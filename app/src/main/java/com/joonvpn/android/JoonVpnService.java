package com.joonvpn.android;

import static com.joonvpn.android.MainActivity.globalConnectionStartTime;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import libv2ray.CoreController;
import libv2ray.Libv2ray;

public class JoonVpnService extends VpnService {

    public static final String BROADCAST_VPN_STATE = "com.joonvpn.android.STATE_CHANGED";
    public static final String ACTION_DISCONNECT = "com.joonvpn.android.ACTION_DISCONNECT";
    public static final String ACTION_RESTART = "com.joonvpn.android.ACTION_RESTART";
    public static final String EXTRA_STATUS = "status";

    private static final String TAG = "JoonVpnService";
    private static final String CHANNEL_ID = "VpnMetricsChannel";
    private static final int NOTIFICATION_ID = 8844;

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler backgroundSyncHandler = new Handler(Looper.getMainLooper());
    private ParcelFileDescriptor vpnInterface = null;
    private CoreController xrayController = null;
    private boolean isUserStopped = false;
    private long totalDownloadedBytes = 0;
    private long totalUploadedBytes = 0;
    private long baseDownloadedBytes = 0;
    private long baseUploadedBytes = 0;
    private long lastRawRx = 0;
    private long lastRawTx = 0;
    private boolean isRegisteredNotificationReceiver = false;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean isNetworkAvailable = true;
    private long lastSavedRx = 0;
    private long lastSavedTx = 0;
    private long lastSavedTimestamp = 0;

