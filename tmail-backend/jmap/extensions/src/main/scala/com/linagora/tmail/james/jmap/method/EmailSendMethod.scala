/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.tmail.james.jmap.method

import java.time.ZonedDateTime
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Date
import java.util.UUID

import com.google.inject.multibindings.{Multibinder, ProvidesIntoSet}
import com.google.inject.{AbstractModule, Scopes}
import com.linagora.tmail.james.jmap.json.EmailSendSerializer
import com.linagora.tmail.james.jmap.method.CapabilityIdentifier.LINAGORA_PGP
import com.linagora.tmail.james.jmap.model.EmailSubmissionHelper.resolveEnvelope
import com.linagora.tmail.james.jmap.model.{EmailSendCreationId, EmailSendCreationRequest, EmailSendCreationRequestInvalidException, EmailSendCreationResponse, EmailSubmissionOutcomeUnknownException, EmailSubmissionDefinitivelyRejectedException, EmailSendRequest, EmailSendResults, EmailSetCreationFailure, EmailSetCreationResult, EmailSetCreationSuccess, EmailSubmissionCreationRequest, MimeMessageSourceImpl}
import eu.timepit.refined.auto._
import jakarta.annotation.PreDestroy
import jakarta.inject.Inject
import jakarta.mail.Flags
import jakarta.mail.MessagingException
import jakarta.mail.internet.{InternetAddress, MimeMessage}
import org.apache.james.core.{MailAddress, Username}
import org.apache.james.jmap.JMAPConfiguration
import org.apache.james.jmap.api.model.Size
import org.apache.james.jmap.core.CapabilityIdentifier.{CapabilityIdentifier, EMAIL_SUBMISSION, JMAP_CORE, JMAP_MAIL}
import org.apache.james.jmap.core.Invocation.{Arguments, MethodName}
import org.apache.james.jmap.core.{Invocation, SessionTranslator, UTCDate, UuidState}
import org.apache.james.jmap.json.EmailSetSerializer
import org.apache.james.jmap.mail.{BlobId, EmailCreationRequest, EmailCreationResponse, EmailSubmissionId, Envelope, ThreadId}
import org.apache.james.jmap.method.EmailSubmissionSetMethod.LOGGER
import org.apache.james.jmap.method.{EmailSetMethod, ForbiddenFromException, ForbiddenMailFromException, InvocationWithContext, Method, MethodRequiringAccountId, NoRecipientException, SizeExceededException}
import org.apache.james.jmap.routes.{BlobResolvers, ProcessingContext, SessionSupplier}
import org.apache.james.lifecycle.api.{LifecycleUtil, Startable}
import org.apache.james.mailbox.MessageManager.AppendCommand
import org.apache.james.mailbox.model.{MailboxId, MessageId}
import org.apache.james.mailbox.{MailboxManager, MailboxSession, MessageIdManager}
import org.apache.james.metrics.api.MetricFactory
import org.apache.james.mime4j.dom.Message
import org.apache.james.mime4j.message.DefaultMessageWriter
import org.apache.james.queue.api.MailQueueFactory.SPOOL
import org.apache.james.queue.api.{MailQueue, MailQueueFactory}
import org.apache.james.rrt.api.CanSendFrom
import org.apache.james.server.core.{MailImpl, MimeMessageSource, MimeMessageWrapper}
import org.apache.james.util.ReactorUtils
import org.apache.james.util.html.HtmlTextExtractor
import org.apache.james.utils.{InitializationOperation, InitilizationOperationBuilder}
import org.apache.mailet.{Attribute, AttributeValue, Mail}
import org.reactivestreams.Publisher
import play.api.libs.json.{JsArray, JsError, JsObject, JsSuccess, JsValue, Json}
import reactor.core.publisher.Mono
import reactor.core.scala.publisher.{SFlux, SMono}
import reactor.core.scheduler.Schedulers
import reactor.util.concurrent.Queues

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.{Failure, Success, Try}

class EmailSendMethodModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[EmailSendMethod]).in(Scopes.SINGLETON)
    bind(classOf[EmailSendOutbox]).to(classOf[DisabledEmailSendOutbox]).in(Scopes.SINGLETON)

    Multibinder.newSetBinder(binder(), classOf[Method])
      .addBinding()
      .to(classOf[EmailSendMethod])
  }

  @ProvidesIntoSet
  def initEmailSends(instance: EmailSendMethod): InitializationOperation = {
    InitilizationOperationBuilder.forClass(classOf[EmailSendMethod])
      .init(new org.apache.james.utils.InitilizationOperationBuilder.Init() {
        override def init(): Unit = instance.init
      })
  }
}

