package dev.codex.clipbatterylink;

interface LinkTransport {
    boolean send(String json);
    boolean isConnected();
    void close();
}
