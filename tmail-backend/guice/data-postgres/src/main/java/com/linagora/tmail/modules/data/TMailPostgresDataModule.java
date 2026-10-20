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
 *                                                                  *
 *  This file was taken and adapted from the Apache James project.  *
 *                                                                  *
 *  https://james.apache.org                                        *
 *                                                                  *
 *  It was originally licensed under the Apache V2 license.         *
 *                                                                  *
 *  http://www.apache.org/licenses/LICENSE-2.0                      *
 ********************************************************************/

package com.linagora.tmail.modules.data;

import org.apache.james.CoreDataModule;
import org.apache.james.backends.postgres.PostgresDataDefinition;
import org.apache.james.modules.data.PostgresMailRepositoryModule;
import org.apache.james.modules.data.PostgresRecipientRewriteTableModule;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import com.linagora.tmail.jmap.postgres.EmailSendOutboxDataDefinition;

public class TMailPostgresDataModule extends AbstractModule {
    @Override
    protected void configure() {
        install(new CoreDataModule());
        Multibinder.newSetBinder(binder(), PostgresDataDefinition.class)
            .addBinding().toInstance(EmailSendOutboxDataDefinition.MODULE);
        install(new TMailPostgresDomainListModule());
        install(new PostgresRecipientRewriteTableModule());
        install(new PostgresMailRepositoryModule());
    }
}