object EmailSendMethod {
  private[method] def canonicalJson(value: JsValue): String = value match {
    case obj: JsObject =>
      obj.fields.sortBy(_._1).map { case (key, field) =>
        Json.stringify(play.api.libs.json.JsString(key)) + ":" + canonicalJson(field)
      }.mkString("{", ",", "}")
    case array: JsArray => array.value.map(canonicalJson).mkString("[", ",", "]")
    case primitive => Json.stringify(primitive)
  }

  private[method] def sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x").mkString

  // Pinned Apache James RabbitMQ Enqueuer.saveMail throws this exact
  // MailQueueException synchronously BEFORE publishing a queue reference.
  // Only this narrow case proves failure before broker acceptance.
  // Fail before appending the sender copy when startup has not supplied a queue.
  // A null queue otherwise fails only after persistence, leaving an orphan.
  private[method] def requireReadyQueue(queue: MailQueue): SMono[Unit] =
    if (queue == null) SMono.error(new IllegalStateException("Email/send queue not initialized"))
    else SMono.just(())

  private[method] def isProvenPrePublicationFailure(error: Throwable): Boolean = error match {
    case exception: MailQueue.MailQueueException =>
      exception.getMessage == "Error while saving blob" &&
        Option(exception.getNextException).exists(_.isInstanceOf[MessagingException])
    case _ => false
  }

  private[method] def preserveUncertainOnCleanupFailure[A](
      cleanup: SMono[A],
      uncertain: EmailSubmissionOutcomeUnknownException): SMono[A] =
    cleanup.onErrorResume(_ => SMono.error(uncertain))
      .switchIfEmpty(SMono.error(uncertain))

  // Reactor owns cleanup on completion, error AND cancellation. Keep it
  // asynchronous, as a MIME-backed resource may perform blocking I/O.
  private[method] def withResourceCleanup[A, B](
      resource: A,
      operation: A => Publisher[B],
      cleanup: A => Unit): Mono[B] =
    Mono.usingWhen(
      Mono.just(resource),
      (value: A) => Mono.from(operation(value)),
      (value: A) => Mono.fromRunnable(() => cleanup(value))
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorComplete(error => {
          // An enqueue may already have committed the message to the queue.
          // Letting a disposal exception turn that success into a JMAP error
          // makes a client retry an already submitted email.
          LOGGER.warn("Failed to release an Email/send MIME resource", error)
          true
        }))
}

