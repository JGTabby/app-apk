package dev.codex.clipbatterylink;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Experimental outbound TCP connection for a user-operated relay server. */
final class NetworkRelayLink implements LinkTransport {
    private static final int CONNECT_TIMEOUT_MILLIS = 8_000;
    private static final int MAX_MESSAGE_CHARS = 16_384;

    private final LinkListener listener;
    private Socket socket;
    private BufferedWriter writer;
    private Thread worker;

    NetworkRelayLink(LinkListener listener) {
        this.listener = listener;
    }

    void connect(String host, int port, String roomCode) {
        close();
        if (host.trim().isEmpty() || roomCode.trim().isEmpty()) {
            listener.onDisconnected("請填寫中繼主機與配對代碼。");
            return;
        }
        worker = new Thread(() -> open(host.trim(), port, roomCode.trim()), "meow-network-relay");
        worker.start();
    }

    private void open(String host, int port, String roomCode) {
        Socket connected = new Socket();
        boolean joined = false;
        try {
            listener.onStatus("正在透過網路中繼連線…");
            connected.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            BufferedWriter newWriter = new BufferedWriter(new OutputStreamWriter(
                    connected.getOutputStream(), StandardCharsets.UTF_8));
            synchronized (this) {
                socket = connected;
                writer = newWriter;
            }
            writeLine("{\"type\":\"join\",\"room\":\"" + escape(roomCode) + "\"}");
            joined = true;
            listener.onStatus("已連線：網路中繼（測試模式）");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connected.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                    if (line.length() <= MAX_MESSAGE_CHARS && !line.contains("\"type\":\"join\"")) {
                        listener.onMessage(line);
                    }
                }
            }
        } catch (Exception error) {
            if (!joined) {
                listener.onDisconnected("網路中繼連線失敗：" + readableError(error));
            }
        } finally {
            boolean wasActive;
            synchronized (this) {
                wasActive = socket == connected;
                if (socket == connected) {
                    writer = null;
                    socket = null;
                }
            }
            closeQuietly(connected);
            if (joined && wasActive) {
                listener.onDisconnected("網路中繼已中斷。");
            }
        }
    }

    @Override
    public synchronized boolean send(String json) {
        if (json.length() > MAX_MESSAGE_CHARS || writer == null) {
            return false;
        }
        try {
            writeLine(json);
            return true;
        } catch (IOException error) {
            listener.onDisconnected("網路資料傳送失敗：" + readableError(error));
            close();
            return false;
        }
    }

    private synchronized void writeLine(String value) throws IOException {
        if (writer == null) {
            throw new IOException("尚未連上中繼");
        }
        writer.write(value);
        writer.newLine();
        writer.flush();
    }

    @Override
    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed() && writer != null;
    }

    @Override
    public synchronized void close() {
        writer = null;
        closeQuietly(socket);
        socket = null;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String readableError(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
