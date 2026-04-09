/*
 * Developed by: Eng. Mahmoud Souliman
 * Project: Knee Angle Monitor Android Client (Java)
 */

package com.example.kneeanglemonitor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Developed by Eng. Mahmoud Souliman
 * Project: Smart Knee Goniometer - Android Client
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "4MED_Knee_Project";
    private TextView angleValue;
    private Button btnConnect;
    private KneeView kneeView;

    private BluetoothSocket bluetoothSocket;
    private BluetoothDevice selectedDevice;
    private InputStream inputStream;
    private volatile boolean stopWorker;
    private final byte[] readBuffer = new byte[1024];
    private int readBufferPosition;

    private boolean isRadian = false;
    private double currentAngle = 0.0;

    // Standard UUID for SPP (Serial Port Profile) protocol
    private static final UUID mUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind UI elements
        angleValue = findViewById(R.id.angleValue);
        btnConnect = findViewById(R.id.btnConnect);
        Button btnToggleUnit = findViewById(R.id.btnToggleUnit);
        Button btnAbout = findViewById(R.id.btnAbout);
        LinearLayout drawingContainer = findViewById(R.id.drawingContainer);

        // Setup custom drawing view
        kneeView = new KneeView(this);
        drawingContainer.addView(kneeView);

        // Check and request necessary permissions
        checkPermissions();

        // Setup button listeners
        btnConnect.setOnClickListener(v -> showDevicePicker());

        btnToggleUnit.setOnClickListener(v -> {
            isRadian = !isRadian;
            updateDisplay();
            sendSignal("z"); // Send zeroing/calibration signal to the sensor
        });

        btnAbout.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, AboutActivity.class);
            startActivity(intent);
        });
    }

    private void checkPermissions() {
        String[] permissions = {
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    listPermissionsNeeded.toArray(new String[0]), 1);
        }
    }

    private void showDevicePicker() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            checkPermissions();
            return;
        }

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bm != null) ? bm.getAdapter() : null;

        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }

        @SuppressLint("MissingPermission")
        Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();

        if (pairedDevices != null && !pairedDevices.isEmpty()) {
            List<String> names = new ArrayList<>();
            final List<BluetoothDevice> devicesList = new ArrayList<>();
            for (BluetoothDevice d : pairedDevices) {
                @SuppressLint("MissingPermission")
                String dName = (d.getName() != null) ? d.getName() : "Unknown Device";
                names.add(dName + "\n" + d.getAddress());
                devicesList.add(d);
            }

            new AlertDialog.Builder(this)
                    .setTitle("Select Bluetooth Device")
                    .setItems(names.toArray(new CharSequence[0]), (dialog, which) -> {
                        selectedDevice = devicesList.get(which);
                        initiateConnection();
                    }).show();
        } else {
            Toast.makeText(this, "No paired devices found! Please pair from System Settings.", Toast.LENGTH_LONG).show();
        }
    }

    private void initiateConnection() {
        btnConnect.setText("Connecting...");
        btnConnect.setEnabled(false);

        new Thread(() -> {
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) return;

                // Close any existing connection before starting
                if (bluetoothSocket != null) {
                    try { bluetoothSocket.close(); } catch (IOException ignored) {}
                }

                // Attempt connection via standard UUID
                bluetoothSocket = selectedDevice.createInsecureRfcommSocketToServiceRecord(mUUID);

                // Wait for hardware stabilization (1000ms)
                Thread.sleep(1000);
                bluetoothSocket.connect();
                connectionSuccess();

            } catch (Exception e) {
                Log.e(TAG, "Primary connection failed, trying fallback reflection...", e);
                try {
                    // Fallback to Reflection method for robust connection
                    @SuppressLint("DiscouragedPrivateApi")
                    Method m = selectedDevice.getClass().getMethod("createRfcommSocket", int.class);
                    bluetoothSocket = (BluetoothSocket) m.invoke(selectedDevice, 1);

                    if (bluetoothSocket != null) {
                        Thread.sleep(1000); // Wait again for the fallback socket
                        bluetoothSocket.connect();
                        connectionSuccess();
                    }
                } catch (Exception ex) {
                    runOnUiThread(() -> {
                        handleError("Connection Failed");
                        btnConnect.setEnabled(true);
                    });
                }
            }
        }).start();
    }

    private void connectionSuccess() {
        runOnUiThread(() -> {
            try {
                startListenLoop();
                @SuppressLint("MissingPermission")
                String deviceName = selectedDevice.getName();
                btnConnect.setText("Connected to: " + deviceName);
                btnConnect.setBackgroundColor(Color.parseColor("#27AE60")); // Green color on success
                btnConnect.setEnabled(true);
            } catch (IOException e) {
                handleError("Failed to start data stream");
            }
        });
    }

    private void startListenLoop() throws IOException {
        inputStream = bluetoothSocket.getInputStream();
        stopWorker = false;
        readBufferPosition = 0;

        final Handler handler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && !stopWorker) {
                try {
                    int bytesAvailable = inputStream.available();
                    if (bytesAvailable > 0) {
                        byte[] packetBytes = new byte[bytesAvailable];
                        int bytesRead = inputStream.read(packetBytes);
                        for (int i = 0; i < bytesRead; i++) {
                            byte b = packetBytes[i];
                            if (b == 10) { // New Line character (\n)
                                final String data = new String(readBuffer, 0, readBufferPosition, StandardCharsets.US_ASCII).trim();
                                readBufferPosition = 0;
                                handler.post(() -> updateLogic(data));
                            } else {
                                if (readBufferPosition < readBuffer.length) {
                                    readBuffer[readBufferPosition++] = b;
                                }
                            }
                        }
                    }
                } catch (IOException ex) {
                    stopWorker = true;
                    runOnUiThread(() -> handleError("Connection lost!"));
                }
            }
        }).start();
    }

    private void updateLogic(String data) {
        // Validate that incoming data is numeric
        if (!data.isEmpty() && data.matches("-?\\d+(\\.\\d+)?")) {
            try {
                currentAngle = Double.parseDouble(data);
                updateDisplay();
                kneeView.refreshAngle((float) currentAngle);
            } catch (NumberFormatException ignored) {}
        }
    }

    private void sendSignal(@SuppressWarnings("sameParameterValue") String signal) {
        if (bluetoothSocket != null && bluetoothSocket.isConnected()) {
            new Thread(() -> {
                try {
                    bluetoothSocket.getOutputStream().write(signal.getBytes());
                } catch (IOException e) {
                    Log.e(TAG, "Error sending signal to hardware", e);
                }
            }).start();
        }
    }

    private void handleError(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        btnConnect.setText("Reconnect");
        btnConnect.setBackgroundColor(Color.GRAY);
        btnConnect.setEnabled(true);
    }

    private void updateDisplay() {
        double val = isRadian ? currentAngle * (Math.PI / 180.0) : currentAngle;
        String unit = isRadian ? " Rad" : "°";
        angleValue.setText(String.format(Locale.US, "%.1f%s", val, unit));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopWorker = true;
        try { if (bluetoothSocket != null) bluetoothSocket.close(); } catch (IOException ignored) {}
    }

    /**
     * Custom View for biomechanical rendering of the knee joint
     */
    private static class KneeView extends View {
        private final Paint pLine = new Paint();
        private final Paint pJoint = new Paint();
        private float angle = 0;

        public KneeView(Context context) {
            super(context);
            init();
        }

        private void init() {
            pLine.setColor(Color.parseColor("#2C3E50"));
            pLine.setStrokeWidth(18f);
            pLine.setStyle(Paint.Style.STROKE);
            pLine.setStrokeCap(Paint.Cap.ROUND);
            pLine.setAntiAlias(true);

            pJoint.setColor(Color.parseColor("#E74C3C"));
            pJoint.setStyle(Paint.Style.FILL);
            pJoint.setAntiAlias(true);
        }

        public void refreshAngle(float a) {
            this.angle = a;
            invalidate(); // Re-draw the view
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            // Draw the fixed part (Thigh)
            canvas.drawLine(cx, cy, cx, cy - 220, pLine);

            // Draw the moving part (Shin) based on sensor angle
            double r = Math.toRadians(angle + 90);
            float endX = cx + (float)(220 * Math.cos(r));
            float endY = cy + (float)(220 * Math.sin(r));
            canvas.drawLine(cx, cy, endX, endY, pLine);

            // Draw the joint center point
            canvas.drawCircle(cx, cy, 22, pJoint);
        }
    }
}
