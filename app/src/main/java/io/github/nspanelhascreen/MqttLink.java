package io.github.nspanelhascreen;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * A minimal MQTT 3.1.1 client: retained QoS 0 publishes, a last will, keep-alive pings and reconnects.
 * The latest message per topic is kept and sent again after every reconnect, so the broker always has it.
 */
final class MqttLink {
    private static final String TAG = "MqttLink";
    private static final int KEEP_ALIVE_S = 60;
    private static final int SILENCE_MS = 30_000; //< nothing heard this long: ping; twice in a row: the link is dead
    private static final int SILENCES_ALLOWED = 1;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final long RETRY_MIN_MS = 5_000;
    private static final long RETRY_MAX_MS = 120_000;

    private static final int PROTOCOL_LEVEL = 4; //< MQTT 3.1.1
    private static final int TYPE_CONNECT = 0x10;
    private static final int TYPE_CONNACK = 0x20;
    private static final int TYPE_PUBLISH_RETAINED = 0x31;
    private static final int TYPE_PINGREQ = 0xC0;
    private static final int TYPE_MASK = 0xF0;
    private static final int FLAG_USERNAME = 0x80;
    private static final int FLAG_PASSWORD = 0x40;
    private static final int FLAG_WILL_RETAIN = 0x20;
    private static final int FLAG_WILL = 0x04;
    private static final int FLAG_CLEAN_SESSION = 0x02;
    private static final int CONNACK_ACCEPTED = 0;
    private static final int LENGTH_DIGIT = 0x80; //< remaining length: 7 bits a byte, high bit = more follow
    private static final int LENGTH_BITS = 0x7F;
    private static final int LENGTH_SHIFT = 7;
    private static final int LENGTH_MAX_DIGITS = 4;
    private static final int BYTE_MASK = 0xFF;
    private static final int BYTE_BITS = 8;

    private final String host;
    private final int port;
    private final String clientId;
    private final String user;
    private final String password;
    private final String willTopic;
    private final String willPayload;
    private final Map<String, byte[]> latest = new LinkedHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final Object outLock = new Object();
    private volatile boolean running;
    private volatile Socket socket;
    private OutputStream out; //< guarded by outLock; null while disconnected
    private Thread reader;

    /** willTopic/willPayload: what the broker publishes (retained) when this client drops off. user may be empty. */
    MqttLink(String host, int port, String clientId, String user, String password, String willTopic, String willPayload) {
        this.host = host;
        this.port = port;
        this.clientId = clientId;
        this.user = user;
        this.password = password;
        this.willTopic = willTopic;
        this.willPayload = willPayload;
    }

    void start() {
        if (running) return;
        running = true;
        reader = new Thread(this::run, "mqtt");
        reader.start();
    }

    /** Drops the connection without a goodbye, so the broker publishes the last will. */
    void stop() {
        running = false;
        closeSocket();
        writer.shutdownNow();
        if (reader != null) reader.interrupt();
    }

    /** Publishes retained; kept and sent again after a reconnect. Any thread. */
    void publish(String topic, String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        synchronized (latest) {
            latest.put(topic, body);
        }
        queue(() -> send(topic, body));
    }

    private void run() {
        long retryMs = RETRY_MIN_MS;
        while (running) {
            try {
                Socket connection = new Socket();
                socket = connection;
                connection.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                connection.setSoTimeout(SILENCE_MS);
                InputStream in = new BufferedInputStream(connection.getInputStream());
                OutputStream rawOut = connection.getOutputStream();
                rawOut.write(connectPacket());
                rawOut.flush();
                byte[] connack = readPacket(in, TYPE_CONNACK);
                if (connack.length < 2 || connack[1] != CONNACK_ACCEPTED) {
                    throw new IOException("broker refused the connection, code " + (connack.length < 2 ? -1 : connack[1]));
                }
                synchronized (outLock) {
                    out = rawOut;
                }
                retryMs = RETRY_MIN_MS;
                queue(this::sendAll);
                listen(in);
            } catch (IOException error) {
                if (running) Log.w(TAG, "MQTT " + host + ":" + port + ": " + error.getMessage());
            } finally {
                synchronized (outLock) {
                    out = null;
                }
                closeSocket();
            }
            if (!running) break;
            try {
                Thread.sleep(retryMs);
            } catch (InterruptedException stopped) {
                break;
            }
            retryMs = Math.min(retryMs * 2, RETRY_MAX_MS);
        }
    }

