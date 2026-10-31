package za.ac.tut.smartgasalert;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class GasMonitorService extends Service {
    private static final String DEFAULT_DB = "https://gaslpg-54c74-default-rtdb.firebaseio.com";
    private static final String SERVICE_CHANNEL = "gas_monitor_service";
    private static final String ALERT_CHANNEL = "gas_alerts";
    private static final int SERVICE_ID = 20;
    private static final int ALERT_ID = 21;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String lastStatus = "";

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            executor.execute(() -> checkFirebase());
            handler.postDelayed(this, 5000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannels(this);
        startForeground(SERVICE_ID, serviceNotification());
        poll.run();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(poll);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent) { return null; }

    private void checkFirebase() {
        String base = getSharedPreferences("smartgas", MODE_PRIVATE).getString("database_url", DEFAULT_DB);
        if (base == null || base.trim().isEmpty()) base = DEFAULT_DB;
        while (base.endsWith("/")) base = base.substring(0, base.length()-1);
        try {
            HttpURLConnection c = (HttpURLConnection)new URL(base + "/gasMonitor/current.json").openConnection();
            c.setConnectTimeout(6000); c.setReadTimeout(6000); c.setRequestMethod("GET");
            if (c.getResponseCode() != 200) return;
            String json = readAll(c.getInputStream());
            if (json == null || json.trim().equals("null")) return;
            JSONObject o = new JSONObject(json);
            int raw = o.optInt("rawValue", -1);
            String status = o.optString("status", "");
            if (status.isEmpty()) status = classify(raw);
            status = status.toUpperCase();
            if ("DANGER".equals(status) && !"DANGER".equals(lastStatus)) {
                postDangerNotification(this, raw);
            }
            lastStatus = status;
        } catch (Exception ignored) {}
    }

    public static void postDangerNotification(Context ctx, int raw) {
        createChannels(ctx);
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(ctx, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("DANGER: High gas level detected")
                .setContentText("Gas alarm detected" + (raw >= 0 ? " (raw " + raw + ")" : "") + ". Tap for safety instructions.")
                .setStyle(new Notification.BigTextStyle().bigText("Dangerous gas condition detected. Leave the area, avoid flames and electrical switches, then tap for safety instructions."))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX);
        ((NotificationManager)ctx.getSystemService(NOTIFICATION_SERVICE)).notify(ALERT_ID, b.build());
    }

    private Notification serviceNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, SERVICE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Smart Gas monitoring active")
                .setContentText("Monitoring Firebase for gas status changes")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager)c.getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel monitor = new NotificationChannel(SERVICE_CHANNEL, "Gas monitor service", NotificationManager.IMPORTANCE_LOW);
            monitor.setDescription("Keeps Smart Gas monitoring active");
            nm.createNotificationChannel(monitor);
            NotificationChannel alert = new NotificationChannel(ALERT_CHANNEL, "Gas danger alerts", NotificationManager.IMPORTANCE_HIGH);
            alert.setDescription("High gas leak alerts");
            alert.enableVibration(true);
            alert.enableLights(true);
            alert.setLightColor(Color.RED);
            nm.createNotificationChannel(alert);
        }
    }

    private String classify(int raw) {
        if (raw < 0) return "UNKNOWN";
        if (raw <= 100) return "DANGER";
        if (raw <= 800) return "WARNING";
        return "NORMAL";
    }

    private String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) != -1) b.write(buf,0,n);
        return b.toString(StandardCharsets.UTF_8.name());
    }
}
