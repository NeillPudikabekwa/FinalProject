package za.ac.tut.smartgasalert;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final String DEFAULT_DB = "https://gaslpg-54c74-default-rtdb.firebaseio.com";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView statusView, rawView, deviceView, sensorView, updateView, connectionView;
    private EditText databaseUrl;
    private LinearLayout statusCard;
    private String lastStatus = "";

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            readFirebase();
            handler.postDelayed(this, 3000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createUi();
        requestNotificationPermission();
        startMonitorService();
        refreshTask.run();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refreshTask);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void createUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        root.setBackgroundColor(Color.rgb(244,247,251));
        scroll.addView(root);

        TextView title = text("Smart Gas Monitor", 28, true, Color.rgb(11,31,51));
        root.addView(title);
        TextView sub = text("Firebase Realtime Database • Android Client", 14, false, Color.rgb(85,104,125));
        sub.setPadding(0, dp(4), 0, dp(18));
        root.addView(sub);

        connectionView = pill("CONNECTING…", Color.rgb(85,104,125));
        root.addView(connectionView);

        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(22), dp(24), dp(22), dp(24));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.setMargins(0, dp(16), 0, dp(14));
        statusCard.setLayoutParams(cardLp);
        statusCard.setBackground(roundRect(Color.rgb(232,239,247), 22, Color.rgb(191,205,220)));

        TextView current = text("CURRENT STATUS", 13, true, Color.rgb(95,117,140));
        statusCard.addView(current);
        statusView = text("WAITING", 38, true, Color.rgb(11,31,51));
        statusView.setPadding(0, dp(6), 0, dp(4));
        statusCard.addView(statusView);
        rawView = text("Raw sensor value: --", 20, false, Color.rgb(11,31,51));
        statusCard.addView(rawView);
        root.addView(statusCard);

        LinearLayout info = section();
        info.addView(text("DEVICE INFORMATION", 14, true, Color.rgb(95,117,140)));
        sensorView = text("Sensor: MQ-5", 18, false, Color.rgb(11,31,51));
        deviceView = text("Device: prototype-01", 18, false, Color.rgb(11,31,51));
        updateView = text("Last update: --", 16, false, Color.rgb(60,80,102));
        sensorView.setPadding(0, dp(12), 0, dp(8));
        deviceView.setPadding(0, 0, 0, dp(8));
        info.addView(sensorView);
        info.addView(deviceView);
        info.addView(updateView);
        root.addView(info);

        Button safety = button("Emergency instructions");
        safety.setOnClickListener(v -> showSafetyDialog());
        root.addView(safety);

        Button refresh = button("Refresh now");
        refresh.setOnClickListener(v -> readFirebase());
        root.addView(refresh);

        LinearLayout cfg = section();
        cfg.addView(text("FIREBASE CONNECTION", 14, true, Color.rgb(95,117,140)));
        cfg.addView(text("The app reads /gasMonitor/current directly from your Realtime Database.", 14, false, Color.rgb(60,80,102)));
        databaseUrl = new EditText(this);
        databaseUrl.setText(getPrefs().getString("database_url", DEFAULT_DB));
        databaseUrl.setSingleLine(true);
        databaseUrl.setTextSize(14);
        databaseUrl.setHint("https://PROJECT-default-rtdb.firebaseio.com");
        cfg.addView(databaseUrl, new LinearLayout.LayoutParams(-1, -2));
        Button save = button("Save Firebase URL");
        save.setOnClickListener(v -> {
            String url = cleanBaseUrl(databaseUrl.getText().toString());
            getPrefs().edit().putString("database_url", url).apply();
            databaseUrl.setText(url);
            startMonitorService();
            readFirebase();
            Toast.makeText(this, "Firebase URL saved", Toast.LENGTH_SHORT).show();
        });
        cfg.addView(save);
        root.addView(cfg);

        TextView note = text("Engineering prototype: raw MQ-5 ADC values are not calibrated ppm measurements.", 12, false, Color.rgb(100,115,130));
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    private void readFirebase() {
        final String base = cleanBaseUrl(getPrefs().getString("database_url", DEFAULT_DB));
        executor.execute(() -> {
            try {
                URL url = new URL(base + "/gasMonitor/current.json");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);
                String json = readAll(c.getInputStream());
                if (json == null || json.trim().equals("null")) throw new IOException("No current reading");
                JSONObject o = new JSONObject(json);
                int raw = o.optInt("rawValue", -1);
                String status = o.optString("status", "");
                if (status.isEmpty()) status = classify(raw);
                final String fStatus = status.toUpperCase();
                final String sensor = o.optString("sensor", "MQ-5");
                final String device = o.optString("device", "prototype-01");
                long ts = o.optLong("timestamp", System.currentTimeMillis());
                final String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(ts));
                handler.post(() -> updateUi(fStatus, raw, sensor, device, when));
            } catch (Exception e) {
                handler.post(() -> {
                    connectionView.setText("OFFLINE / CHECK FIREBASE URL");
                    connectionView.setBackground(roundRect(Color.rgb(220,53,69), 20, Color.TRANSPARENT));
                });
            }
        });
    }

    private void updateUi(String status, int raw, String sensor, String device, String when) {
        connectionView.setText("LIVE FIREBASE");
        connectionView.setBackground(roundRect(Color.rgb(25,135,84), 20, Color.TRANSPARENT));
        statusView.setText(status);
        rawView.setText("Raw sensor value: " + (raw >= 0 ? raw : "--"));
        sensorView.setText("Sensor: " + sensor);
        deviceView.setText("Device: " + device);
        updateView.setText("Last update: " + when);

        int bg, border, fg;
        if ("DANGER".equals(status)) {
            bg = Color.rgb(255,235,235); border = Color.rgb(205,42,45); fg = Color.rgb(205,42,45);
        } else if ("WARNING".equals(status)) {
            bg = Color.rgb(255,247,218); border = Color.rgb(214,157,0); fg = Color.rgb(160,110,0);
        } else {
            bg = Color.rgb(231,248,238); border = Color.rgb(25,135,84); fg = Color.rgb(25,135,84);
        }
        statusCard.setBackground(roundRect(bg, 22, border));
        statusView.setTextColor(fg);

        if ("DANGER".equals(status) && !"DANGER".equals(lastStatus)) {
            GasMonitorService.postDangerNotification(this, raw);
            if (!isFinishing()) showSafetyDialog();
        }
        lastStatus = status;
    }

    private String classify(int raw) {
        if (raw < 0) return "UNKNOWN";
        if (raw <= 100) return "DANGER";
        if (raw <= 800) return "WARNING";
        return "NORMAL";
    }

    private void showSafetyDialog() {
        new AlertDialog.Builder(this)
            .setTitle("DANGER — Gas leak safety")
            .setMessage("1. Leave the affected area and move to fresh air.\n\n" +
                    "2. Do not use flames, smoke, switches, plugs or appliances.\n\n" +
                    "3. Do not use a phone until you are outside the suspected leak area.\n\n" +
                    "4. Close the gas valve only if it is safe to do so.\n\n" +
                    "5. Ventilate naturally if safe; do not switch on an electrical fan.\n\n" +
                    "6. From outside, contact emergency services or a qualified gas supplier.\n\n" +
                    "7. Do not re-enter until the area is declared safe.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Call 112", (d,w) -> {
                Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"));
                startActivity(i);
            }).show();
    }

    private void startMonitorService() {
        Intent i = new Intent(this, GasMonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private SharedPreferences getPrefs() { return getSharedPreferences("smartgas", MODE_PRIVATE); }
    private String cleanBaseUrl(String s) {
        String x = (s == null || s.trim().isEmpty()) ? DEFAULT_DB : s.trim();
        while (x.endsWith("/")) x = x.substring(0, x.length()-1);
        return x;
    }
    private String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) != -1) b.write(buf,0,n);
        return b.toString(StandardCharsets.UTF_8.name());
    }
    private TextView text(String s, int sp, boolean bold, int color) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color);
        if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return v;
    }
    private TextView pill(String s, int color) {
        TextView v = text(s, 12, true, Color.WHITE); v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12),dp(7),dp(12),dp(7)); v.setBackground(roundRect(color,20,Color.TRANSPARENT));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1,-2)); return v;
    }
    private LinearLayout section() {
        LinearLayout x = new LinearLayout(this); x.setOrientation(LinearLayout.VERTICAL); x.setPadding(dp(18),dp(18),dp(18),dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,dp(8),0,dp(8)); x.setLayoutParams(lp);
        x.setBackground(roundRect(Color.WHITE,18,Color.rgb(214,224,234))); return x;
    }
    private Button button(String s) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,dp(6),0,dp(6)); b.setLayoutParams(lp);
        return b;
    }
    private GradientDrawable roundRect(int fill, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT) d.setStroke(dp(1), stroke); return d;
    }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
}
