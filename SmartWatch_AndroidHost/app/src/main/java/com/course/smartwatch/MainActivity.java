package com.course.smartwatch;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity implements BluetoothSppClient.Listener {
    private static final int REQUEST_BLUETOOTH_CONNECT = 1001;
    private static final String[] PAGE_NAMES = {
            "Watch Face",
            "MPU",
            "Steps",
            "Bluetooth",
            "Device Info"
    };

    private final List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
    private final List<String> deviceLabels = new ArrayList<String>();

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSppClient client;
    private ArrayAdapter<String> deviceAdapter;
    private Spinner deviceSpinner;
    private Button connectButton;
    private Button disconnectButton;
    private Button syncTimeButton;
    private Button requestStatusButton;
    private Button prevPageButton;
    private Button nextPageButton;
    private Button dialButton;
    private Button resetStepsButton;
    private TextView connectionText;
    private TextView watchTimeText;
    private TextView pageText;
    private TextView stepsText;
    private TextView frameCountText;
    private TextView imuText;
    private TextView accelText;
    private TextView gyroText;
    private TextView eventText;

    private int currentPage;
    private boolean connected;
    private boolean connecting;
    private String connectionState = "Disconnected";
    private boolean hasWatchConnectionFlag;
    private boolean watchConnectionFlag;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        buildUi();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            setDeviceMessage("Bluetooth unavailable");
            setConnectionState("Bluetooth unavailable", false);
            setEvent("This device has no Bluetooth adapter");
            return;
        }

        client = new BluetoothSppClient(bluetoothAdapter, this);
        setConnectionState("Disconnected", false);
        if (hasBluetoothConnectPermission()) {
            loadBondedDevices();
        } else {
            setDeviceMessage("Bluetooth permission required");
            requestBluetoothConnectPermission();
        }
        updateControls();
    }

    @Override
    protected void onDestroy() {
        if (client != null) {
            client.shutdown();
            client = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BLUETOOTH_CONNECT) {
            return;
        }

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setEvent("Bluetooth permission granted");
            loadBondedDevices();
        } else {
            setDeviceMessage("Bluetooth permission denied");
            setConnectionState("Bluetooth permission denied", false);
            setEvent("Grant Bluetooth permission to list paired devices");
        }
        updateControls();
    }

    @Override
    public void onState(String text, boolean isConnected) {
        setConnectionState(text, isConnected);
    }

    @Override
    public void onFrame(BtProtocol.Frame frame) {
        if (frame == null) {
            return;
        }

        try {
            if (frame.cmd == BtProtocol.CMD_STATUS) {
                updateStatus(BtProtocol.parseStatus(frame));
            } else if (frame.cmd == BtProtocol.CMD_SENSOR_DATA) {
                updateSensor(BtProtocol.parseSensor(frame));
            }
        } catch (IllegalArgumentException e) {
            setEvent("Bad Bluetooth frame: " + safeMessage(e));
        }
    }

    @Override
    public void onError(String text) {
        setEvent(text);
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("STM32 Smart Watch Host");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, fullWidthParams());

        addDeviceSpinner(root);

        connectButton = makeButton("Connect");
        disconnectButton = makeButton("Disconnect");
        addButtonRow(root, connectButton, disconnectButton);

        syncTimeButton = makeButton("Sync Time");
        requestStatusButton = makeButton("Request Status");
        addButtonRow(root, syncTimeButton, requestStatusButton);

        prevPageButton = makeButton("Prev Page");
        nextPageButton = makeButton("Next Page");
        addButtonRow(root, prevPageButton, nextPageButton);

        dialButton = makeButton("Dial");
        resetStepsButton = makeButton("Reset Steps");
        addButtonRow(root, dialButton, resetStepsButton);

        connectionText = makeStatusText("Connection: --");
        watchTimeText = makeStatusText("Watch Time: --");
        pageText = makeStatusText("Current Page: 0 Watch Face");
        stepsText = makeStatusText("Steps: --");
        frameCountText = makeStatusText("Bluetooth Frames: --");
        imuText = makeStatusText("IMU: --");
        accelText = makeStatusText("Accel: --");
        gyroText = makeStatusText("Gyro: --");
        eventText = makeStatusText("Last Event: Ready");

        root.addView(connectionText, fullWidthParams());
        root.addView(watchTimeText, fullWidthParams());
        root.addView(pageText, fullWidthParams());
        root.addView(stepsText, fullWidthParams());
        root.addView(frameCountText, fullWidthParams());
        root.addView(imuText, fullWidthParams());
        root.addView(accelText, fullWidthParams());
        root.addView(gyroText, fullWidthParams());
        root.addView(eventText, fullWidthParams());

        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                connectSelectedDevice();
            }
        });
        disconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                disconnect();
            }
        });
        syncTimeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                syncTime();
            }
        });
        requestStatusButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestStatus();
            }
        });
        prevPageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setWatchPage(previousPage(currentPage));
            }
        });
        nextPageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setWatchPage(nextPage(currentPage));
            }
        });
        dialButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setWatchPage(0);
            }
        });
        resetStepsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                resetSteps();
            }
        });

        setContentView(scrollView);
    }

    private void addDeviceSpinner(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(8));

        TextView label = new TextView(this);
        label.setText("Device");
        label.setTextSize(16);
        row.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        deviceSpinner = new Spinner(this);
        deviceAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item,
                deviceLabels);
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(deviceAdapter);

        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f);
        spinnerParams.leftMargin = dp(12);
        row.addView(deviceSpinner, spinnerParams);

        root.addView(row, fullWidthParams());
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(44));
        return button;
    }

    private TextView makeStatusText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(16);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private void addButtonRow(LinearLayout root, Button left, Button right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f);
        leftParams.rightMargin = dp(6);
        row.addView(left, leftParams);

        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f);
        rightParams.leftMargin = dp(6);
        row.addView(right, rightParams);

        LinearLayout.LayoutParams rowParams = fullWidthParams();
        rowParams.topMargin = dp(4);
        root.addView(row, rowParams);
    }

    private LinearLayout.LayoutParams fullWidthParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                    new String[] { Manifest.permission.BLUETOOTH_CONNECT },
                    REQUEST_BLUETOOTH_CONNECT);
        }
    }

    private void loadBondedDevices() {
        devices.clear();
        deviceLabels.clear();

        if (client == null) {
            setDeviceMessage("Bluetooth unavailable");
            updateControls();
            return;
        }
        if (!hasBluetoothConnectPermission()) {
            setDeviceMessage("Bluetooth permission required");
            requestBluetoothConnectPermission();
            updateControls();
            return;
        }

        Set<BluetoothDevice> bondedDevices = client.getBondedDevices();
        for (BluetoothDevice device : bondedDevices) {
            devices.add(device);
            deviceLabels.add(safeDeviceLabel(device));
        }

        if (devices.isEmpty()) {
            deviceLabels.add("No paired Bluetooth devices");
        }
        deviceAdapter.notifyDataSetChanged();
        updateControls();
    }

    private void setDeviceMessage(String message) {
        devices.clear();
        deviceLabels.clear();
        deviceLabels.add(message);
        if (deviceAdapter != null) {
            deviceAdapter.notifyDataSetChanged();
        }
        updateControls();
    }

    private String safeDeviceLabel(BluetoothDevice device) {
        String name = null;
        String address = null;
        try {
            name = device.getName();
        } catch (SecurityException ignored) {
            name = null;
        }
        try {
            address = device.getAddress();
        } catch (SecurityException ignored) {
            address = null;
        }

        if (name == null || name.length() == 0) {
            name = "Paired device";
        }
        if (address == null || address.length() == 0) {
            return name;
        }
        return name + " (" + address + ")";
    }

    private void connectSelectedDevice() {
        if (client == null) {
            setEvent("Bluetooth unavailable");
            return;
        }
        if (!hasBluetoothConnectPermission()) {
            setEvent("Bluetooth permission required");
            requestBluetoothConnectPermission();
            return;
        }

        int index = deviceSpinner.getSelectedItemPosition();
        if (index < 0 || index >= devices.size()) {
            setEvent("Select a paired Bluetooth device first");
            return;
        }
        client.connect(devices.get(index));
    }

    private void disconnect() {
        if (client != null) {
            client.disconnect();
        }
    }

    private void syncTime() {
        if (!canSendCommand()) {
            return;
        }

        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);
        int second = calendar.get(Calendar.SECOND);
        int yearOffset = calendar.get(Calendar.YEAR) - 2000;
        int month = calendar.get(Calendar.MONTH) + 1;
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        int weekday = calendar.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;

        client.write(BtProtocol.buildTimeSyncFrame(
                hour,
                minute,
                second,
                yearOffset,
                month,
                day,
                weekday));
        setEvent("Time sync sent");
    }

    private void requestStatus() {
        if (!canSendCommand()) {
            return;
        }
        client.write(BtProtocol.buildRequestStatusFrame());
        setEvent("Status request sent");
    }

    private void setWatchPage(int page) {
        if (!canSendCommand()) {
            return;
        }
        currentPage = page;
        updatePageText();
        client.write(BtProtocol.buildSetPageFrame(page));
        setEvent("Page command sent: " + pageName(page));
    }

    private void resetSteps() {
        if (!canSendCommand()) {
            return;
        }
        client.write(BtProtocol.buildResetStepsFrame());
        setEvent("Reset steps sent");
    }

    private boolean canSendCommand() {
        if (client == null) {
            setEvent("Bluetooth unavailable");
            return false;
        }
        if (!connected) {
            setEvent("Bluetooth is not connected");
            return false;
        }
        return true;
    }

    private int previousPage(int page) {
        if (page <= 0) {
            return PAGE_NAMES.length - 1;
        }
        return page - 1;
    }

    private int nextPage(int page) {
        if (page >= PAGE_NAMES.length - 1) {
            return 0;
        }
        return page + 1;
    }

    private void updateStatus(WatchStatus status) {
        currentPage = status.page;
        watchTimeText.setText(String.format(
                Locale.US,
                "Watch Time: %04d-%02d-%02d %02d:%02d:%02d",
                status.year,
                status.month,
                status.day,
                status.hour,
                status.minute,
                status.second));
        updatePageText();
        stepsText.setText(String.format(Locale.US, "Steps: %d", status.steps));
        frameCountText.setText(String.format(Locale.US, "Bluetooth Frames: %d", status.btFrames));
        imuText.setText("IMU: " + (status.imuValid ? "OK" : "Invalid"));
        hasWatchConnectionFlag = true;
        watchConnectionFlag = status.connected;
        updateConnectionText();
    }

    private void updateSensor(SensorData data) {
        accelText.setText(String.format(
                Locale.US,
                "Accel: ax=%.3f ay=%.3f az=%.3f",
                data.ax,
                data.ay,
                data.az));
        gyroText.setText(String.format(
                Locale.US,
                "Gyro: gx=%.3f gy=%.3f gz=%.3f",
                data.gx,
                data.gy,
                data.gz));
    }

    private void setConnectionState(String text, boolean isConnected) {
        connectionState = text;
        connected = isConnected;
        connecting = "Connecting".equals(text);
        if (!isConnected && !connecting) {
            hasWatchConnectionFlag = false;
        }
        updateConnectionText();
        updateControls();
    }

    private void updateConnectionText() {
        String text = "Connection: " + connectionState;
        if (hasWatchConnectionFlag) {
            text += " / Watch BT: " + (watchConnectionFlag ? "Connected" : "Idle");
        }
        connectionText.setText(text);
    }

    private void updatePageText() {
        pageText.setText("Current Page: " + pageName(currentPage));
    }

    private String pageName(int page) {
        if (page >= 0 && page < PAGE_NAMES.length) {
            return page + " " + PAGE_NAMES[page];
        }
        return page + " Unknown";
    }

    private void updateControls() {
        boolean hasClient = client != null;
        boolean hasPermission = hasBluetoothConnectPermission();
        boolean hasDevices = !devices.isEmpty();
        boolean canUseBluetooth = hasClient && hasPermission;
        boolean canSend = hasClient && connected;

        if (deviceSpinner != null) {
            deviceSpinner.setEnabled(canUseBluetooth && hasDevices && !connected && !connecting);
        }
        setEnabled(connectButton, canUseBluetooth && hasDevices && !connected && !connecting);
        setEnabled(disconnectButton, hasClient && (connected || connecting));
        setEnabled(syncTimeButton, canSend);
        setEnabled(requestStatusButton, canSend);
        setEnabled(prevPageButton, canSend);
        setEnabled(nextPageButton, canSend);
        setEnabled(dialButton, canSend);
        setEnabled(resetStepsButton, canSend);
    }

    private void setEnabled(View view, boolean enabled) {
        if (view != null) {
            view.setEnabled(enabled);
        }
    }

    private void setEvent(String text) {
        if (eventText != null) {
            eventText.setText("Last Event: " + text);
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.length() == 0) {
            return e.getClass().getSimpleName();
        }
        return message;
    }
}
