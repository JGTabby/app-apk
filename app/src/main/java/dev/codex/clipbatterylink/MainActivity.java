package dev.codex.clipbatterylink;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.animation.ObjectAnimator;
import android.animation.AnimatorSet;
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
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH = 42;
    private static final int REQUEST_ENABLE_BLUETOOTH = 43;
    private static final int REQUEST_LOCATION = 44;
    private static final long NETWORK_SAMPLE_INTERVAL_MILLIS = 5000L;
    private static final long BATTERY_SAMPLE_INTERVAL_MILLIS = 60000L;

    private BluetoothAdapter bluetoothAdapter;
    private LinkTransport link;
    private LinkListener linkListener;
    private NetworkRelayLink networkRelayLink;
    private ClipboardManager clipboard;
    private ConnectivityManager connectivityManager;
    private BatteryManager batteryManager;
    private ActivityManager activityManager;
    private LocationManager locationManager;
    private PowerManager powerManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ArrayAdapter<DeviceOption> deviceAdapter;
    private TextView statusView;
    private TextView connectionHintView;
    private TextView localLocationView;
    private TextView remoteLocationView;
    private TextView distanceView;
    private TextView localBatteryView;
    private TextView remoteBatteryView;
    private TextView localChargeView;
    private TextView remoteChargeView;
    private TextView localDeviceView;
    private TextView remoteDeviceView;
    private TextView localNetworkView;
    private TextView remoteNetworkView;
    private TextView remoteClipboardView;
    private TextView logView;
    private TextView interactionView;
    private Spinner deviceSpinner;
    private CheckBox autoClipboardBox;
    private CheckBox distanceReminderBox;
    private EditText distanceThresholdInput;
    private Switch networkTestSwitch;
    private EditText relayHostInput;
    private EditText relayPortInput;
    private EditText relayRoomInput;
    private long suppressClipboardUntilMillis;
    private long previousNetworkSampleAt;
    private long previousRxBytes;
    private long previousTxBytes;
    private AnimatorSet statusAnimator;
    private Location localLocation;
    private Location remoteLocation;
    private boolean wasOutsideReminderDistance;
    private int transportGeneration;

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
        batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        linkListener = createLinkListener();
        link = new BluetoothLink(bluetoothAdapter, linkListener);

        setContentView(buildUi());
        requestNeededPermissions();
        refreshBattery();
        refreshPairedDevices();
    }

    private LinkListener createLinkListener() {
        final int generation = ++transportGeneration;
        return new LinkListener() {
            @Override
            public void onStatus(String status) {
                runOnUiThread(() -> {
                    if (generation != transportGeneration) {
                        return;
                    }
                    setStatus(status);
                    if (status.startsWith("已連線：")) {
                        refreshBattery();
                        resetNetworkSample();
                        startNetworkSampling();
                        startBatterySampling();
                    }
                });
            }

            @Override
            public void onMessage(String json) {
                runOnUiThread(() -> {
                    if (generation == transportGeneration) {
                        handleMessage(json);
                    }
                });
            }

            @Override
            public void onDisconnected(String reason) {
                runOnUiThread(() -> {
                    if (generation != transportGeneration) {
                        return;
                    }
                    stopNetworkSampling();
                    stopBatterySampling();
                    setStatus(reason);
                });
            }
        };
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
            localNetworkView.setText("本機網路：兩台手機連線後開始監測");
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
        stopStatusAnimation();
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
        root.addView(text("在兩支手機間同步剪貼簿、電量與網路速度", 14, false));

        statusView = text("正在準備藍牙功能…", 16, true);
        statusView.setTextColor(Color.rgb(15, 118, 110));
        root.addView(section("連線狀態"));
        root.addView(statusView);

        connectionHintView = text("1. 先到兩支手機的系統藍牙完成配對\n2. 手機 A 按「開始等待」\n3. 手機 B 選擇手機 A，按「連接選取手機」", 14, false);
        connectionHintView.setPadding(0, dp(8), 0, dp(4));
        root.addView(connectionHintView);

        root.addView(section("喵喵雷達"));
        root.addView(text("位置只會在你按下更新時讀取並傳給另一支手機。", 14, false));
        localLocationView = text("本機位置：尚未更新", 16, false);
        remoteLocationView = text("另一支手機位置：尚未收到", 16, false);
        distanceView = text("兩機距離：--", 16, true);
        root.addView(localLocationView);
        root.addView(remoteLocationView);
        root.addView(distanceView);
        LinearLayout radarRow = row();
        radarRow.addView(button("更新本機位置", v -> updateLocation()), weight());
        root.addView(radarRow);

        distanceReminderBox = new CheckBox(this);
        distanceReminderBox.setText("超過指定距離時提醒我");
        distanceReminderBox.setChecked(true);
        root.addView(distanceReminderBox);
        LinearLayout distanceRow = row();
        distanceThresholdInput = new EditText(this);
        distanceThresholdInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        distanceThresholdInput.setText("100");
        distanceThresholdInput.setSelectAllOnFocus(false);
        distanceRow.addView(distanceThresholdInput, weight());
        distanceRow.addView(button("公尺提醒距離", v -> updateDistance()), weight());
        root.addView(distanceRow);

        localBatteryView = text("本機電量：--", 16, false);
        remoteBatteryView = text("另一支手機電量：--", 16, false);
        root.addView(section("電量"));
        root.addView(localBatteryView);
        root.addView(remoteBatteryView);

        root.addView(section("充電資訊"));
        localChargeView = text("本機充電：--", 16, false);
        remoteChargeView = text("另一支手機充電：--", 16, false);
        root.addView(localChargeView);
        root.addView(remoteChargeView);

        root.addView(section("手機狀態"));
        localDeviceView = text("本機狀態：--", 16, false);
        remoteDeviceView = text("另一支手機狀態：--", 16, false);
        root.addView(localDeviceView);
        root.addView(remoteDeviceView);

        localNetworkView = text("本機網路：--", 16, false);
        remoteNetworkView = text("另一支手機網路：--", 16, false);
        root.addView(section("網路速度"));
        root.addView(localNetworkView);
        root.addView(remoteNetworkView);

        root.addView(section("選擇已配對的手機"));
        deviceSpinner = new Spinner(this);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        deviceSpinner.setAdapter(deviceAdapter);
        root.addView(deviceSpinner);

        LinearLayout row = row();
        row.addView(button("重新整理", v -> refreshPairedDevices()), weight());
        row.addView(button("開始等待", v -> startServer()), weight());
        row.addView(button("連接選取手機", v -> connectSelected()), weight());
        root.addView(row);

        root.addView(section("測試功能：網路中繼連線"));
        root.addView(text("兩支手機可透過 Wi-Fi 或行動數據連到同一台公開中繼。\n此模式仍在測試中，會較耗電，且需要你自行架設中繼。", 14, false));
        networkTestSwitch = new Switch(this);
        networkTestSwitch.setText("啟用網路中繼測試模式");
        networkTestSwitch.setChecked(false);
        networkTestSwitch.setOnCheckedChangeListener((button, enabled) -> switchTransport(enabled));
        root.addView(networkTestSwitch);
        relayHostInput = new EditText(this);
        relayHostInput.setHint("中繼主機，例如 relay.example.com");
        root.addView(relayHostInput);
        relayPortInput = new EditText(this);
        relayPortInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        relayPortInput.setHint("中繼連接埠，例如 7000");
        root.addView(relayPortInput);
        relayRoomInput = new EditText(this);
        relayRoomInput.setHint("兩支手機使用相同的配對代碼");
        root.addView(relayRoomInput);
        root.addView(button("連接網路中繼", v -> connectNetworkRelay()));

        root.addView(section("剪貼簿同步"));
        autoClipboardBox = new CheckBox(this);
        autoClipboardBox.setText("此畫面開啟時，自動傳送剪貼簿文字");
        autoClipboardBox.setChecked(true);
        root.addView(autoClipboardBox);

        LinearLayout clipRow = row();
        clipRow.addView(button("立即傳送", v -> {
            String text = readClipboardText();
            if (text == null || text.isEmpty()) {
                log("剪貼簿沒有可傳送的文字。");
            } else {
                sendClipboard(text);
            }
        }), weight());
        clipRow.addView(button("複製對方內容", v -> copyRemoteToClipboard()), weight());
        root.addView(clipRow);

        remoteClipboardView = text("另一支手機剪貼簿：--", 16, false);
        root.addView(remoteClipboardView);

        root.addView(section("貓咪互動"));
        interactionView = text("尚未收到另一支手機的貓掌訊號", 16, false);
        root.addView(interactionView);
        LinearLayout interactionRow = row();
        interactionRow.addView(button("傳送貓掌訊號", v -> sendPawSignal()), weight());
        root.addView(interactionRow);

        root.addView(section("連線紀錄"));
        logView = text("", 13, false);
        root.addView(logView);

        return scroll;
    }

    @SuppressLint("MissingPermission")
    private void refreshPairedDevices() {
        deviceAdapter.clear();
        if (bluetoothAdapter == null) {
            setStatus("這支手機不支援藍牙功能。");
            return;
        }
        if (!hasBluetoothPermission()) {
            setStatus("請允許「附近裝置」權限，然後按重新整理。");
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
                ? "請先到系統設定 > 藍牙，完成兩支手機的配對。"
                : "選擇另一支手機後按「連接選取手機」，或在本機按「開始等待」。");
    }

    private void startServer() {
        if (isNetworkTestEnabled()) {
            setStatus("網路測試模式不需要「開始等待」，請填寫中繼資料後按「連接網路中繼」。");
            return;
        }
        if (!canUseBluetooth()) {
            return;
        }
        if (link instanceof BluetoothLink) {
            ((BluetoothLink) link).startServer();
        }
    }

    private void connectSelected() {
        if (isNetworkTestEnabled()) {
            setStatus("網路測試模式請使用「連接網路中繼」。");
            return;
        }
        if (!canUseBluetooth()) {
            return;
        }
        Object selected = deviceSpinner.getSelectedItem();
        if (!(selected instanceof DeviceOption)) {
            log("尚未選擇已配對的手機。");
            return;
        }
        if (link instanceof BluetoothLink) {
            ((BluetoothLink) link).connect(((DeviceOption) selected).device);
        }
    }

    private boolean isNetworkTestEnabled() {
        return networkTestSwitch != null && networkTestSwitch.isChecked();
    }

    private void switchTransport(boolean useNetworkRelay) {
        if (link == null || linkListener == null) {
            return;
        }
        link.close();
        stopNetworkSampling();
        stopBatterySampling();
        linkListener = createLinkListener();
        if (useNetworkRelay) {
            networkRelayLink = new NetworkRelayLink(linkListener);
            link = networkRelayLink;
            setStatus("已啟用網路中繼測試模式，填寫中繼資料後連線。");
        } else {
            networkRelayLink = null;
            link = new BluetoothLink(bluetoothAdapter, linkListener);
            setStatus("已切回藍牙模式。");
            refreshPairedDevices();
        }
    }

    private void connectNetworkRelay() {
        if (!isNetworkTestEnabled()) {
            setStatus("請先開啟「啟用網路中繼測試模式」。");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(relayPortInput.getText().toString());
        } catch (NumberFormatException error) {
            setStatus("請填入有效的中繼連接埠。");
            return;
        }
        if (port < 1 || port > 65535) {
            setStatus("中繼連接埠必須介於 1 到 65535。" );
            return;
        }
        if (networkRelayLink == null) {
            networkRelayLink = new NetworkRelayLink(linkListener);
            link = networkRelayLink;
        }
        networkRelayLink.connect(
                relayHostInput.getText().toString(),
                port,
                relayRoomInput.getText().toString()
        );
    }

    private boolean canUseBluetooth() {
        if (bluetoothAdapter == null) {
            setStatus("這支手機無法使用藍牙。");
            return false;
        }
        if (!hasBluetoothPermission()) {
            requestNeededPermissions();
            setStatus("需要「附近裝置」權限才能連線。");
            return false;
        }
        if (!bluetoothAdapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
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
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), REQUEST_BLUETOOTH);
        }
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION) {
            if (hasLocationPermission()) {
                log("已取得位置權限，請再按一次「更新本機位置」。");
            } else {
                setStatus("未取得位置權限，喵喵雷達無法更新位置。");
            }
            return;
        }
        if (requestCode != REQUEST_BLUETOOTH) {
            return;
        }
        if (hasBluetoothPermission()) {
            setStatus("已取得「附近裝置」權限，請選擇另一支手機後連線。");
            refreshPairedDevices();
        } else {
            setStatus("未取得「附近裝置」權限，無法使用藍牙連線。");
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void updateLocation() {
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQUEST_LOCATION);
            return;
        }
        if (locationManager == null) {
            log("這支手機無法取得位置服務。");
            return;
        }
        String provider = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
        try {
            Location recent = locationManager.getLastKnownLocation(provider);
            if (recent != null) {
                receiveLocalLocation(recent);
            }
            locationManager.requestSingleUpdate(provider, new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    receiveLocalLocation(location);
                }

                @Override
                public void onStatusChanged(String changedProvider, int status, Bundle extras) {
                    // No UI change is needed for this one-shot location request.
                }

                @Override
                public void onProviderEnabled(String enabledProvider) {
                    // The next manual update will request a fresh location.
                }

                @Override
                public void onProviderDisabled(String disabledProvider) {
                    log("定位服務已關閉。" );
                }
            }, Looper.getMainLooper());
            localLocationView.setText("本機位置：正在更新…");
        } catch (SecurityException error) {
            setStatus("位置權限不足，無法更新喵喵雷達。");
        } catch (IllegalArgumentException error) {
            setStatus("請開啟定位服務後，再更新本機位置。");
        }
    }

    private void receiveLocalLocation(Location location) {
        localLocation = location;
        localLocationView.setText("本機位置：" + formatLocation(location)
                + "，誤差約 " + Math.round(location.getAccuracy()) + " 公尺");
        sendLocation(location);
        updateDistance();
    }

    private void sendLocation(Location location) {
        if (!link.isConnected()) {
            log("位置已更新；兩支手機連線後再傳送。" );
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("type", "location");
            json.put("latitude", location.getLatitude());
            json.put("longitude", location.getLongitude());
            json.put("accuracy", location.getAccuracy());
            if (link.send(json.toString())) {
                log("已傳送本機位置。" );
            }
        } catch (JSONException error) {
            log("位置傳送失敗。" );
        }
    }

    private void updateDistance() {
        if (localLocation == null || remoteLocation == null) {
            return;
        }
        float meters = localLocation.distanceTo(remoteLocation);
        distanceView.setText("兩機距離：約 " + formatDistance(meters)
                + "，另一支手機在" + directionLabel(localLocation.bearingTo(remoteLocation)) + "方");
        int threshold = readReminderDistance();
        boolean outside = distanceReminderBox != null && distanceReminderBox.isChecked() && meters > threshold;
        if (outside && !wasOutsideReminderDistance) {
            String reminder = "距離已超過 " + threshold + " 公尺";
            log(reminder);
            Toast.makeText(this, reminder, Toast.LENGTH_LONG).show();
        }
        wasOutsideReminderDistance = outside;
    }

    private int readReminderDistance() {
        try {
            return Math.max(1, Integer.parseInt(distanceThresholdInput.getText().toString()));
        } catch (NumberFormatException ignored) {
            return 100;
        }
    }

    private String formatDistance(float meters) {
        return meters >= 1000f ? String.format(Locale.US, "%.1f 公里", meters / 1000f)
                : Math.round(meters) + " 公尺";
    }

    private String formatLocation(Location location) {
        return String.format(Locale.US, "北緯 %.5f，東經 %.5f", location.getLatitude(), location.getLongitude());
    }

    private String directionLabel(float bearing) {
        String[] directions = {"北", "東北", "東", "東南", "南", "西南", "西", "西北"};
        float normalizedBearing = (bearing + 360f) % 360f;
        int index = Math.round(normalizedBearing / 45f) % directions.length;
        return directions[index];
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ENABLE_BLUETOOTH) {
            return;
        }
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            setStatus("藍牙已開啟，現在可選擇另一支手機連線。");
            refreshPairedDevices();
        } else {
            setStatus("藍牙尚未開啟，無法連線。");
        }
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
        localBatteryView.setText("本機電量：" + percent + "% " + (charging ? "充電中" : "未充電"));
        String chargeInfo = buildChargeInfo(battery, percent, charging);
        localChargeView.setText("本機充電：" + chargeInfo);
        updateLocalDeviceStatus(battery);
        if (link.isConnected()) {
            sendBattery(percent, charging);
            sendDeviceStatus(battery, chargeInfo);
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

    private String buildChargeInfo(Intent battery, int percent, boolean charging) {
        int voltageMv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        long currentUa = batteryManager == null ? Integer.MIN_VALUE
                : batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        long absoluteCurrentUa = currentUa == Integer.MIN_VALUE ? -1L : Math.abs(currentUa);
        if (!charging) {
            return "未充電";
        }
        String power = "功率無法取得";
        if (absoluteCurrentUa > 0L && voltageMv > 0) {
            double watts = absoluteCurrentUa * voltageMv / 1_000_000_000d;
            String speed = watts >= 15d ? "快充" : watts >= 7.5d ? "一般充電" : "慢速充電";
            power = speed + "，約 " + String.format(Locale.US, "%.1f", watts) + " W";
        }
        return power + "，" + estimateChargeTime(percent, absoluteCurrentUa);
    }

    private String estimateChargeTime(int percent, long currentUa) {
        if (batteryManager == null || currentUa <= 0L || percent <= 0 || percent >= 100) {
            return percent >= 100 ? "已接近充滿" : "剩餘時間無法估算";
        }
        long chargeCounterUa = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        if (chargeCounterUa <= 0L) {
            return "剩餘時間無法估算";
        }
        double remainingUa = chargeCounterUa * (100d - percent) / percent;
        int minutes = (int) Math.round(remainingUa * 60d / currentUa);
        if (minutes <= 0 || minutes > 24 * 60) {
            return "剩餘時間無法估算";
        }
        return "預估約 " + (minutes / 60) + " 小時 " + (minutes % 60) + " 分充滿";
    }

    private void updateLocalDeviceStatus(Intent battery) {
        StatFs storage = new StatFs(getFilesDir().getAbsolutePath());
        long availableStorage = storage.getAvailableBytes();
        long totalStorage = storage.getTotalBytes();
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (activityManager != null) {
            activityManager.getMemoryInfo(memory);
        }
        int temperatureTenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        String temperature = temperatureTenths < 0 ? "電池溫度無法取得"
                : "電池 " + String.format(Locale.US, "%.1f", temperatureTenths / 10d) + "°C";
        localDeviceView.setText("本機狀態：儲存 " + formatBytes(availableStorage) + "/" + formatBytes(totalStorage)
                + " 可用；記憶體 " + formatBytes(memory.availMem) + "/" + formatBytes(memory.totalMem)
                + " 可用；CPU " + Runtime.getRuntime().availableProcessors() + " 核心；"
                + temperature + "；" + thermalStatusLabel());
    }

    private void sendDeviceStatus(Intent battery, String chargeInfo) {
        try {
            StatFs storage = new StatFs(getFilesDir().getAbsolutePath());
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            if (activityManager != null) {
                activityManager.getMemoryInfo(memory);
            }
            JSONObject json = new JSONObject();
            json.put("type", "device");
            json.put("storageFree", storage.getAvailableBytes());
            json.put("storageTotal", storage.getTotalBytes());
            json.put("ramFree", memory.availMem);
            json.put("ramTotal", memory.totalMem);
            json.put("cpuCores", Runtime.getRuntime().availableProcessors());
            json.put("batteryTemperature", battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1));
            json.put("thermal", thermalStatusLabel());
            json.put("chargeInfo", chargeInfo);
            link.send(json.toString());
        } catch (JSONException ignored) {
            // Built from primitive values only.
        }
    }

    private String thermalStatusLabel() {
        if (powerManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "系統散熱狀態無法取得";
        }
        switch (powerManager.getCurrentThermalStatus()) {
            case PowerManager.THERMAL_STATUS_NONE:
                return "系統溫度正常";
            case PowerManager.THERMAL_STATUS_LIGHT:
                return "系統稍熱";
            case PowerManager.THERMAL_STATUS_MODERATE:
                return "系統偏熱";
            case PowerManager.THERMAL_STATUS_SEVERE:
                return "系統過熱，可能降速";
            case PowerManager.THERMAL_STATUS_CRITICAL:
                return "系統嚴重過熱";
            case PowerManager.THERMAL_STATUS_EMERGENCY:
                return "系統緊急過熱";
            case PowerManager.THERMAL_STATUS_SHUTDOWN:
                return "系統即將因過熱關閉";
            default:
                return "系統散熱狀態未知";
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1_000_000_000L) {
            return String.format(Locale.US, "%.1f MB", bytes / 1_000_000d);
        }
        return String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000d);
    }

    private void sampleNetworkSpeed() {
        if (!link.isConnected()) {
            localNetworkView.setText("本機網路：藍牙尚未連線");
            resetNetworkSample();
            return;
        }

        NetworkSnapshot snapshot = readNetworkSnapshot();
        if (snapshot.rxBytes < 0L || snapshot.txBytes < 0L) {
            localNetworkView.setText("本機網路：" + snapshot.transport + " 無法取得流量資料");
            return;
        }
        long now = System.currentTimeMillis();
        if (previousNetworkSampleAt == 0L || previousRxBytes < 0 || previousTxBytes < 0) {
            previousNetworkSampleAt = now;
            previousRxBytes = snapshot.rxBytes;
            previousTxBytes = snapshot.txBytes;
            localNetworkView.setText("本機網路：" + snapshot.transport + " 正在測量…");
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

        localNetworkView.setText("本機網路：" + snapshot.transport
                + " 下載 " + formatSpeed(rxBytesPerSecond)
                + " 上傳 " + formatSpeed(txBytesPerSecond));
        sendNetwork(snapshot.transport, rxBytesPerSecond, txBytesPerSecond);
    }

    private NetworkSnapshot readNetworkSnapshot() {
        String transport = currentTransportLabel();
        long totalRx = TrafficStats.getTotalRxBytes();
        long totalTx = TrafficStats.getTotalTxBytes();
        long mobileRx = TrafficStats.getMobileRxBytes();
        long mobileTx = TrafficStats.getMobileTxBytes();

        if ("行動網路".equals(transport) && mobileRx >= 0L && mobileTx >= 0L) {
            return new NetworkSnapshot(transport, mobileRx, mobileTx);
        }
        return new NetworkSnapshot(transport, totalRx, totalTx);
    }

    private String currentTransportLabel() {
        if (connectivityManager == null) {
            return "未知";
        }
        Network active = connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(active);
        if (capabilities == null) {
            return "離線";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "Wi-Fi";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "行動網路";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return "VPN";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "乙太網路";
        }
        return "其他網路";
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
        if (text.length() > 12_000) {
            log("剪貼簿文字過長，請縮短至 12,000 個字元內再傳送。");
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("type", "clipboard");
            json.put("text", text);
            if (link.send(json.toString())) {
                log("剪貼簿已傳送。");
            } else {
                log("剪貼簿未傳送：兩支手機尚未連線。");
            }
        } catch (JSONException error) {
            log("剪貼簿傳送失敗：" + error.getMessage());
        }
    }

    private void handleMessage(String raw) {
        try {
            JSONObject json = new JSONObject(raw);
            String type = json.optString("type");
            if ("battery".equals(type)) {
                int percent = json.optInt("percent", -1);
                boolean charging = json.optBoolean("charging", false);
                remoteBatteryView.setText("另一支手機電量：" + percent + "% "
                        + (charging ? "充電中" : "未充電"));
                log("已更新另一支手機的電量。");
            } else if ("device".equals(type)) {
                long storageFree = json.optLong("storageFree", 0L);
                long storageTotal = json.optLong("storageTotal", 0L);
                long ramFree = json.optLong("ramFree", 0L);
                long ramTotal = json.optLong("ramTotal", 0L);
                int cpuCores = json.optInt("cpuCores", 0);
                int batteryTemperature = json.optInt("batteryTemperature", -1);
                String temperature = batteryTemperature < 0 ? "電池溫度無法取得"
                        : "電池 " + String.format(Locale.US, "%.1f", batteryTemperature / 10d) + "°C";
                remoteChargeView.setText("另一支手機充電：" + json.optString("chargeInfo", "無法取得"));
                remoteDeviceView.setText("另一支手機狀態：儲存 " + formatBytes(storageFree) + "/" + formatBytes(storageTotal)
                        + " 可用；記憶體 " + formatBytes(ramFree) + "/" + formatBytes(ramTotal)
                        + " 可用；CPU " + cpuCores + " 核心；" + temperature + "；"
                        + json.optString("thermal", "系統散熱狀態未知"));
            } else if ("network".equals(type)) {
                String transport = json.optString("transport", "未知");
                double rxBps = json.optDouble("rxBps", 0d);
                double txBps = json.optDouble("txBps", 0d);
                remoteNetworkView.setText("另一支手機網路：" + transport
                        + " 下載 " + formatSpeed(rxBps)
                        + " 上傳 " + formatSpeed(txBps));
            } else if ("location".equals(type)) {
                Location location = new Location("另一支手機");
                location.setLatitude(json.getDouble("latitude"));
                location.setLongitude(json.getDouble("longitude"));
                location.setAccuracy((float) json.optDouble("accuracy", 0d));
                remoteLocation = location;
                remoteLocationView.setText("另一支手機位置：" + formatLocation(location)
                        + "，誤差約 " + Math.round(location.getAccuracy()) + " 公尺");
                updateDistance();
                log("已收到另一支手機的位置。" );
            } else if ("clipboard".equals(type)) {
                String text = json.optString("text", "");
                remoteClipboardView.setText("另一支手機剪貼簿：" + text);
                suppressClipboardUntilMillis = System.currentTimeMillis() + 1500;
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("另一支手機剪貼簿", text));
                }
                log("已收到另一支手機的剪貼簿，並複製到本機。");
            } else if ("paw".equals(type)) {
                interactionView.setText("另一支手機剛剛送來一個貓掌訊號！");
                animateInteraction();
                Toast.makeText(this, "收到貓掌訊號", Toast.LENGTH_SHORT).show();
                log("收到另一支手機的貓掌訊號。" );
            }
        } catch (JSONException error) {
            log("收到無法辨識的資料。");
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
        String prefix = "另一支手機剪貼簿：";
        String label = remoteClipboardView.getText().toString();
        if (!label.startsWith(prefix) || label.length() == prefix.length() || clipboard == null) {
            log("目前沒有另一支手機的剪貼簿內容。");
            return;
        }
        suppressClipboardUntilMillis = System.currentTimeMillis() + 1500;
        clipboard.setPrimaryClip(ClipData.newPlainText("另一支手機剪貼簿", label.substring(prefix.length())));
        log("已再次複製另一支手機的文字。");
    }

    private void sendPawSignal() {
        if (!link.isConnected()) {
            log("尚未連線，無法傳送貓掌訊號。" );
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("type", "paw");
            if (link.send(json.toString())) {
                interactionView.setText("已送出貓掌訊號，等另一支手機回應。" );
                animateInteraction();
                log("已送出貓掌訊號。" );
            }
        } catch (JSONException ignored) {
            // Static message cannot fail JSON construction.
        }
    }

    private void animateInteraction() {
        if (interactionView == null) {
            return;
        }
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(interactionView, View.SCALE_X, 1f, 1.08f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(interactionView, View.SCALE_Y, 1f, 1.08f, 1f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(interactionView, View.ALPHA, 0.5f, 1f);
        PathInterpolator curve = new PathInterpolator(0.18f, 0.8f, 0.25f, 1f);
        AnimatorSet animation = new AnimatorSet();
        animation.playTogether(scaleX, scaleY, alpha);
        animation.setDuration(520L);
        animation.setInterpolator(curve);
        animation.start();
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
        boolean isWaiting = status.startsWith("正在等待") || status.startsWith("正在連線");
        if (isWaiting) {
            connectionHintView.setText("連線中：請確認另一支手機已開啟喵喵連接，並依照對應步驟操作。");
            startStatusAnimation();
        } else if (status.startsWith("已連線：")) {
            connectionHintView.setText("連線成功。剪貼簿、電量與網路速度會在此畫面開啟時同步。\n網路速度每 5 秒更新一次，不會進行耗電的網速測試。");
            stopStatusAnimation();
        } else {
            connectionHintView.setText("1. 先到兩支手機的系統藍牙完成配對\n2. 手機 A 按「開始等待」\n3. 手機 B 選擇手機 A，按「連接選取手機」");
            stopStatusAnimation();
        }
        log(status);
    }

    private void startStatusAnimation() {
        if (statusAnimator != null || statusView == null) {
            return;
        }
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(statusView, View.SCALE_X, 1f, 1.035f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(statusView, View.SCALE_Y, 1f, 1.035f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(statusView, View.ALPHA, 0.72f, 1f);
        PathInterpolator curve = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
        for (ObjectAnimator animator : new ObjectAnimator[]{scaleX, scaleY, alpha}) {
            animator.setDuration(900L);
            animator.setInterpolator(curve);
            animator.setRepeatCount(ObjectAnimator.INFINITE);
            animator.setRepeatMode(ObjectAnimator.REVERSE);
        }
        statusAnimator = new AnimatorSet();
        statusAnimator.playTogether(scaleX, scaleY, alpha);
        statusAnimator.start();
    }

    private void stopStatusAnimation() {
        if (statusAnimator != null) {
            statusAnimator.cancel();
            statusAnimator = null;
        }
        if (statusView != null) {
            statusView.setScaleX(1f);
            statusView.setScaleY(1f);
            statusView.setAlpha(1f);
        }
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
