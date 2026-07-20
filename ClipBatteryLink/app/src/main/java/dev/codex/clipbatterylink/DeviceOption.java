package dev.codex.clipbatterylink;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;

final class DeviceOption {
    final BluetoothDevice device;
    final String label;

    @SuppressLint("MissingPermission")
    DeviceOption(BluetoothDevice device) {
        this.device = device;
        String name = device.getName();
        this.label = (name == null || name.trim().isEmpty() ? "Unnamed device" : name)
                + " - " + device.getAddress();
    }

    @Override
    public String toString() {
        return label;
    }
}
