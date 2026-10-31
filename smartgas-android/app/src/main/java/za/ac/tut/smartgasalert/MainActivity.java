package za.ac.tut.smartgasalert;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.*;
import android.widget.*;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String FIREBASE_URL =
            "https://gaslpg-54c74-default-rtdb.firebaseio.com/gasMonitor/current.json";
    private static final String CHANNEL_ID = "gas_alerts";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView statusText, rawText, sensorText, deviceText, updatedText, connectionText;
    private LinearLayout statusCard;
    private String lastStatus = "";
    private boolean running = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createNotificationChannel();
        requestNotificationPermission();
        buildUi();
        startPolling();
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(18, 45, 68));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, 6, 0, 6);
        return t;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 42, 40, 42);
        root.setBackgroundColor(Color.rgb(244, 248, 252));
        scroll.addView(root);

        TextView title = text("Smart Gas Monitor", 28, true);
        root.addView(title);
        TextView subtitle = text("Live Firebase gas-leak monitoring", 16, false);
        subtitle.setTextColor(Color.rgb(91, 113, 132));
        root.addView(subtitle);

        connectionText = text("Connecting to Firebase…", 14, true);
        connectionText.setTextColor(Color.rgb(55, 107, 155));
        root.addView(connectionText);

        Space s1 = new Space(this);
        root.addView(s1, new LinearLayout.LayoutParams(1, 30));

        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(30, 30, 30, 30);
        statusCard.setGravity(Gravity.CENTER);
        statusCard.setBackgroundColor(Color.rgb(229, 238, 245));

        TextView current = text("CURRENT STATUS", 14, true);
        current.setTextColor(Color.rgb(100, 120, 138));
        statusCard.addView(current);

        statusText = text("WAITING", 38, true);
        statusText.setGravity(Gravity.CENTER);
        statusCard.addView(statusText);

        rawText = text("Raw sensor value: --", 20, false);
        rawText.setGravity(Gravity.CENTER);
        statusCard.addView(rawText);

        root.addView(statusCard, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Space s2 = new Space(this);
        root.addView(s2, new LinearLayout.LayoutParams(1, 28));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(26, 24, 26, 24);
        info.setBackgroundColor(Color.WHITE);

        TextView infoTitle = text("DEVICE INFORMATION", 15, true);
        infoTitle.setTextColor(Color.rgb(95, 118, 140));
        info.addView(infoTitle);

        sensorText = text("Sensor: MQ-5", 18, false);
        deviceText = text("Device: prototype-01", 18, false);
        updatedText = text("Last update: --", 16, false);
        info.addView(sensorText);
        info.addView(deviceText);
        info.addView(updatedText);
        root.addView(info);

        Button instructions = new Button(this);
        instructions.setText("Emergency instructions");
        instructions.setTextSize(17);
        instructions.setOnClickListener(v -> showEmergencyInstructions());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.setMargins(0, 28, 0, 10);
        root.addView(instructions, bp);

        TextView note = text(
                "Engineering prototype — raw MQ-5 ADC values are not certified gas concentration measurements.",
                13, false);
        note.setTextColor(Color.rgb(100, 120, 138));
        note.setPadding(0, 30, 0, 20);
        root.addView(note);

        setContentView(scroll);
    }

    private void startPolling() {
        handler.post(new Runnable() {
            @Override public void run() {
                if (!running) return;
                readFirebase();
                handler.postDelayed(this, 2000);
            }
        });
    }

    private void readFirebase() {
        executor.execute(() -> {
            HttpURLConnection con = null;
            try {
                URL url = new URL(FIREBASE_URL);
                con = (HttpURLConnection) url.openConnection();
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);
                con.setRequestMethod("GET");

                int code = con.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

                BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                String body = sb.toString();
                if (body.equals("null") || body.isEmpty()) throw new Exception("No data");

                JSONObject data = new JSONObject(body);
                String status = data.optString("status", "UNKNOWN").toUpperCase();
                int raw = data.optInt("rawValue", -1);
                String sensor = data.optString("sensor", "MQ-5");
                String device = data.optString("device", "prototype-01");
                long timestamp = data.optLong("timestamp", 0);

                handler.post(() -> updateUi(status, raw, sensor, device, timestamp));
            } catch (Exception e) {
                handler.post(() -> {
                    connectionText.setText("Firebase connection error");
                    connectionText.setTextColor(Color.rgb(196, 55, 55));
                });
            } finally {
                if (con != null) con.disconnect();
            }
        });
    }

    private void updateUi(String status, int raw, String sensor, String device, long timestamp) {
        connectionText.setText("LIVE • Firebase connected");
        connectionText.setTextColor(Color.rgb(20, 134, 86));

        statusText.setText(status);
        rawText.setText("Raw sensor value: " + (raw >= 0 ? raw : "--"));
        sensorText.setText("Sensor: " + sensor);
        deviceText.setText("Device: " + device);

        if (timestamp > 0) {
            updatedText.setText("Last update: " +
                    DateFormat.getDateTimeInstance().format(new Date(timestamp)));
        } else {
            updatedText.setText("Last update: live");
        }

        if ("DANGER".equals(status)) {
            statusCard.setBackgroundColor(Color.rgb(255, 224, 224));
            statusText.setTextColor(Color.rgb(201, 38, 38));
            if (!"DANGER".equals(lastStatus)) {
                showDangerNotification(raw);
                showEmergencyInstructions();
            }
        } else if ("WARNING".equals(status)) {
            statusCard.setBackgroundColor(Color.rgb(255, 244, 203));
            statusText.setTextColor(Color.rgb(167, 112, 0));
        } else {
            statusCard.setBackgroundColor(Color.rgb(221, 246, 231));
            statusText.setTextColor(Color.rgb(18, 132, 74));
        }
        lastStatus = status;
    }

    private void showEmergencyInstructions() {
        new AlertDialog.Builder(this)
                .setTitle("Gas leak safety instructions")
                .setMessage(
                        "1. Leave the affected area and move to fresh air.\n\n" +
                        "2. Do not use flames, smoke, electrical switches, plugs or appliances.\n\n" +
                        "3. Do not use a phone until you are outside the suspected leak area.\n\n" +
                        "4. If safe, close the gas cylinder or valve without taking additional risk.\n\n" +
                        "5. Ventilate naturally if safe; do not switch on electrical fans.\n\n" +
                        "6. From outside, call emergency services or a qualified gas supplier.\n\n" +
                        "7. Do not re-enter until the area is declared safe.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Call 112", (d, w) -> {
                    Intent dial = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"));
                    startActivity(dial);
                })
                .show();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Gas alerts", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("High gas level alerts");
            channel.enableVibration(true);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void showDangerNotification(int raw) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pi = PendingIntent.getActivity(
                this, 100, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("DANGER: High gas level detected")
                .setContentText("Raw MQ-5 value: " + raw + ". Tap for safety instructions.")
                .setAutoCancel(true)
                .setContentIntent(pi);

        getSystemService(NotificationManager.class).notify(1001, b.build());
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 50);
        }
    }

    @Override protected void onDestroy() {
        running = false;
        executor.shutdownNow();
        super.onDestroy();
    }
}
