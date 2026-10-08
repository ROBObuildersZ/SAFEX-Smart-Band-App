package com.safex.webview;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.GeolocationPermissions;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private WebView webView;
    private BluetoothAdapter adapter;
    private BluetoothSocket socket;
    private OutputStream output;
    private InputStream input;
    private Thread readerThread;
    private volatile boolean reading = false;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int REQ_BT = 501;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 777);
                    callback.invoke(origin, false, false);
                } else {
                    callback.invoke(origin, true, false);
                }
            }
        });
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");
        adapter = BluetoothAdapter.getDefaultAdapter();
    }

    private boolean hasBtPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestBtPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        }
    }

    private void jsStatus(String msg) {
        main.post(() -> webView.evaluateJavascript("onNativeStatus(" + jsQuote(msg) + ")", null));
    }

    private void jsRx(String msg) {
        main.post(() -> webView.evaluateJavascript("onNativeRx(" + jsQuote(msg) + ")", null));
    }

    private String jsQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private void chooseDevice() {
        if (adapter == null) { jsStatus("ERROR: Bluetooth not supported"); return; }
        if (!adapter.isEnabled()) {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            jsStatus("Bluetooth is off. Turn it on and press Connect again.");
            return;
        }
        if (!hasBtPermission()) { requestBtPermission(); return; }

        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        List<BluetoothDevice> list = new ArrayList<>();
        for (BluetoothDevice d : bonded) {
            String n = d.getName();
            if (n != null && (n.toUpperCase().contains("HC-05") || n.toUpperCase().contains("SAFEX") || n.toUpperCase().contains("HC05"))) list.add(d);
        }
        if (list.isEmpty()) {
            for (BluetoothDevice d : bonded) list.add(d);
        }
        if (list.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("No paired Bluetooth device")
                    .setMessage("Pair HC-05 from Android Settings → Bluetooth first, then return here.")
                    .setPositiveButton("Bluetooth Settings", (d,w) -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                    .setNegativeButton("Close", null).show();
            return;
        }
        String[] names = new String[list.size()];
        for (int i=0;i<list.size();i++) names[i] = (list.get(i).getName()==null?"Unknown":list.get(i).getName());
        new AlertDialog.Builder(this).setTitle("Select SAFEX / HC-05")
                .setItems(names, (dialog, which) -> connectDevice(list.get(which))).setNegativeButton("Cancel", null).show();
    }

    private void connectDevice(BluetoothDevice device) {
        new Thread(() -> {
            closeSocket();
            try {
                if (hasBtPermission()) adapter.cancelDiscovery();
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                output = socket.getOutputStream();
                input = socket.getInputStream();
                jsStatus("Connected: " + device.getName());
                startReader();
                sendLineInternal("APP,1");
            } catch (Exception e) {
                closeSocket();
                jsStatus("Connection failed: " + e.getMessage());
            }
        }).start();
    }

    private void startReader() {
        reading = true;
        readerThread = new Thread(() -> {
            byte[] buf = new byte[256];
            StringBuilder pending = new StringBuilder();
            try {
                while (reading && input != null) {
                    int n = input.read(buf);
                    if (n < 0) break;
                    String chunk = new String(buf, 0, n, "UTF-8");
                    pending.append(chunk);
                    int idx;
                    while ((idx = pending.indexOf("\n")) >= 0) {
                        String line = pending.substring(0, idx).replace("\r", "").trim();
                        pending.delete(0, idx + 1);
                        if (!line.isEmpty()) jsRx(line);
                    }
                }
            } catch (IOException ignored) {}
            if (reading) jsStatus("Disconnected");
        });
        readerThread.start();
    }

    private synchronized boolean sendLineInternal(String line) {
        try {
            if (output == null) return false;
            output.write((line + "\n").getBytes("UTF-8"));
            output.flush();
            return true;
        } catch (Exception e) {
            jsStatus("Write error: " + e.getMessage());
            return false;
        }
    }

    private void closeSocket() {
        reading = false;
        try { if (input != null) input.close(); } catch (Exception ignored) {}
        try { if (output != null) output.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        input = null; output = null; socket = null;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT) {
            boolean ok = true; for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            jsStatus(ok ? "Bluetooth permission granted. Press Connect again." : "Bluetooth permission denied.");
        }
    }

    public class AndroidBridge {
        @JavascriptInterface public boolean isAndroidApp() { return true; }
        @JavascriptInterface public void connectBluetooth() { main.post(() -> { if (!hasBtPermission()) requestBtPermission(); else chooseDevice(); }); }
        @JavascriptInterface public void disconnectBluetooth() { closeSocket(); jsStatus("Disconnected"); }
        @JavascriptInterface public void sendLine(String line) { new Thread(() -> sendLineInternal(line)).start(); }
        @JavascriptInterface public void openDialer(String number) { main.post(() -> startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))); }
        @JavascriptInterface public void openSms(String numbers, String body) {
            main.post(() -> { Intent i = new Intent(Intent.ACTION_SENDTO); i.setData(Uri.parse("smsto:" + Uri.encode(numbers))); i.putExtra("sms_body", body); startActivity(i); });
        }
        @JavascriptInterface public void toast(String message) { main.post(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show()); }
    }

    @Override protected void onDestroy() { closeSocket(); super.onDestroy(); }
}
