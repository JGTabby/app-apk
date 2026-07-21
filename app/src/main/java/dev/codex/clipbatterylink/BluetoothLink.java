package dev.codex.clipbatterylink;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class BluetoothLink implements LinkTransport {

    private static final String SERVICE_NAME = "MeowLink";
    private static final int MAX_MESSAGE_CHARS = 16_384;
    private static final UUID SERVICE_UUID =
            UUID.fromString("6b468b8a-20c5-4a8d-b94d-6db50d4c7fb7");

    private final BluetoothAdapter adapter;
    private final LinkListener listener;
    private Thread worker;
    private BluetoothServerSocket serverSocket;
    private BluetoothSocket socket;
    private BufferedWriter writer;

    BluetoothLink(BluetoothAdapter adapter, LinkListener listener) {
        this.adapter = adapter;
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    void startServer() {
        close();
        worker = new Thread(() -> {
            try {
                listener.onStatus("正在等待另一支已配對的手機連入…");
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
                BluetoothSocket accepted = serverSocket.accept();
                synchronized (this) {
                    closeQuietly(serverSocket);
                    serverSocket = null;
                }
                attachSocket(accepted, "已連線：" + deviceName(accepted.getRemoteDevice()));
            } catch (Exception error) {
                listener.onDisconnected("等待連線已停止：" + readableError(error));
            }
        }, "clip-link-server");
        worker.start();
    }

    @SuppressLint("MissingPermission")
    void connect(BluetoothDevice device) {
        close();
        worker = new Thread(() -> {
            try {
                listener.onStatus("正在連線到「" + deviceName(device) + "」…");
                adapter.cancelDiscovery();
                BluetoothSocket outgoing = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
                outgoing.connect();
                attachSocket(outgoing, "已連線：" + deviceName(device));
            } catch (Exception error) {
                listener.onDisconnected("連線失敗：" + readableError(error));
            }
        }, "clip-link-client");
        worker.start();
    }

    @Override
    public synchronized boolean send(String json) {
        if (json.length() > MAX_MESSAGE_CHARS) {
            listener.onStatus("資料過長，無法傳送。剪貼簿請縮短後再試。");
            return false;
        }
        if (writer == null) {
            listener.onStatus("尚未連線，請先完成兩支手機的連線。");
            return false;
        }
        try {
            writer.write(json);
            writer.newLine();
            writer.flush();
            return true;
        } catch (IOException error) {
            listener.onDisconnected("資料傳送失敗：" + readableError(error));
            close();
            return false;
        }
    }

    @Override
    public synchronized boolean isConnected() {
        return writer != null && socket != null && socket.isConnected();
    }

    @Override
    public synchronized void close() {
        writer = null;
        closeQuietly(socket);
        closeQuietly(serverSocket);
        socket = null;
        serverSocket = null;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private void attachSocket(BluetoothSocket connectedSocket, String status) throws IOException {
        synchronized (this) {
            socket = connectedSocket;
            writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(),
                    StandardCharsets.UTF_8
            ));
        }
        listener.onStatus(status);

        String disconnectReason = "已與另一支手機中斷連線。";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connectedSocket.getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            String line;
            while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                listener.onMessage(line);
            }
        } catch (IOException error) {
            disconnectReason = "連線已中斷：" + readableError(error);
        } finally {
            synchronized (this) {
                if (socket == connectedSocket) {
                    writer = null;
                    socket = null;
                }
            }
            closeQuietly(connectedSocket);
            listener.onDisconnected(disconnectReason);
        }
    }

    @SuppressLint("MissingPermission")
    private static String deviceName(BluetoothDevice device) {
        String name = device.getName();
        if (name == null || name.trim().isEmpty()) {
            return "已配對的手機";
        }
        return name;
    }

    private static String readableError(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static void closeQuietly(Object closeable) {
        if (closeable == null) {
            return;
        }
        try {
            if (closeable instanceof BluetoothSocket) {
                ((BluetoothSocket) closeable).close();
            } else if (closeable instanceof BluetoothServerSocket) {
                ((BluetoothServerSocket) closeable).close();
            }
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