    private final BroadcastReceiver notificationActionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                String action = intent.getAction();
                if (ACTION_DISCONNECT.equals(action)) {
                    isUserStopped = true;
                    cleanStopVpnAndXray();
                    stopSelf();
                } else if (ACTION_RESTART.equals(action)) {
                    isUserStopped = false;
                    executorService.execute(() -> performHardRestart());
                }
            }
        }
    };
    private final Runnable backgroundSyncRunnable = new Runnable() {
        @Override
        public void run() {
            if (vpnInterface != null) {
                syncLiveStatsToStorage();
                backgroundSyncHandler.postDelayed(this, 10000);
            }
        }
    };

    public static native boolean TProxyStartService(String configPath, int fd);

    public static native boolean TProxyStopService();

    public static native long[] TProxyGetStats();

    public static native boolean TProxyIsRunning();

    public ParcelFileDescriptor getVpnInterface() {
        return vpnInterface;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            if (isUserStopped) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.connecting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.connecting)));
            }
            executorService.execute(this::startVpnAndXray);
            return START_STICKY;
        }

        String action = intent.getAction();
        if ("ACTION_STOP".equals(action)) {
            isUserStopped = true;
            cleanStopVpnAndXray();
            stopSelf();
            return START_NOT_STICKY;
        }

        isUserStopped = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.connecting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.connecting)));
        }
        executorService.execute(this::startVpnAndXray);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (VpnService.SERVICE_INTERFACE.equals(action)) {
            return super.onBind(intent);
        }
        return new MainActivity.JoonVpnServiceBinder(this);
    }

    private void sendStateBroadcast(String status) {
        Intent intent = new Intent(BROADCAST_VPN_STATE);
        intent.putExtra(EXTRA_STATUS, status);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private Notification buildNotification(String status) {
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

        Intent disconnectIntent = new Intent(ACTION_DISCONNECT).setPackage(getPackageName());
        PendingIntent disconnectPendingIntent = PendingIntent.getBroadcast(this, (int) System.currentTimeMillis(), disconnectIntent, flags);

        Intent restartIntent = new Intent(ACTION_RESTART).setPackage(getPackageName());
        PendingIntent restartPendingIntent = PendingIntent.getBroadcast(this, (int) System.currentTimeMillis() + 1, restartIntent, flags);

        String title;
        String text;
        int icon;
        boolean showActions;

        if ("CONNECTING".equals(status)) {
            title = getString(R.string.connecting);
            text = getString(R.string.wait_for_connection);
            icon = android.R.drawable.ic_popup_sync;
            showActions = false;
        } else if ("CONNECTED_FAILED".equals(status)) {
            title = getString(R.string.connection_failed_title);
            text = getString(R.string.connection_failed_description);
            icon = android.R.drawable.ic_delete;
            showActions = true;
        } else {
            title = getString(R.string.connected);
            text = getString(R.string.change_server_description);
            icon = R.drawable.joon_ic;
            showActions = true;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(icon)
                .setContentIntent(pendingIntent)
                .setOngoing(!"CONNECTED_FAILED".equals(status))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setShowWhen(false);

        if (showActions) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.disconnect), disconnectPendingIntent);
            builder.addAction(android.R.drawable.ic_menu_rotate, getString(R.string.change_server_title), restartPendingIntent);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "VPN Status & Actions",
                    NotificationManager.IMPORTANCE_DEFAULT
            );

            serviceChannel.setSound(null, null);
            serviceChannel.enableVibration(false);
            serviceChannel.enableLights(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onRevoke() {
        isUserStopped = true;
        stopSelf();
        super.onRevoke();
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE";
        char prefix = (exp - 1 < pre.length()) ? pre.charAt(exp - 1) : 'E';
        return String.format(Locale.US, "%.1f %cB", bytes / Math.pow(1024, exp), prefix);
    }

    private void copyAssetsToFilesDir() {
        String[] datFiles = {"geoip.dat", "geosite.dat"};
        File targetDir = getFilesDir();

        for (String fileName : datFiles) {
            File targetFile = new File(targetDir, fileName);
            if (targetFile.exists() && targetFile.length() > 0) {
                continue;
            }

            try (java.io.InputStream in = getAssets().open(fileName);
                 FileOutputStream out = new FileOutputStream(targetFile)) {

                byte[] buffer = new byte[1024 * 4];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } catch (IOException e) {
                Log.e(TAG, "Failed to extract core asset files: " + fileName, e);
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_DISCONNECT);
        filter.addAction(ACTION_RESTART);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationActionReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(notificationActionReceiver, filter);
        }

        isRegisteredNotificationReceiver = true;
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    super.onAvailable(network);
                    isNetworkAvailable = true;
                }

                @Override
                public void onLost(@NonNull Network network) {
                    super.onLost(network);
                    isNetworkAvailable = false;
                }
            };
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        }


    }

    public synchronized String getFormattedTrafficMetrics() {
        if (xrayController == null || !isNetworkAvailable) return "0 B;0 B;0 B";
        try {
            String statsStr = xrayController.queryAllOutboundTrafficStats();
            if (statsStr == null || statsStr.trim().isEmpty()) {
                return formatBytes(totalDownloadedBytes) + ";" + formatBytes(totalUploadedBytes) + ";" + formatBytes(totalDownloadedBytes + totalUploadedBytes);
            }
            long currentTotalRx = 0;
            long currentTotalTx = 0;
            String[] parts = statsStr.split(";");
            for (String part : parts) {
                if (part == null || part.trim().isEmpty()) continue;
                String[] item = part.split(",");
                if (item.length == 3) {
                    String metricType = item[1].toLowerCase(Locale.US);
                    long bytes = Long.parseLong(item[2]);
                    if (metricType.contains("downlink")) {
                        currentTotalRx += bytes;
                    } else if (metricType.contains("uplink")) {
                        currentTotalTx += bytes;
                    }
                }
            }
            if (currentTotalRx < lastRawRx && currentTotalRx > 0) {
                baseDownloadedBytes += lastRawRx;
            }
            if (currentTotalTx < lastRawTx && currentTotalTx > 0) {
                baseUploadedBytes += lastRawTx;
            }
            if (currentTotalRx > 0) lastRawRx = currentTotalRx;
            if (currentTotalTx > 0) lastRawTx = currentTotalTx;
            totalDownloadedBytes = baseDownloadedBytes + lastRawRx;
            totalUploadedBytes = baseUploadedBytes + lastRawTx;
            syncLiveStatsToStorage();
            return formatBytes(totalDownloadedBytes) + ";" + formatBytes(totalUploadedBytes) + ";" + formatBytes(totalDownloadedBytes + totalUploadedBytes);
        } catch (Exception e) {
            Log.e(TAG, "Traffic metrics native string parsing exception", e);
            return formatBytes(totalDownloadedBytes) + ";" + formatBytes(totalUploadedBytes) + ";" + formatBytes(totalDownloadedBytes + totalUploadedBytes);
        }
    }

    private synchronized void startVpnAndXray() {
        if (vpnInterface != null) return;
        globalConnectionStartTime = System.currentTimeMillis();
        updateNotification("CONNECTING");

        if (xrayController != null) {
            try {
                xrayController.stopLoop();
            } catch (Exception ignored) {
            }
            xrayController = null;
        }

        try {
            copyAssetsToFilesDir();
            Libv2ray.initCoreEnv(getFilesDir().getAbsolutePath(), getOrCreateBaseKey());
            xrayController = Libv2ray.newCoreController(new libv2ray.CoreCallbackHandler() {
                @Override
                public long onEmitStatus(long level, String msg) {
                    return 0;
                }

                @Override
                public long shutdown() {
                    return 0;
                }

                @Override
                public long startup() {
                    return 0;
                }
            });
            if (xrayController != null) {
                ConfigManager configManager = new ConfigManager(this);
                String dynamicXrayConfig = configManager.generateTargetXrayConfig();
                if (dynamicXrayConfig == null) {
                    sendStateBroadcast("CONNECTED_FAILED");
                    updateNotification("CONNECTED_FAILED");
                    return;
                }
                xrayController.startLoop(dynamicXrayConfig, -1);
            }
        } catch (Exception e) {
            Log.e(TAG, "Xray loop execution error", e);
            sendStateBroadcast("CONNECTED_FAILED");
            updateNotification("CONNECTED_FAILED");
            return;
        }

        File configFile = new File(getCacheDir(), "tun_config.conf");
        writeTunnelConfigFile(configFile);
        Builder builder = new Builder();
        try {
            builder.setMtu(1400)
                    .addAddress("10.0.0.1", 32)
                    .addRoute("0.0.0.0", 0)
                    .addAddress("fc00::1", 128)
                    .addRoute("::", 0)
                    .addDnsServer("10.0.0.2")
                    .addDnsServer("fc00::2")
                    .addDisallowedApplication(getPackageName());
        } catch (Exception e) {
            Log.e(TAG, "Error configuring VPN interface builders", e);
            sendStateBroadcast("CONNECTED_FAILED");
            updateNotification("CONNECTED_FAILED");
            return;
        }
        String[] appsToBypass = getResources().getStringArray(R.array.apps_to_bypass);
        PackageManager pm = getPackageManager();
        for (String pkg : appsToBypass) {
            try {
                pm.getPackageInfo(pkg, 0);
                builder.addDisallowedApplication(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        vpnInterface = builder.establish();
        if (vpnInterface != null) {
            TProxyStartService(configFile.getAbsolutePath(), vpnInterface.getFd());
            sendStateBroadcast("CONNECTED");
            updateNotification("CONNECTED");
            backgroundSyncHandler.post(backgroundSyncRunnable);
        } else {
            sendStateBroadcast("CONNECTED_FAILED");
            updateNotification("CONNECTED_FAILED");
        }
    }

    private void writeTunnelConfigFile(File file) {
        String configContent = """
                main:
                  workers: 4
                  mtu: 1400
                  listen-address: '::'
                socks5:
                  address: '::'
                  port: 10801
                  udp: 'udp'
                  pipeline: true
                tcp:
                  keepalive: 15
                udp:
                  mode: 'udp'
                  timeout: 10
                  max-sessions: 2048
                """;
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(configContent.getBytes());
        } catch (IOException e) {
            Log.e(TAG, "Config write error", e);
        }
    }

    @Override
    public void onDestroy() {
        cleanStopVpnAndXray();
        executorService.shutdownNow();
        super.onDestroy();
    }

    private void cleanStopVpnAndXray() {
        backgroundSyncHandler.removeCallbacks(backgroundSyncRunnable);
        syncLiveStatsToStorage();
        if (isRegisteredNotificationReceiver) {
            try {
                unregisterReceiver(notificationActionReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            isRegisteredNotificationReceiver = false;
        }
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }
        TProxyStopService();
        if (xrayController != null) {
            try {
                xrayController.stopLoop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping xray loop", e);
            }
            xrayController = null;
        }
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing vpnInterface", e);
            }
            vpnInterface = null;
        }
        totalDownloadedBytes = 0;
        totalUploadedBytes = 0;
        baseDownloadedBytes = 0;
        baseUploadedBytes = 0;
        lastRawRx = 0;
        lastRawTx = 0;
        lastSavedRx = 0;
        lastSavedTx = 0;
        lastSavedTimestamp = 0;
        globalConnectionStartTime = 0;
        sendStateBroadcast("DISCONNECTED");
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        if (fgsType == ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) {
            executorService.execute(() -> {
                cleanStopVpnAndXray();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                startVpnAndXray();
            });
        } else {
            super.onTimeout(startId, fgsType);
        }
    }

    private synchronized void syncLiveStatsToStorage() {
        if (globalConnectionStartTime <= 0 || vpnInterface == null) return;

        long currentTimestamp = System.currentTimeMillis();
        if (lastSavedTimestamp == 0) {
            lastSavedTimestamp = globalConnectionStartTime;
        }
        long elapsedSeconds = (currentTimestamp - lastSavedTimestamp) / 1000;

        long snapshotRx = totalDownloadedBytes;
        long snapshotTx = totalUploadedBytes;

        long newBytesRx = snapshotRx - lastSavedRx;
        long newBytesTx = snapshotTx - lastSavedTx;

        if (newBytesRx < 0 || newBytesTx < 0 || (elapsedSeconds < 1 && newBytesRx == 0 && newBytesTx == 0)) {
            lastSavedRx = snapshotRx;
            lastSavedTx = snapshotTx;
            lastSavedTimestamp = currentTimestamp;
            return;
        }

        SharedPreferences statsPrefs = getSharedPreferences("VpnStatsPrefs", Context.MODE_PRIVATE);
        String todayKey = new SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());

        long currentDayDuration = statsPrefs.getLong(todayKey + "_duration", 0);
        long currentDayDownload = statsPrefs.getLong(todayKey + "_download", 0);
        long currentDayUpload = statsPrefs.getLong(todayKey + "_upload", 0);

        statsPrefs.edit()
                .putLong(todayKey + "_duration", currentDayDuration + elapsedSeconds)
                .putLong(todayKey + "_download", currentDayDownload + newBytesRx)
                .putLong(todayKey + "_upload", currentDayUpload + newBytesTx)
                .apply();

        lastSavedRx = snapshotRx;
        lastSavedTx = snapshotTx;
        lastSavedTimestamp = currentTimestamp;
    }

    private void updateNotification(String status) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(status));
        }
    }

    private synchronized void performHardRestart() {
        backgroundSyncHandler.removeCallbacks(backgroundSyncRunnable);
        TProxyStopService();

        if (xrayController != null) {
            try {
                xrayController.stopLoop();
            } catch (Exception ignored) {
            }
            xrayController = null;
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException ignored) {
            }
            vpnInterface = null;
        }

        try {
            Thread.sleep(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        startVpnAndXray();
    }

    public String getOrCreateBaseKey() {
        String PREF_NAME = "xray_prefs";
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String KEY_BASE_KEY = "xudp_base_key";
        String savedKey = prefs.getString(KEY_BASE_KEY, null);

        if (savedKey != null) {
            return savedKey;
        }

        byte[] rawBytes = new byte[32];
        new SecureRandom().nextBytes(rawBytes);
        String newKey = Base64.encodeToString(rawBytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);

        prefs.edit().putString(KEY_BASE_KEY, newKey).apply();
        return newKey;
    }

}