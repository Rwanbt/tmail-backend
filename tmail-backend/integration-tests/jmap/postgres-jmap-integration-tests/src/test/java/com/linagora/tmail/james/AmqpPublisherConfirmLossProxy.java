package com.linagora.tmail.james;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Transport-level fault between the real James RabbitMQ client and a real
 * RabbitMQ broker. No synthetic publisher result or mocked queue is used.
 * All client->broker frames including Basic.Publish and content are forwarded.
 * Only broker->publisher Basic.Ack frames for the spool exchange are lost.
 */
final class AmqpPublisherConfirmLossProxy implements AutoCloseable {
    private static final String SPOOL_EXCHANGE = "JamesMailQueue-exchange-spool";
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    private final String brokerHost;
    private final int brokerPort;
    private final ServerSocket listener;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicInteger spoolPublishes = new AtomicInteger();
    private final AtomicInteger droppedConfirms = new AtomicInteger();
    private final AtomicReference<byte[]> publishedReference = new AtomicReference<>();

    AmqpPublisherConfirmLossProxy(String brokerHost, int brokerPort) throws IOException {
        this.brokerHost = brokerHost;
        this.brokerPort = brokerPort;
        listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        workers.submit(this::acceptConnections);
    }

    int localPort() {
        return listener.getLocalPort();
    }

    void arm() {
        armed.set(true);
    }

    int spoolPublishes() {
        return spoolPublishes.get();
    }

    int droppedConfirms() {
        return droppedConfirms.get();
    }

    byte[] publishedReference() {
        return publishedReference.get();
    }

    private void acceptConnections() {
        while (!listener.isClosed()) {
            try {
                Socket client = listener.accept();
                Socket broker = new Socket(brokerHost, brokerPort);
                client.setTcpNoDelay(true);
                broker.setTcpNoDelay(true);
                sockets.add(client);
                sockets.add(broker);
                Set<Integer> spoolChannels = ConcurrentHashMap.newKeySet();
                workers.submit(() -> proxyFrames(client, broker, spoolChannels, true));
                workers.submit(() -> proxyFrames(broker, client, spoolChannels, false));
            } catch (IOException closed) {
                if (!listener.isClosed()) {
                    throw new IllegalStateException("RabbitMQ proxy failed before fault injection", closed);
                }
            }
        }
    }

    private void proxyFrames(Socket source, Socket target, Set<Integer> spoolChannels, boolean towardBroker) {
        try {
            DataInputStream in = new DataInputStream(source.getInputStream());
            DataOutputStream out = new DataOutputStream(target.getOutputStream());
            if (towardBroker) {
                // AMQP 0-9-1 client connection preamble, not a normal frame.
                byte[] preamble = new byte[8];
                in.readFully(preamble);
                if (!new String(preamble, 0, 4, StandardCharsets.US_ASCII).equals("AMQP")) {
                    throw new IOException("unexpected RabbitMQ protocol preamble");
                }
                out.write(preamble);
                out.flush();
            }
            while (true) {
                int type = in.readUnsignedByte();
                int channel = in.readUnsignedShort();
                int size = in.readInt();
                if (size < 0 || size > MAX_FRAME_BYTES) {
                    throw new IOException("invalid AMQP frame length");
                }
                byte[] payload = new byte[size];
                in.readFully(payload);
                if (in.readUnsignedByte() != 0xce) {
                    throw new IOException("invalid AMQP frame terminator");
                }
                if (towardBroker && isSpoolPublish(type, payload)) {
                    // Arm before the HTTP request: a genuine Basic.Publish
                    // reaches the real broker, not a generated in-memory ACK.
                    spoolChannels.add(channel);
                    if (armed.get()) {
                        spoolPublishes.incrementAndGet();
                    }
                }
                if (towardBroker && armed.get() && type == 3 && spoolChannels.contains(channel)) {
                    publishedReference.compareAndSet(null, payload);
                }
                boolean drop = !towardBroker && armed.get() && spoolChannels.contains(channel)
                    && isPublisherAck(type, payload);
                if (drop) {
                    droppedConfirms.incrementAndGet();
                    continue;
                }
                out.writeByte(type);
                out.writeShort(channel);
                out.writeInt(size);
                out.write(payload);
                out.writeByte(0xce);
                out.flush();
            }
        } catch (EOFException closed) {
            // A peer closed its side after a timed-out publisher confirm.
        } catch (IOException closed) {
            // Closing sockets during teardown also terminates the two pipes.
        } finally {
            try {
                source.close();
                target.close();
            } catch (IOException ignored) {
                // Peer is already closed.
            }
        }
    }

    private static boolean isPublisherAck(int type, byte[] body) {
        return type == 1 && body.length >= 4 && u16(body, 0) == 60 && u16(body, 2) == 80;
    }

    private static boolean isSpoolPublish(int type, byte[] body) {
        // Basic.Publish: class=60, method=40, ticket=short, exchange=shortstr.
        if (type != 1 || body.length < 7 || u16(body, 0) != 60 || u16(body, 2) != 40) {
            return false;
        }
        int length = body[6] & 0xff;
        return body.length >= 7 + length
            && SPOOL_EXCHANGE.equals(new String(body, 7, length, StandardCharsets.UTF_8));
    }

    private static int u16(byte[] value, int offset) {
        return ((value[offset] & 0xff) << 8) | (value[offset + 1] & 0xff);
    }

    @Override
    public void close() throws IOException {
        armed.set(false);
        listener.close();
        for (Socket socket : sockets) {
            socket.close();
        }
        workers.shutdownNow();
    }
}