class EmailSendMethod @Inject()(emailSetSerializer: EmailSetSerializer,
                                mailQueueFactory: MailQueueFactory[_ <: MailQueue],
                                canSendFrom: CanSendFrom,
                                blobResolvers: BlobResolvers,
                                htmlTextExtractor: HtmlTextExtractor,
                                mailboxManager: MailboxManager,
                                messageIdManager: MessageIdManager,
                                emailSetMethod: EmailSetMethod,
                                configuration: JMAPConfiguration,
                                val metricFactory: MetricFactory,
                                val sessionTranslator: SessionTranslator,
                                val sessionSupplier: SessionSupplier,
                                outbox: EmailSendOutbox) extends MethodRequiringAccountId[EmailSendRequest] with Startable {

  override val methodName: MethodName = MethodName("Email/send")

  override val requiredCapabilities: Set[CapabilityIdentifier] = Set(JMAP_CORE, JMAP_MAIL, EMAIL_SUBMISSION, LINAGORA_PGP)

  var queue: MailQueue = _

  def init: Unit = queue = mailQueueFactory.createQueue(SPOOL)

  @PreDestroy def dispose: Unit =
    Try(queue.close())
      .recover(e => LOGGER.debug("error closing queue", e))

  override def getRequest(mailboxSession: MailboxSession,
                          invocation: Invocation): Either[Exception, EmailSendRequest] =
    EmailSendSerializer.deserializeEmailSendRequest(invocation.arguments.value)
      .asEitherRequest
      .flatMap(emailSendRequest => emailSendRequest.validate)

  override def doProcess(capabilities: Set[CapabilityIdentifier],
                         invocation: InvocationWithContext,
                         mailboxSession: MailboxSession,
                         request: EmailSendRequest): Publisher[InvocationWithContext] =
    create(request, mailboxSession, invocation.processingContext)
      .flatMapMany(createResults => {
        val explicitInvocation: InvocationWithContext = InvocationWithContext(
          invocation = Invocation(
            methodName = invocation.invocation.methodName,
            arguments = Arguments(EmailSendSerializer.serializeEmailSendResponse(
              createResults._1.asResponse(request.accountId, UuidState.INSTANCE))
              .as[JsObject]),
            methodCallId = invocation.invocation.methodCallId),
          processingContext = createResults._2)

        val emailSetCall: SMono[InvocationWithContext] = request.implicitEmailSetRequest(createResults._1.resolveMessageId)
          .fold(e => SMono.error(e),
            maybeEmailSetRequest => maybeEmailSetRequest.map(emailSetRequest => emailSetMethod.doProcess(
              capabilities = capabilities,
              invocation = invocation,
              mailboxSession = mailboxSession,
              request = emailSetRequest))
              .getOrElse(SMono.empty))

        SFlux.concat(SMono.just(explicitInvocation), emailSetCall)
      })

  private def create(request: EmailSendRequest,
                     mailboxSession: MailboxSession,
                     processingContext: ProcessingContext): SMono[(EmailSendResults, ProcessingContext)] =
    SFlux.fromIterable(request.create.view)
      .fold[SMono[(EmailSendResults, ProcessingContext)]](SMono.just(EmailSendResults.empty -> processingContext)) {
        (acc: SMono[(EmailSendResults, ProcessingContext)], elem: (EmailSendCreationId, JsObject)) => {
          val (emailSendCreationId, jsObject) = elem
          acc.flatMap {
            case (creationResult, processingContext) =>
              createEach(emailSendCreationId, jsObject, mailboxSession, processingContext)
                .map(e => EmailSendResults.merge(creationResult, e._1) -> e._2)
          }
        }
      }
      .flatMap(any => any)
      .subscribeOn(ReactorUtils.BLOCKING_CALL_WRAPPER)

  private def createEach(clientId: EmailSendCreationId,
                         jsObject: JsObject,
                         mailboxSession: MailboxSession,
                         processingContext: ProcessingContext): SMono[(EmailSendResults, ProcessingContext)] = {
    parseCreationRequest(jsObject)
      .fold(error => SMono.error(error),
        request => createEmailAndEmailSubmission(clientId, mailboxSession, request, jsObject))
      .map(response => EmailSendResults.created(clientId, response) -> processingContext)
      .onErrorResume(error => SMono.just(EmailSendResults.notCreated(clientId, error) -> processingContext))
  }

  private def createEmailAndEmailSubmission(clientId: EmailSendCreationId,
                                            mailboxSession: MailboxSession,
                                            request: EmailSendCreationRequest,
                                            sourceJson: JsObject): SMono[EmailSendCreationResponse] = {
    // Opt-in until all legacy clients can supply a stable idempotencyKey.
    // The key is scoped to the authenticated principal, never to a JMAP call id
    // (which changes across client retries).
    val durableKey = request.idempotencyKey.filter(_ => outbox.enabled)
      .map(key => EmailSendMethod.sha256(mailboxSession.getUser.asString() + "\u0000" + key))
    val reservation: SMono[Unit] = durableKey match {
      case Some(key) =>
        val payload = EmailSendMethod.canonicalJson(sourceJson)
        outbox.reserve(key, EmailSendMethod.sha256(payload), payload)
          .flatMap(isNew => if (isNew) SMono.just(())
            else SMono.error(EmailSubmissionOutcomeUnknownException(
              new IllegalStateException("submission already reserved"))))
      case None => SMono.just(())
    }
    EmailSendMethod.requireReadyQueue(queue)
      .flatMap(_ => reservation)
      .flatMap(_ => createEmail(clientId, mailboxSession, request.emailCreate, request.emailSubmissionSet.envelope))
      .flatMap {
        case failure: EmailSetCreationFailure => SMono.error(failure.error)
        case success: EmailSetCreationSuccess =>
          createEmailSubmission(
            mailboxSession,
            success.response,
            success.originalMessage,
            request.emailSubmissionSet,
            durableKey)
            .onErrorResume {
              case uncertain: EmailSubmissionOutcomeUnknownException
                  if durableKey.isEmpty && EmailSendMethod.isProvenPrePublicationFailure(uncertain.getCause) =>
                // An error while storing the MIME blob predates any publish.
                // Only this failure allows safe, precisely targeted cleanup.
                EmailSendMethod.preserveUncertainOnCleanupFailure(
                  SMono.fromPublisher(messageIdManager.deleteReactive(
                    List(success.response.id).asJava,
                    request.emailCreate.mailboxIds.value.asJava,
                    mailboxSession)),
                  uncertain)
                  .flatMap(result =>
                    if (result.getDestroyed.contains(success.response.id)) {
                      SMono.error[EmailSendCreationResponse](EmailSubmissionDefinitivelyRejectedException())
                    } else {
                      // No deletion proof: never invite an unsafe retry.
                      SMono.error[EmailSendCreationResponse](uncertain)
                    })
              case unknown => SMono.error[EmailSendCreationResponse](unknown)
            }
      }
  }

  private def parseCreationRequest(jsObject: JsObject): Either[EmailSendCreationRequestInvalidException, EmailSendCreationRequest] =
    EmailSendCreationRequest.validateProperties(jsObject)
      .flatMap(validJson => EmailSendSerializer.deserializeEmailSendCreationRequest(validJson) match {
        case JsSuccess(createRequest, _) => createRequest.validate()
        case JsError(errors) => Left(EmailSendCreationRequestInvalidException.parse(errors))
      })
      .flatMap(createRequestRaw => createRequestRaw.toModel(emailSetSerializer))

  def createEmail(clientId: EmailSendCreationId,
                  mailboxSession: MailboxSession,
                  request: EmailCreationRequest,
                  requestedEnvelope: Option[Envelope]): SMono[EmailSetCreationResult] = {
    val mailboxIds: List[MailboxId] = request.mailboxIds.value
    if (mailboxIds.size != 1) {
      SMono.just(EmailSetCreationFailure(clientId, new IllegalArgumentException("mailboxIds need to have size 1")))
    } else {
      request.toMime4JMessage(blobResolvers, htmlTextExtractor, mailboxSession)
        .flatMap(message => validateBeforeAppend(message, mailboxSession, requestedEnvelope)
          .flatMap(_ => append(clientId, request, message, mailboxSession, mailboxIds)))
        .onErrorResume(e => SMono.just[EmailSetCreationResult](EmailSetCreationFailure(clientId, e)))
    }
  }

  // Reject invalid envelopes and forbidden senders before persisting a message.
  // Post-append validation remains in place to account for permission changes.
  private def validateBeforeAppend(message: Message,
                                   mailboxSession: MailboxSession,
                                   requestedEnvelope: Option[Envelope]): SMono[Unit] =
    SMono.fromCallable(() => DefaultMessageWriter.asBytes(message))
      .flatMap(bytes => SMono.fromTry(toMimeMessage("preflight", bytes)))
      .flatMap(mime => SMono.fromPublisher(EmailSendMethod.withResourceCleanup(
        mime,
        (pending: MimeMessageWrapper) => SMono.fromTry(resolveEnvelope(pending, requestedEnvelope))
          .flatMap(envelope => validate(mailboxSession)(pending, envelope))
          .asJava(),
        (pending: MimeMessageWrapper) => LifecycleUtil.dispose(pending))))
      .map(_ => ())

  def createEmailSubmission(mailboxSession: MailboxSession,
                            emailCreationResponse: EmailCreationResponse,
                            originalMessage: Array[Byte],
                            request: EmailSubmissionCreationRequest,
                            durableKey: Option[String] = None): SMono[EmailSendCreationResponse] = {
    val emailId: MessageId = emailCreationResponse.id
    val submissionId: EmailSubmissionId = EmailSubmissionId.generate
    val durableOutbox = if (durableKey.isDefined) outbox else new DisabledEmailSendOutbox
    val owner = UUID.randomUUID().toString
    SMono.fromTry(toMimeMessage(submissionId.value, originalMessage))
      .flatMap(message => SMono.fromPublisher(EmailSendMethod.withResourceCleanup(
        message,
        (initial: MimeMessageWrapper) => {
          for {
            envelope <- SMono.fromTry(resolveEnvelope(initial, request.envelope))
            _ <- validate(mailboxSession)(initial, envelope)
            staged <- durableOutbox.stageAndClaim(durableKey.getOrElse(""), owner,
              OutboxMail(originalMessage, envelope.mailFrom.email.asString,
                envelope.rcptTo.map(_.email.asString), mailboxSession.getUser.asString(), submissionId.value.value))
            outgoing <- SMono.fromTry(toMimeMessage(staged.submissionId, staged.mime))
            mail <- SMono.fromCallable(() => {
              val mailImpl = MailImpl.builder()
                .name(staged.submissionId)
                .addRecipients(staged.recipients.map(new MailAddress(_)).asJava)
                .sender(new MailAddress(staged.from))
                .addAttribute(new Attribute(Mail.JMAP_AUTH_USER, AttributeValue.of(staged.authUser)))
                .build()
              mailImpl.setMessageNoCopy(outgoing)
              mailImpl
            })
            _ <- SMono.fromPublisher(EmailSendMethod.withResourceCleanup(
              mail,
              (pending: MailImpl) => queue.enqueueReactive(pending),
              (pending: MailImpl) => LifecycleUtil.dispose(pending)))
              // Any RabbitMQ error can follow broker acceptance; permanently
              // block automatic resend, including when the uncertainty write fails.
              .onErrorResume(error => {
                val uncertain = EmailSubmissionOutcomeUnknownException(error)
                durableOutbox.finish(durableKey.getOrElse(""), owner, accepted = false)
                  .onErrorResume(_ => SMono.just(()))
                  .flatMap(_ => SMono.error[Unit](uncertain))
              })
            _ <- durableOutbox.finish(durableKey.getOrElse(""), owner, accepted = true)
          } yield EmailSendCreationResponse(
            emailSubmissionId = submissionId,
            emailId = emailId,
            blobId = emailCreationResponse.blobId,
            threadId = emailCreationResponse.threadId,
            size = emailCreationResponse.size)
        }.asJava(),
        (pending: MimeMessageWrapper) => LifecycleUtil.dispose(pending))))
  }

  def toMimeMessage(name: String, message: Array[Byte]): Try[MimeMessageWrapper] = {
    val source: MimeMessageSource = MimeMessageSourceImpl(name, message)
    Try(new MimeMessageWrapper(source))
      .recover(e => {
        LifecycleUtil.dispose(source)
        throw e
      })
  }

  private def validate(session: MailboxSession)(mimeMessage: MimeMessage, envelope: Envelope): SMono[MimeMessage] =
    SFlux.fromIterable(Option(mimeMessage.getSender).toList ++ Option(mimeMessage.getFrom).toList.flatten)
      .map(_.asInstanceOf[InternetAddress].getAddress)
      .filterWhen(addressAsString => SMono.fromPublisher(canSendFrom.userCanSendFromReactive(session.getUser, Username.fromMailAddress(new MailAddress(addressAsString))))
        .map(Boolean.unbox(_)).map(!_), Queues.SMALL_BUFFER_SIZE)
      .collectSeq()
      .flatMap(forbiddenMailFrom => {
        if (forbiddenMailFrom.nonEmpty) {
          SMono.just(Failure(ForbiddenMailFromException(forbiddenMailFrom.toList)))
        } else if (envelope.rcptTo.isEmpty) {
          SMono.just(Failure(NoRecipientException()))
        } else {
          SMono.fromPublisher(canSendFrom.userCanSendFromReactive(session.getUser, Username.fromMailAddress(envelope.mailFrom.email)))
            .filter(bool => bool.equals(false))
            .map(_ => Failure(ForbiddenFromException(envelope.mailFrom.email.asString)))
            .switchIfEmpty(SMono.just(Success(mimeMessage)))
        }
      })
      .handle[MimeMessage]((aTry, sink) => {
        aTry match {
          case Success(mimeMessage) => sink.next(mimeMessage)
          case Failure(ex) => sink.error(ex)
        }
      })

  private def append(clientId: EmailSendCreationId,
                     request: EmailCreationRequest,
                     message: Message,
                     mailboxSession: MailboxSession,
                     mailboxIds: List[MailboxId]): SMono[EmailSetCreationSuccess] =
    for {
      mailbox <- SMono(mailboxManager.getMailboxReactive(mailboxIds.head, mailboxSession))
      messageAsBytes = DefaultMessageWriter.asBytes(message)
      appendCommandEither = Right(AppendCommand.builder()
        .recent()
        .withFlags(request.keywords.map(_.asFlags).getOrElse(new Flags()))
        .withInternalDate(Date.from(request.receivedAt.getOrElse(UTCDate(ZonedDateTime.now())).asUTC.toInstant))
        .build(messageAsBytes))
        .flatMap(appendCommand =>
          configuration.getMaximumSendSize.toScala
            .filter(limit => appendCommand.getMsgIn.size() > limit)
            .map(limit => Left(SizeExceededException(appendCommand.getMsgIn.size(), limit)))
            .getOrElse(Right(appendCommand)))

      appendResult <- appendCommandEither.fold(SMono.error(_),
        appendCommand => SMono(mailbox.appendMessageReactive(appendCommand, mailboxSession)))
    } yield {
      val blobId: Option[BlobId] = BlobId.of(appendResult.getId.getMessageId).toOption
      val threadId: ThreadId = ThreadId.fromJava(appendResult.getThreadId)
      EmailSetCreationSuccess(clientId,
        EmailCreationResponse(appendResult.getId.getMessageId, blobId, threadId, Size.sanitizeSize(appendResult.getSize)),
        messageAsBytes)
    }
}