    /** Reads (and ignores) what the broker sends; pings when it's quiet, gives up when the pings go unanswered. */
    private void listen(InputStream in) throws IOException {
        int silences = 0;
        while (running) {
            int first;
            try {
                first = in.read();
            } catch (SocketTimeoutException quiet) {
                silences++;
                if (silences > SILENCES_ALLOWED) throw new IOException("broker stopped answering");
                write(new byte[]{(byte) TYPE_PINGREQ, 0});
                continue;
            }
            if (first < 0) throw new IOException("broker closed the connection");
            silences = 0;
            readBody(in);
        }
    }

    private void queue(Runnable task) {
        try {
            writer.execute(task);
        } catch (RejectedExecutionException stopped) {
            // stopping: nothing more goes out
        }
    }

    private void sendAll() {
        List<Map.Entry<String, byte[]>> all;
        synchronized (latest) {
            all = new ArrayList<>(latest.entrySet());
        }
        for (Map.Entry<String, byte[]> message : all) send(message.getKey(), message.getValue());
    }

    private void send(String topic, byte[] body) {
        try {
            write(publishPacket(topic, body));
        } catch (IOException error) {
            closeSocket(); //< the reader notices and reconnects
        }
    }

    private void write(byte[] packet) throws IOException {
        synchronized (outLock) {
            if (out == null) return; //< not connected: sent after the next connect
            out.write(packet);
            out.flush();
        }
    }

    private void closeSocket() {
        Socket current = socket;
        if (current == null) return;
        try {
            current.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    private byte[] connectPacket() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeString(body, "MQTT");
        body.write(PROTOCOL_LEVEL);
        int flags = FLAG_CLEAN_SESSION | FLAG_WILL | FLAG_WILL_RETAIN;
        boolean withUser = user != null && !user.isEmpty();
        if (withUser) flags |= FLAG_USERNAME | FLAG_PASSWORD;
        body.write(flags);
        body.write(KEEP_ALIVE_S >> BYTE_BITS);
        body.write(KEEP_ALIVE_S & BYTE_MASK);
        writeString(body, clientId);
        writeString(body, willTopic);
        writeString(body, willPayload);
        if (withUser) {
            writeString(body, user);
            writeString(body, password == null ? "" : password);
        }
        return packet(TYPE_CONNECT, body.toByteArray());
    }

    private static byte[] publishPacket(String topic, byte[] payload) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeString(body, topic);
        body.write(payload, 0, payload.length);
        return packet(TYPE_PUBLISH_RETAINED, body.toByteArray());
    }

    private static byte[] packet(int header, byte[] body) {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.write(header);
        int remaining = body.length;
        do {
            int digit = remaining & LENGTH_BITS;
            remaining >>= LENGTH_SHIFT;
            if (remaining > 0) digit |= LENGTH_DIGIT;
            packet.write(digit);
        } while (remaining > 0);
        packet.write(body, 0, body.length);
        return packet.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream stream, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        stream.write(bytes.length >> BYTE_BITS);
        stream.write(bytes.length & BYTE_MASK);
        stream.write(bytes, 0, bytes.length);
    }

    /** One whole packet of the expected type; returns its body. */
    private static byte[] readPacket(InputStream in, int expectedType) throws IOException {
        int first = in.read();
        if (first < 0) throw new IOException("broker closed the connection");
        byte[] body = readBody(in);
        if ((first & TYPE_MASK) != expectedType) throw new IOException("unexpected packet " + first);
        return body;
    }

    private static byte[] readBody(InputStream in) throws IOException {
        int length = 0;
        int shift = 0;
        for (int iDigit = 0; iDigit < LENGTH_MAX_DIGITS; iDigit++) {
            int digit = readByte(in);
            length |= (digit & LENGTH_BITS) << shift;
            shift += LENGTH_SHIFT;
            if ((digit & LENGTH_DIGIT) == 0) break;
        }
        byte[] body = new byte[length];
        int done = 0;
        while (done < length) {
            int count = in.read(body, done, length - done);
            if (count < 0) throw new IOException("broker closed the connection");
            done += count;
        }
        return body;
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) throw new IOException("broker closed the connection");
        return value;
    }
}
