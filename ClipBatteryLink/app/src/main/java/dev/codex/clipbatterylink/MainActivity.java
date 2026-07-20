package dev.codex.clipbatterylink;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH = 42;
    private static final long NETWORK_SAMPLE_INTERVAL_MILLIS = 5000L;
    private static final long BATTERY_SAMPLE_INTERVAL_MILLIS = 60000L;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLink link;
    private ClipboardManager clipboard;
    private ConnectivityManager connectivityManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ArrayAdapter<DeviceOption> deviceAdapter;
    private TextView statusView;
    private TextView localBatteryView;
    private TextView remoteBatteryView;
    private TextView localNetworkView;
    private TextView remoteNetworkView;
    private TextView remoteClipboardView;
    private TextView logView;
    private Spinner deviceSpinner;
    private CheckBox autoClipboardBox;
    private long suppressClipboardUntilMillis;
    private long previousNetworkSampleAt;
    private long previousRxBytes;
    private long previousTxBytes;

    private final Runnable networkSampler = new Runnable() {
        @Override
        public void run() {
            sampleNetworkSpeed();
            if (link.isConnected()) {
                handler.postDelayed(this, NETWORK_SAMPLE_INTERVAL_MILLIS);
            }
        }
    };

    private final Runnable batterySampler = new Runnable() {
        @Override
        public void run() {
            refreshBattery();
            if (link.isConnected()) {
                handler.postDelayed(this, BATTERY_SAMPLE_INTERVAL_MILLIS);
            }
        }
    };

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener = () -> {
        if (!autoClipboardBox.isChecked()) {
            return;
        }
        if (System.currentTimeMillis() < suppressClipboardUntilMillis) {
            return;
        }
        String text = readClipboardText();
        if (text != null && !text.isEmpty()) {
            sendClipboard(text);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        link = new BluetoothLink(bluetoothAdapter, new BluetoothLink.Listener() {
            @Override
            public void onStatus(String status) {
                runOnUiThread(() -> {
                    setStatus(status);
                    if (status.startsWith("Connected:")) {
                        refreshBattery();
                        resetNetworkSample();
                        startNetworkSampling();
                        startBatterySampling();
                    }
                });
            }

            @Override
            public void onMessage(String json) {
                runOnUiThread(() -> handleMessage(json));
            }

            @Override
            public void onDisconnected(String reason) {
                runOnUiThread(() -> {
                    stopNetworkSampling();
                    stopBatterySampling();
                    setStatus(reason);
                });
            }
        });

        setContentView(buildUi());
        requestNeededPermissions();
        refreshBattery();
        refreshPairedDevices();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (clipboard != null) {
            clipboard.addPrimaryClipChangedListener(clipListener);
        }
        refreshBattery();
        if (link.isConnected()) {
            startNetworkSampling();
            startBatterySampling();
        } else if (localNetworkView != null) {
            localNetworkView.setText("Local network: idle until phones connect");
        }
    }

    @Override
    protected void onPause() {
        stopNetworkSampling();
        stopBatterySampling();
        if (clipboard != null) {
            clipboard.removePrimaryClipChangedListener(clipListener);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        link.close();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.addView(root);

        TextView title = text(getString(R.string.app_name), 26, true);
        root.addView(title);
        root.addView(text("Local phone-to-phone clipboard and battery sync", 14, false));

        statusView = text("Starting...", 16, true);
        statusView.setTextColor(Color.rgb(15, 118, 110));
        root.addView(section("Status"));
        root.addView(statusView);

        localBatteryView = text("Local battery: --", 16, false);
        remoteBatteryView = text("Remote battery: --", 16, false);
        root.addView(section("Battery"));
        root.addView(localBatteryView);
        root.addView(remoteBatteryView);

        localNetworkView = text("Local network: --", 16, false);
        remoteNetworkView = text("Remote network: --", 16, false);
        root.addView(section("Network speed"));
        root.addView(localNetworkView);
        root.addView(remoteNetworkView);

        root.addView(section("Pairing"));
        deviceSpinner = new Spinner(this);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        deviceSpinner.setAdapter(deviceAdapter);
        root.addView(deviceSpinner);

        LinearLayout row = row();
        row.addView(button("Refresh", v -> refreshPairedDevices()), weight());
        row.addView(button("Wait", v -> startServer()), weight());
        row.addView(button("Connect", v -> connectSelected()), weight());
        root.addView(row);

        root.addView(section("Clipboard"));
        autoClipboardBox = new CheckBox(this);
        autoClipboardBox.setText("Auto-send clipboard while this screen is open");
        autoClipboardBox.setChecked(true);
        root.addView(autoClipboardBox);

        LinearLayout clipRow = row();
        clipRow.addView(button("Send Now", v -> {
            String text = readClipboardText();
            if (text == null || text.isEmpty()) {
                log("Clipboard is empty or unavailable.");
            } else {
                sendClipboard(text);
            }
        }), weight());
        clipRow.addView(button("Copy Remote", v -> copyRemoteToClipboard()), weight());
        root.addView(clipRow);

        remoteClipboardView = text("Remote clipboard: --", 16, false);
        root.addView(remoteClipboardView);

        root.addView(section("Log"));
        logView = text("", 13, false);
        root.addView(logView);

        return scroll;
    }

    @SuppressLint("MissingPermission")
    private void refreshPairedDevices() {
        deviceAdapter.clear();
        if (bluetoothAdapter == null) {
            setStatus("Bluetooth is not available on this phone.");
            return;
        }
        if (!hasBluetoothPermission()) {
            setStatus("Allow Nearby devices, then refresh.");
            return;
        }
        Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
        List<DeviceOption> options = new ArrayList<>();
        for (BluetoothDevice device : bonded) {
            options.add(new DeviceOption(device));
        }
        deviceAdapter.addAll(options);
        deviceAdapter.notifyDataSetChanged();
        setStatus(options.isEmpty()
                ? "Pair the phones in Android Bluetooth settings first."
                : "Choose the other phone, or wait for it to connect.");
    }

    private void startServer() {
        if (!canUseBluetooth()) {
            return;
        }
        link.startServer();
    }

    private void connectSelected() {
        if (!canUseBluetooth()) {
            return;
        }
        Object selected = deviceSpinner.getSelectedItem();
        if (!(selected instanceof DeviceOption)) {
            log("No paired phone selected.");
            return;
        }
        link.connect(((DeviceOption) selected).device);
    }

    private boolean canUseBluetooth() {
        if (bluetoothAdapter == null) {
            setStatus("Bluetooth is not available.");
            return false;
        }
        if (!hasBluetoothPermission()) {
            requestNeededPermissions();
            setStatus("Bluetooth permission is needed.");
            return false;
        }
        if (!bluetoothAdapter.isEnabled()) {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return false;
        }
        return true;
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), REQUEST_BLUETOOTH);
        }
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshBattery() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) {
            return;
        }
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int percent = scale <= 0 ? -1 : Math.round(level * 100f / scale);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        localBatteryView.setText("Local battery: " + percent + "% " + (charging ? "charging" : "not charging"));
        if (link.isConnected()) {
            sendBattery(percent, charging);
        }
    }

    private void sendBattery(int percent, boolean charging) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "battery");
            json.put("percent", percent);
            json.put("charging", charging);
            link.send(json.toString());
        } catch (JSONException ignored) {
        }
    }

    private void sampleNetworkSpeed() {
        if (!link.isConnected()) {
            localNetworkView.setText("Local network: connected phone link idle");
            resetNetworkSample();
            return;
        }

        NetworkSnapshot snapshot = readNetworkSnapshot();
        if (snapshot.rxBytes < 0L || snapshot.txBytes < 0L) {
            localNetworkView.setText("Local network: " + snapshot.transport + " unsupported");
            return;
        }
        long now = System.currentTimeMillis();
        if (previousNetworkSampleAt == 0L || previousRxBytes < 0 || previousTxBytes < 0) {
            previousNetworkSampleAt = now;
            previousRxBytes = snapshot.rxBytes;
            previousTxBytes = snapshot.txBytes;
            localNetworkView.setText("Local network: " + snapshot.transport + " measuring...");
            return;
        }

        long elapsedMillis = Math.max(1L, now - previousNetworkSampleAt);
        long rxDelta = Math.max(0L, snapshot.rxBytes - previousRxBytes);
        long txDelta = Math.max(0L, snapshot.txBytes - previousTxBytes);
        double rxBytesPerSecond = rxDelta * 1000d / elapsedMillis;
        double txBytesPerSecond = txDelta * 1000d / elapsedMillis;

        previousNetworkSampleAt = now;
        previousRxBytes = snapshot.rxBytes;
        previousTxBytes = snapshot.txBytes;

        localNetworkView.setText("Local network: " + snapshot.transport
                + " down " + formatSpeed(rxBytesPerSecond)
                + " up " + formatSpeed(txBytesPerSecond));
        sendNetwork(snapshot.transport, rxBytesPerSecond, txBytesPerSecond);
    }

    private NetworkSnapshot readNetworkSnapshot() {
        String transport = currentTransportLabel();
        long totalRx = TrafficStats.getTotalRxBytes();
        long totalTx = TrafficStats.getTotalTxBytes();
        long mobileRx = TrafficStats.getMobileRxBytes();
        long mobileTx = TrafficStats.getMobileTxBytes();

        if ("Mobile".equals(transport) && mobileRx >= 0L && mobileTx >= 0L) {
            return new NetworkSnapshot(transport, mobileRx, mobileTx);
        }
        return new NetworkSnapshot(transport, totalRx, totalTx);
    }

    private String currentTransportLabel() {
        if (connectivityManager == null) {
            return "Unknown";
        }
        Network active = connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(active);
        if (capabilities == null) {
            return "Offline";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "Wi-Fi";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "Mobile";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return "VPN";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "Ethernet";
        }
        return "Other";
    }

    private void sendNetwork(String transport, double rxBytesPerSecond, double txBytesPerSecond) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "network");
            json.put("transport", transport);
            json.put("rxBps", rxBytesPerSecond);
            json.put("txBps", txBytesPerSecond);
            link.send(json.toString());
        } catch (JSONException ignored) {
        }
    }

    private void startNetworkSampling() {
        handler.removeCallbacks(networkSampler);
        if (link.isConnected()) {
            handler.post(networkSampler);
        }
    }

    private void stopNetworkSampling() {
        handler.removeCallbacks(networkSampler);
        resetNetworkSample();
    }

    private void startBatterySampling() {
        handler.removeCallbacks(batterySampler);
        if (link.isConnected()) {
            handler.postDelayed(batterySampler, BATTERY_SAMPLE_INTERVAL_MILLIS);
        }
    }

    private void stopBatterySampling() {
        handler.removeCallbacks(batterySampler);
    }

    private void resetNetworkSample() {
        previousNetworkSampleAt = 0L;
        previousRxBytes = -1L;
        previousTxBytes = -1L;
    }

    private String formatSpeed(double bytesPerSecond) {
        double bitsPerSecond = bytesPerSecond * 8d;
        if (bitsPerSecond >= 1_000_000d) {
            return String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000d);
        }
        if (bitsPerSecond >= 1_000d) {
            return String.format(Locale.US, "%.0f Kbps", bitsPerSecond / 1_000d);
        }
        return String.format(Locale.US, "%.0f bps", bitsPerSecond);
    }

    private void sendClipboard(String text) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "clipboard");
            json.put("text", text);
            if (link.send(json.toString())) {
                log("Clipboard sent.");
            } else {
                log("Clipboard was not sent because the phones are not connected.");
            }
        } catch (JSONException error) {
            log("Clipboard send failed: " + error.getMessage());
        }
    }

    private void handleMessage(String raw) {
        try {
            JSONObject json = new JSONObject(raw);
            String type = json.optString("type");
            if ("battery".equals(type)) {
                int percent = json.optInt("percent", -1);
                boolean charging = json.optBoolean("charging", false);
                remoteBatteryView.setText("Remote battery: " + percent + "% "
                        + (charging ? "charging" : "not charging"));
                log("Battery updated from remote.");
            } else if ("network".equals(type)) {
                String transport = json.optString("transport", "Unknown");
                double rxBps = json.optDouble("rxBps", 0d);
                double txBps = json.optDouble("txBps", 0d);
                remoteNetworkView.setText("Remote network: " + transport
                        + " down " + formatSpeed(rxBps)
                        + " up " + formatSpeed(txBps));
            } else if ("clipboard".equals(type)) {
                String text = json.optString("text", "");
                remoteClipboardView.setText("Remote clipboard: " + text);
                suppressClipboardUntilMillis = System.currentTimeMillis() + 1500;
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Remote clipboard", text));
                }
                log("Remote clipboard received and copied locally.");
            }
        } catch (JSONException error) {
            log("Bad message: " + raw);
        }
    }

    private String readClipboardText() {
        if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null) {
            return null;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip.getItemCount() == 0) {
            return null;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        return text == null ? null : text.toString();
    }

    private void copyRemoteToClipboard() {
        String prefix = "Remote clipboard: ";
        String label = remoteClipboardView.getText().toString();
        if (!label.startsWith(prefix) || label.length() == prefix.length() || clipboard == null) {
            log("No remote clipboard text yet.");
            return;
        }
        suppressClipboardUntilMillis = System.currentTimeMillis() + 1500;
        clipboard.setPrimaryClip(ClipData.newPlainText("Remote clipboard", label.substring(prefix.length())));
        log("Remote text copied again.");
    }

    private TextView section(String label) {
        TextView view = text(label, 18, true);
        view.setPadding(0, dp(24), 0, dp(8));
        return view;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(24, 24, 27));
        view.setPadding(0, dp(4), 0, dp(4));
        if (bold) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        return row;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.weight = 1;
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String status) {
        statusView.setText(status);
        log(status);
    }

    private void log(String value) {
        String old = logView == null ? "" : logView.getText().toString();
        logView.setText(value + "\n" + old);
    }

    private static final class NetworkSnapshot {
        final String transport;
        final long rxBytes;
        final long txBytes;

        NetworkSnapshot(String transport, long rxBytes, long txBytes) {
            this.transport = transport;
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
        }
    }
}
