package za.ac.tut.smartgasalert;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.database.*;

public class GasMonitorService extends Service {
    private static final String PROJECT_ID = "gaslpg-54c74";
    private static final String DATABASE_URL = "https://gaslpg-54c74-default-rtdb.firebaseio.com";
    private static final String APP_ID = "1:859259164362:android:3011bb9ff108a446f44633";
    private static final String API_KEY = "AIzaSyA-XkLCV2-NS7zyJwz3IGBuuJg8qCcpv_0";
    private static final String SENDER_ID = "859259164362";

    private static final String SERVICE_CHANNEL = "gas_monitor_service";
    private static final String ALERT_CHANNEL = "gas_alerts";
    private static final int SERVICE_ID = 20;
    private static final int ALERT_ID = 21;

    private DatabaseReference currentRef;
    private ValueEventListener listener;
    private String lastStatus = "";

    @Override public void onCreate() {
        super.onCreate();
        createChannels(this);
        startForeground(SERVICE_ID, serviceNotification());
        connectFirebase();
    }

    private void connectFirebase() {
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

            currentRef = FirebaseDatabase.getInstance(app)
                    .getReference("gasMonitor").child("current");

            listener = new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snapshot) {
                    long raw = parseLong(snapshot.child("rawValue").getValue(), -1);
                    if (raw < 0) raw = parseLong(snapshot.child("adc").getValue(), -1);
                    if (raw < 0) raw = parseLong(snapshot.child("value").getValue(), -1);

                    String status = classify(raw);
                    if ("DANGER".equals(status) && !"DANGER".equals(lastStatus)) {
                        postDangerNotification(GasMonitorService.this, (int)raw);
                    }
                    lastStatus = status;
                }

                @Override public void onCancelled(DatabaseError error) { }
            };
            currentRef.addValueEventListener(listener);
        } catch (Exception ignored) { }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (currentRef != null && listener != null) currentRef.removeEventListener(listener);
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent) { return null; }

    private String classify(long raw) {
        if (raw < 0) return "UNKNOWN";
        if (raw < 110) return "SAFE";
        if (raw <= 650) return "WARNING";
        return "DANGER";
    }

    private long parseLong(Object obj, long fallback) {
        if (obj == null) return fallback;
        if (obj instanceof Number) return ((Number)obj).longValue();
        try { return Long.parseLong(String.valueOf(obj).trim()); }
        catch (Exception e) { return fallback; }
    }

    public static void postDangerNotification(Context ctx, int raw) {
        createChannels(ctx);
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(ctx, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("DANGER: Gas level 651+ ADC")
                .setContentText("Current ADC: " + raw + ". Buzzer state: ON.")
                .setStyle(new Notification.BigTextStyle().bigText(
                        "Dangerous gas threshold reached. Current ADC: " + raw +
                        ". Leave the area, avoid flames and electrical switches, and follow the safety instructions."))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX);

        ((NotificationManager)ctx.getSystemService(NOTIFICATION_SERVICE))
                .notify(ALERT_ID, b.build());
    }

    private Notification serviceNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, SERVICE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Smart Gas monitoring active")
                .setContentText("SAFE <110 • WARNING 110–650 • DANGER 651+")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm =
                    (NotificationManager)c.getSystemService(NOTIFICATION_SERVICE);

            NotificationChannel monitor = new NotificationChannel(
                    SERVICE_CHANNEL, "Gas monitor service",
                    NotificationManager.IMPORTANCE_LOW);
            monitor.setDescription("Keeps Smart Gas Firebase monitoring active");
            nm.createNotificationChannel(monitor);

            NotificationChannel alert = new NotificationChannel(
                    ALERT_CHANNEL, "Gas danger alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            alert.setDescription("Alerts when ADC is 651 or higher");
            alert.enableVibration(true);
            alert.enableLights(true);
            alert.setLightColor(Color.RED);
            nm.createNotificationChannel(alert);
        }
    }
}
