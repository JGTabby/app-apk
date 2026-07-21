package dev.codex.clipbatterylink;

interface LinkListener {
    void onStatus(String status);
    void onMessage(String json);
    void onDisconnected(String reason);
}
