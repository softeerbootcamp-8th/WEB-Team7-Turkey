package com.example.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;

/** WebView와 독립적으로 GPS를 수집하고 백엔드에 직접 전송하는 Android Foreground Service. */
public class RiderLocationService extends Service implements LocationListener {

    static final String ACTION_START = "com.example.app.location.START";
    static final String ACTION_STOP = "com.example.app.location.STOP";
    static final String EXTRA_OPERATING_STATUS = "operatingStatus";
    static final String EXTRA_API_BASE_URL = "apiBaseUrl";

    private static final String TAG = "RiderLocationService";
    private static final String CHANNEL_ID = "rider_location_tracking";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PREFERENCES = "rider_location_service";
    private static final long AVAILABLE_INTERVAL_MS = 30_000L;
    private static final long BUSY_INTERVAL_MS = 5_000L;
    private static final long FORCE_SEND_AFTER_MS = 120_000L;
    private static final long MAX_FIX_AGE_MS = 60_000L;
    private static final float MAX_ACCURACY_METERS = 100F;
    private static final double MIN_MOVE_DISTANCE_METERS = 20D;
    private static final double MAX_SPEED_METERS_PER_SECOND = 50D;

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean sending = new AtomicBoolean(false);
    private LocationManager locationManager;
    private SharedPreferences preferences;
    private volatile String operatingStatus;
    private volatile String apiBaseUrl;
    private volatile long sendIntervalMs = AVAILABLE_INTERVAL_MS;
    private volatile SentLocation lastSent;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            preferences.edit().clear().apply();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_START.equals(intent.getAction())) {
            operatingStatus = intent.getStringExtra(EXTRA_OPERATING_STATUS);
            apiBaseUrl = normalizeBaseUrl(intent.getStringExtra(EXTRA_API_BASE_URL));
            preferences.edit()
                    .putString(EXTRA_OPERATING_STATUS, operatingStatus)
                    .putString(EXTRA_API_BASE_URL, apiBaseUrl)
                    .apply();
        } else {
            operatingStatus = preferences.getString(EXTRA_OPERATING_STATUS, null);
            apiBaseUrl = preferences.getString(EXTRA_API_BASE_URL, null);
        }

        if (!isConfigurationValid()) {
            Log.w(TAG, "Location service configuration is invalid");
            stopSelf();
            return START_NOT_STICKY;
        }

        sendIntervalMs = "BUSY".equals(operatingStatus) ? BUSY_INTERVAL_MS : AVAILABLE_INTERVAL_MS;
        Log.i(TAG, "Location service started endpoint=" + apiBaseUrl
                + "/api/rider/location status=" + operatingStatus);
        startForeground(NOTIFICATION_ID, buildNotification());
        startLocationUpdates();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        locationManager.removeUpdates(this);
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onLocationChanged(Location location) {
        if (!shouldSend(location) || !sending.compareAndSet(false, true)) {
            return;
        }

        networkExecutor.execute(() -> {
            try {
                if (postLocation(location)) {
                    lastSent = SentLocation.from(location);
                }
            } finally {
                sending.set(false);
            }
        });
    }

    @SuppressWarnings("MissingPermission")
    private void startLocationUpdates() {
        locationManager.removeUpdates(this);
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, sendIntervalMs, 0F, this, Looper.getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, sendIntervalMs, 0F, this, Looper.getMainLooper());
            }
        } catch (SecurityException error) {
            Log.w(TAG, "Location permission was revoked", error);
            preferences.edit().clear().apply();
            stopSelf();
        }
    }

    private boolean shouldSend(Location next) {
        long ageMs = System.currentTimeMillis() - next.getTime();
        if ((next.hasAccuracy() && next.getAccuracy() > MAX_ACCURACY_METERS)
                || ageMs > MAX_FIX_AGE_MS
                || ageMs < -5_000L) {
            return false;
        }

        SentLocation previous = lastSent;
        if (previous == null) {
            return true;
        }

        long elapsedMs = next.getTime() - previous.measuredAt;
        if (elapsedMs <= 0L) {
            return false;
        }
        float distance = previous.distanceTo(next);
        if (elapsedMs >= 1_000L && elapsedMs <= 30_000L
                && distance / (elapsedMs / 1_000D) > MAX_SPEED_METERS_PER_SECOND) {
            return false;
        }
        if (elapsedMs < sendIntervalMs) {
            return false;
        }
        return distance >= MIN_MOVE_DISTANCE_METERS || elapsedMs >= FORCE_SEND_AFTER_MS;
    }

    private boolean postLocation(Location location) {
        HttpURLConnection connection = null;
        try {
            String endpoint = apiBaseUrl + "/api/rider/location";
            String cookie = CookieManager.getInstance().getCookie(endpoint);

            byte[] body = createRequestBody(location).getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            if (cookie != null && !cookie.isBlank()) {
                connection.setRequestProperty("Cookie", cookie);
            }
            connection.setFixedLengthStreamingMode(body.length);
            Log.i(TAG, "POST " + endpoint + " sessionCookie="
                    + (cookie != null && cookie.contains("SESSION_ID=")));
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int status = connection.getResponseCode();
            Log.i(TAG, "Location POST response status=" + status);
            if (status == HttpURLConnection.HTTP_CONFLICT) {
                Log.w(TAG, "Location tracking stopped by server status=" + status);
                preferences.edit().clear().apply();
                stopSelf();
            }
            return status >= 200 && status < 300;
        } catch (IOException | JSONException error) {
            Log.w(TAG, "Failed to send rider location", error);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String createRequestBody(Location location) throws JSONException {
        JSONObject body = new JSONObject()
                .put("latitude", location.getLatitude())
                .put("longitude", location.getLongitude())
                .put("measuredAt", Instant.ofEpochMilli(location.getTime()).toString());
        if (location.hasAccuracy()) {
            body.put("accuracyMeters", location.getAccuracy());
        }
        return body.toString();
    }

    private Notification buildNotification() {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String intervalText = "BUSY".equals(operatingStatus) ? "5초" : "30초";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("라이더 위치 전송 중")
                .setContentText(intervalText + " 간격으로 배송 위치를 공유하고 있습니다.")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "라이더 위치 공유",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("운행 중 백그라운드 위치 전송 상태를 표시합니다.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private boolean isConfigurationValid() {
        return ("AVAILABLE".equals(operatingStatus) || "BUSY".equals(operatingStatus))
                && apiBaseUrl != null
                && (apiBaseUrl.startsWith("http://") || apiBaseUrl.startsWith("https://"));
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null) {
            return null;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record SentLocation(double latitude, double longitude, long measuredAt) {
        static SentLocation from(Location location) {
            return new SentLocation(location.getLatitude(), location.getLongitude(), location.getTime());
        }

        float distanceTo(Location location) {
            float[] result = new float[1];
            Location.distanceBetween(latitude, longitude, location.getLatitude(), location.getLongitude(), result);
            return result[0];
        }
    }
}
