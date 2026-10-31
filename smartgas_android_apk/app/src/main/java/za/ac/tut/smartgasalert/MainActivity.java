package za.ac.tut.smartgasalert;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.database.*;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String PROJECT_ID = "gaslpg-54c74";
    private static final String DATABASE_URL = "https://gaslpg-54c74-default-rtdb.firebaseio.com";
    private static final String APP_ID = "1:859259164362:android:3011bb9ff108a446f44633";
    private static final String API_KEY = "AIzaSyA-XkLCV2-NS7zyJwz3IGBuuJg8qCcpv_0";
    private static final String SENDER_ID = "859259164362";

    private TextView connectionView, statusView, rawView, deviceView, sensorView, updateView, buzzerView, debugView;
    private LinearLayout statusCard;
    private DatabaseReference currentRef;
    private String lastStatus = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createUi();
        requestNotificationPermission();
        initialiseFirebase();
        startMonitorService();
    }

    private void createUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        root.setBackgroundColor(Color.rgb(244,247,251));
        scroll.addView(root);

        root.addView(text("Smart Gas Monitor", 28, true, Color.rgb(11,31,51)));
        TextView sub = text("Live Firebase • MQ-5 gas monitoring", 14, false, Color.rgb(85,104,125));
        sub.setPadding(0, dp(4), 0, dp(16));
        root.addView(sub);

        connectionView = pill("CONNECTING TO FIREBASE…", Color.rgb(85,104,125));
        root.addView(connectionView);

        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(22), dp(24), dp(22), dp(24));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.setMargins(0, dp(16), 0, dp(14));
        statusCard.setLayoutParams(cardLp);
        statusCard.setBackground(roundRect(Color.rgb(232,239,247), 22, Color.rgb(191,205,220)));

        statusCard.addView(text("CURRENT STATUS", 13, true, Color.rgb(95,117,140)));
        statusView = text("WAITING", 38, true, Color.rgb(11,31,51));
        statusView.setPadding(0, dp(6), 0, dp(4));
        statusCard.addView(statusView);
        rawView = text("ADC reading: --", 21, false, Color.rgb(11,31,51));
        statusCard.addView(rawView);
        buzzerView = text("Buzzer: --", 17, true, Color.rgb(60,80,102));
        buzzerView.setPadding(0, dp(8), 0, 0);
        statusCard.addView(buzzerView);
        root.addView(statusCard);

        LinearLayout thresholds = section();
        thresholds.addView(text("STATUS THRESHOLDS", 14, true, Color.rgb(95,117,140)));
        TextView safe = text("● SAFE     0–109 ADC     Green • buzzer OFF", 16, true, Color.rgb(25,135,84));
        TextView warning = text("● WARNING  110–650 ADC   Yellow • buzzer OFF", 16, true, Color.rgb(160,110,0));
        TextView danger = text("● DANGER   651+ ADC      Red • buzzer ON", 16, true, Color.rgb(205,42,45));
        safe.setPadding(0, dp(12), 0, dp(7));
        warning.setPadding(0, 0, 0, dp(7));
        thresholds.addView(safe);
        thresholds.addView(warning);
        thresholds.addView(danger);
        root.addView(thresholds);

        LinearLayout info = section();
        info.addView(text("DEVICE INFORMATION", 14, true, Color.rgb(95,117,140)));
        sensorView = text("Sensor: MQ-5", 17, false, Color.rgb(11,31,51));
        deviceView = text("Device: prototype-01", 17, false, Color.rgb(11,31,51));
        updateView = text("Last update: --", 15, false, Color.rgb(60,80,102));
        sensorView.setPadding(0, dp(12), 0, dp(7));
        deviceView.setPadding(0, 0, 0, dp(7));
        info.addView(sensorView);
        info.addView(deviceView);
        info.addView(updateView);
        root.addView(info);

        Button refresh = button("Refresh Firebase reading");
        refresh.setOnClickListener(v -> refreshOnce());
        root.addView(refresh);

        Button safety = button("Emergency instructions");
        safety.setOnClickListener(v -> showSafetyDialog());
        root.addView(safety);

        LinearLayout diag = section();
        diag.addView(text("CONNECTION DIAGNOSTICS", 14, true, Color.rgb(95,117,140)));
        debugView = text("Project: " + PROJECT_ID + "\nPath: /gasMonitor/current", 13, false, Color.rgb(60,80,102));
        debugView.setPadding(0, dp(10), 0, 0);
        diag.addView(debugView);
        root.addView(diag);

        TextView note = text("Prototype note: ADC values are raw sensor readings, not calibrated LPG ppm.", 12, false, Color.rgb(100,115,130));
        note.setPadding(0, dp(12), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    private void initialiseFirebase() {
        try {
            FirebaseApp app;
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseOptions options = new FirebaseOptions.Builder()
                        .setProjectId(PROJECT_ID)
                        .setApplicationId(APP_ID)
                        .setApiKey(API_KEY)
                        .setGcmSenderId(SENDER_ID)
                        .setDatabaseUrl(DATABASE_URL)
                        .build();
                app = FirebaseApp.initializeApp(this, options);
            } else {
                app = FirebaseApp.getInstance();
            }

            if (app == null) throw new IllegalStateException("Firebase initialization returned null");

            FirebaseDatabase database = FirebaseDatabase.getInstance(app);
            currentRef = database.getReference("gasMonitor").child("current");

            currentRef.addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snapshot) {
                    if (!snapshot.exists()) {
                        showConnectionMessage("FIREBASE CONNECTED • NO CURRENT DATA", Color.rgb(214,157,0));
                        debugView.setText("Connected successfully, but /gasMonitor/current is empty.");
                        return;
                    }
                    applySnapshot(snapshot, "LIVE FIREBASE");
                }

                @Override public void onCancelled(DatabaseError error) {
                    showConnectionMessage("FIREBASE READ ERROR", Color.rgb(205,42,45));
                    debugView.setText("Read cancelled: " + error.getMessage() + "\nCode: " + error.getCode());
                }
            });

            database.getReference(".info/connected").addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snapshot) {
                    Boolean online = snapshot.getValue(Boolean.class);
                    if (Boolean.FALSE.equals(online)) {
                        showConnectionMessage("OFFLINE • FIREBASE DISCONNECTED", Color.rgb(205,42,45));
                    }
                }
                @Override public void onCancelled(DatabaseError error) { }
            });

        } catch (Exception e) {
            showConnectionMessage("FIREBASE SETUP ERROR", Color.rgb(205,42,45));
            debugView.setText(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void refreshOnce() {
        if (currentRef == null) {
            initialiseFirebase();
            return;
        }
        showConnectionMessage("REFRESHING…", Color.rgb(85,104,125));
        currentRef.get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.exists()) applySnapshot(snapshot, "LIVE FIREBASE • REFRESHED");
                    else {
                        showConnectionMessage("FIREBASE CONNECTED • NO CURRENT DATA", Color.rgb(214,157,0));
                        debugView.setText("No data exists at /gasMonitor/current.");
                    }
                })
                .addOnFailureListener(e -> {
                    showConnectionMessage("REFRESH ERROR", Color.rgb(205,42,45));
                    debugView.setText("Firebase read failed: " + e.getMessage());
                });
    }

    private void applySnapshot(DataSnapshot snapshot, String sourceLabel) {
        long raw = parseLong(snapshot.child("rawValue").getValue(), -1);
        if (raw < 0) {
            raw = parseLong(snapshot.child("adc").getValue(), -1);
        }
        if (raw < 0) {
            raw = parseLong(snapshot.child("value").getValue(), -1);
        }

        String sensor = value(snapshot.child("sensor").getValue());
        String device = value(snapshot.child("device").getValue());
        long ts = parseLong(snapshot.child("timestamp").getValue(), System.currentTimeMillis());

        if (sensor.isEmpty()) sensor = "MQ-5";
        if (device.isEmpty()) device = "prototype-01";
        if (ts > 0 && ts < 100000000000L) ts *= 1000L;

        String status = classify(raw);
        updateUi(status, raw, sensor, device, ts);
        showConnectionMessage(sourceLabel, Color.rgb(25,135,84));
        debugView.setText(
                "Snapshot received from /gasMonitor/current" +
                "\nFirebase status field: " + value(snapshot.child("status").getValue()) +
                "\nApp status from ADC: " + status +
                "\nFields in snapshot: " + snapshot.getChildrenCount()
        );
    }

    private String classify(long raw) {
        if (raw < 0) return "UNKNOWN";
        if (raw < 110) return "SAFE";
        if (raw <= 650) return "WARNING";
        return "DANGER";
    }

    private void updateUi(String status, long raw, String sensor, String device, long ts) {
        statusView.setText(status);
        rawView.setText(raw >= 0 ? "ADC reading: " + raw : "ADC reading: --");
        sensorView.setText("Sensor: " + sensor);
        deviceView.setText("Device: " + device);
        updateView.setText("Last update: " + DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.MEDIUM).format(new Date(ts)));

        int bg, border, fg;
        if ("DANGER".equals(status)) {
            bg = Color.rgb(255,235,235);
            border = Color.rgb(205,42,45);
            fg = Color.rgb(205,42,45);
            buzzerView.setText("Buzzer: ON");
            buzzerView.setTextColor(fg);
        } else if ("WARNING".equals(status)) {
            bg = Color.rgb(255,247,218);
            border = Color.rgb(214,157,0);
            fg = Color.rgb(160,110,0);
            buzzerView.setText("Buzzer: OFF");
            buzzerView.setTextColor(fg);
        } else if ("SAFE".equals(status)) {
            bg = Color.rgb(231,248,238);
            border = Color.rgb(25,135,84);
            fg = Color.rgb(25,135,84);
            buzzerView.setText("Buzzer: OFF");
            buzzerView.setTextColor(fg);
        } else {
            bg = Color.rgb(238,242,247);
            border = Color.rgb(150,165,180);
            fg = Color.rgb(70,85,100);
            buzzerView.setText("Buzzer: --");
            buzzerView.setTextColor(fg);
        }

        statusCard.setBackground(roundRect(bg, 22, border));
        statusView.setTextColor(fg);

        if ("DANGER".equals(status) && !"DANGER".equals(lastStatus)) {
            GasMonitorService.postDangerNotification(this, (int)raw);
            if (!isFinishing()) showSafetyDialog();
        }
        lastStatus = status;
    }

    private long parseLong(Object obj, long fallback) {
        if (obj == null) return fallback;
        if (obj instanceof Number) return ((Number)obj).longValue();
        try { return Long.parseLong(String.valueOf(obj).trim()); }
        catch (Exception e) { return fallback; }
    }

    private String value(Object obj) {
        return obj == null ? "" : String.valueOf(obj).trim();
    }

    private void showConnectionMessage(String message, int color) {
        connectionView.setText(message);
        connectionView.setBackground(roundRect(color, 20, Color.TRANSPARENT));
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
                .setNeutralButton("Call 112", (d,w) -> startActivity(
                        new Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))))
                .show();
    }

    private void startMonitorService() {
        try {
            Intent i = new Intent(this, GasMonitorService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception e) {
            debugView.setText(debugView.getText() + "\nBackground service: " + e.getMessage());
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(color);
        if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return v;
    }

    private TextView pill(String s, int color) {
        TextView v = text(s, 12, true, Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), dp(7), dp(12), dp(7));
        v.setBackground(roundRect(color, 20, Color.TRANSPARENT));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private LinearLayout section() {
        LinearLayout x = new LinearLayout(this);
        x.setOrientation(LinearLayout.VERTICAL);
        x.setPadding(dp(18), dp(18), dp(18), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), 0, dp(8));
        x.setLayoutParams(lp);
        x.setBackground(roundRect(Color.WHITE, 18, Color.rgb(214,224,234)));
        return x;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable roundRect(int fill, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT) d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(int n) {
        return (int)(n * getResources().getDisplayMetrics().density + .5f);
    }
}
