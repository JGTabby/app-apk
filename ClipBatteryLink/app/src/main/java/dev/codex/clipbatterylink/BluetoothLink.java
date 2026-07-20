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

final class BluetoothLink {
    interface Listener {
        void onStatus(String status);
        void onMessage(String json);
        void onDisconnected(String reason);
    }

    private static final String SERVICE_NAME = "ClipBatteryLink";
    private static final UUID SERVICE_UUID =
            UUID.fromString("6b468b8a-20c5-4a8d-b94d-6db50d4c7fb7");

    private final BluetoothAdapter adapter;
    private final Listener listener;
    private Thread worker;
    private BluetoothServerSocket serverSocket;
    private BluetoothSocket socket;
    private BufferedWriter writer;

    BluetoothLink(BluetoothAdapter adapter, Listener listener) {
        this.adapter = adapter;
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    void startServer() {
        close();
        worker = new Thread(() -> {
            try {
                listener.onStatus("Waiting for paired phone...");
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
                BluetoothSocket accepted = serverSocket.accept();
                synchronized (this) {
                    closeQuietly(serverSocket);
                    serverSocket = null;
                }
                attachSocket(accepted, "Connected: " + deviceName(accepted.getRemoteDevice()));
            } catch (IOException error) {
                listener.onDisconnected("Server stopped: " + error.getMessage());
            }
        }, "clip-link-server");
        worker.start();
    }

    @SuppressLint("MissingPermission")
    void connect(BluetoothDevice device) {
        close();
        worker = new Thread(() -> {
            try {
                listener.onStatus("Connecting to " + deviceName(device) + "...");
                adapter.cancelDiscovery();
                BluetoothSocket outgoing = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
                outgoing.connect();
                attachSocket(outgoing, "Connected: " + deviceName(device));
            } catch (IOException error) {
                listener.onDisconnected("Connection failed: " + error.getMessage());
            }
        }, "clip-link-client");
        worker.start();
    }

    synchronized boolean send(String json) {
        if (writer == null) {
            listener.onStatus("Not connected yet.");
            return false;
        }
        try {
            writer.write(json);
            writer.newLine();
            writer.flush();
            return true;
        } catch (IOException error) {
            listener.onDisconnected("Send failed: " + error.getMessage());
            close();
            return false;
        }
    }

    synchronized boolean isConnected() {
        return writer != null && socket != null && socket.isConnected();
    }

    synchronized void close() {
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

        String disconnectReason = "Disconnected.";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connectedSocket.getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            String line;
            while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                listener.onMessage(line);
            }
        } catch (IOException error) {
            disconnectReason = "Disconnected: " + error.getMessage();
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
            return "paired phone";
        }
        return name;
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
