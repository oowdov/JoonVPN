package com.joonvpn.android;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import libv2ray.CoreController;
import libv2ray.Libv2ray;

@SuppressLint("VpnServicePolicy")
public class JoonVpnService extends VpnService {
    public static final String BROADCAST_VPN_STATE = "com.joonvpn.android.STATE_CHANGED";
    public static final String ACTION_DISCONNECT = "com.joonvpn.android.ACTION_DISCONNECT";
    public static final String ACTION_RESTART = "com.joonvpn.android.ACTION_RESTART";
    private static final String ACTION_START = "ACTION_START";
    public static final String EXTRA_STATUS = "status";
    private static final String TAG = "JoonVpnService";
    private static final String CHANNEL_ID = "VpnMetricsChannel";
    private static final int NOTIFICATION_ID = 8844;
    private static final int VPN_MTU = 1400;
    private static final String HOST_ADDRESS = "0.0.0.0";
    private static final int SOCKS_PORT = 1819;
    private static final long STATS_SYNC_INTERVAL = 10_000L;
    private static final String STATS_PREFS = "VpnStatsPrefs";
    private static final String XRAY_PREFS = "xray_prefs";
    private static final String XRAY_BASE_KEY = "xudp_base_key";
    private static final String STATUS_CONNECTING = "CONNECTING";
    private static final String STATUS_CONNECTED = "CONNECTED";
    private static final String STATUS_CONNECTED_FAILED = "CONNECTED_FAILED";
    private static final String STATUS_DISCONNECTED = "DISCONNECTED";

    private enum VpnState {STOPPED, STARTING, CONNECTED, RESTARTING, STOPPING, FAILED}

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public static native boolean TProxyStartService(String configPath, int fd);

    public static native boolean TProxyStopService();

    public static native long[] TProxyGetStats();

    public static native boolean TProxyIsRunning();

    private final ExecutorService vpnExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "JoonVpn-Worker");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean destroyRequested = new AtomicBoolean(false);
    private volatile boolean isUserStopped = false;
    private volatile boolean isNetworkAvailable = false;
    private volatile VpnState vpnState = VpnState.STOPPED;
    private volatile String currentStatus = STATUS_DISCONNECTED;
    private ParcelFileDescriptor vpnInterface;
    private CoreController xrayController;
    private boolean isHevRunning = false;
    private boolean isVpnReceiverRegistered = false;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private long totalDownloadedBytes = 0L;
    private long totalUploadedBytes = 0L;
    private long baseDownloadedBytes = 0L;
    private long baseUploadedBytes = 0L;
    private long lastRawRx = 0L;
    private long lastRawTx = 0L;
    private long lastSavedRx = 0L;
    private long lastSavedTx = 0L;
    private long lastSavedTimestamp = 0L;
    private long globalConnectionStartTime = 0L;
    private final Object statsLock = new Object();
    private final Runnable backgroundSyncRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyRequested.get()) {
                return;
            }
            if (vpnState == VpnState.CONNECTED) {
                vpnExecutor.execute(() -> {
                    updateTrafficMetricsInternal();
                    syncLiveStatsToStorage();
                });
                mainHandler.postDelayed(this, STATS_SYNC_INTERVAL);
            }
        }
    };
    private final BroadcastReceiver notificationActionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || destroyRequested.get()) {
                return;
            }
            String action = intent.getAction();
            if (ACTION_DISCONNECT.equals(action)) {
                isUserStopped = true;
                enqueueVpnOperation(() -> {
                    stopVpnInternal(true);
                    stopSelf();
                });
            } else if (ACTION_RESTART.equals(action)) {
                isUserStopped = false;
                enqueueVpnOperation(JoonVpnService.this::restartXrayInternal);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        registerNotificationReceiver();
        initializeNetworkMonitoring();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "onStartCommand: action=" + action + ",isUserStopped=" + isUserStopped);
        if ("ACTION_STOP".equals(action) || ACTION_DISCONNECT.equals(action)) {
            isUserStopped = true;
            enqueueVpnOperation(() -> {
                stopVpnInternal(true);
                stopSelfResult(startId);
            });
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            isUserStopped = false;
        } else if (isUserStopped) {
            Log.w(TAG, "Ignoring automatic restart: user stopped VPN");
            return START_NOT_STICKY;
        }
        try {
            startVpnForeground();
        } catch (Exception e) {
            Log.e(TAG, "Unable to start foreground service", e);
            isUserStopped = true;
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        enqueueVpnOperation(() -> {
            if (isUserStopped || destroyRequested.get()) {
                Log.w(TAG, "VPN startup cancelled");
                return;
            }
            if (vpnState == VpnState.CONNECTED && vpnInterface != null && isHevRunning) {
                return;
            }
            Log.i(TAG, "Starting VPN connection...");
            startVpnInternal();
        });
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

    @Override
    public void onRevoke() {
        isUserStopped = true;
        enqueueVpnOperation(() -> {
            stopVpnInternal(true);
            stopSelf();
        });
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        destroyRequested.set(true);
        isUserStopped = true;
        mainHandler.removeCallbacks(backgroundSyncRunnable);
        try {
            vpnExecutor.execute(() -> {
                stopVpnInternal(true);
                unregisterServiceCallbacks();
            });
            vpnExecutor.shutdown();
        } catch (Exception e) {
            Log.e(TAG, "Service destruction error", e);
        }
        super.onDestroy();
    }

    private void startVpnForeground() {
        Notification notification = buildNotification(STATUS_CONNECTING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "VPN Status & Actions", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String status) {
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent activityPendingIntent = PendingIntent.getActivity(this, 0, activityIntent, flags);
        Intent disconnectIntent = new Intent(ACTION_DISCONNECT).setPackage(getPackageName());
        PendingIntent disconnectPendingIntent = PendingIntent.getBroadcast(this, 8845, disconnectIntent, flags);
        Intent restartIntent = new Intent(ACTION_RESTART).setPackage(getPackageName());
        PendingIntent restartPendingIntent = PendingIntent.getBroadcast(this, 8846, restartIntent, flags);
        boolean connecting = STATUS_CONNECTING.equals(status);
        boolean failed = STATUS_CONNECTED_FAILED.equals(status);
        String title;
        String description;
        int icon;
        if (connecting) {
            title = getString(R.string.connecting);
            description = getString(R.string.wait_for_connection);
            icon = android.R.drawable.ic_popup_sync;
        } else if (failed) {
            title = getString(R.string.connection_failed_title);
            description = getString(R.string.connection_failed_description);
            icon = android.R.drawable.ic_delete;
        } else {
            title = getString(R.string.connected);
            description = getString(R.string.change_server_description);
            icon = R.drawable.joon_ic;
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(title).setContentText(description).setSmallIcon(icon).setContentIntent(activityPendingIntent).setOngoing(!failed).setPriority(NotificationCompat.PRIORITY_DEFAULT).setShowWhen(false);
        if (!connecting) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.disconnect), disconnectPendingIntent);
            builder.addAction(android.R.drawable.ic_menu_rotate, getString(R.string.change_server_title), restartPendingIntent);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void updateNotification(String status) {
        currentStatus = status;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(status));
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerNotificationReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_DISCONNECT);
        filter.addAction(ACTION_RESTART);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(notificationActionReceiver, filter);
        }
        isVpnReceiverRegistered = true;
    }

    private void sendStateBroadcast(String status) {
        Intent intent = new Intent(BROADCAST_VPN_STATE);
        intent.putExtra(EXTRA_STATUS, status);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void setVpnState(VpnState state, String status) {
        vpnState = state;
        updateNotification(status);
        sendStateBroadcast(status);
    }

    private void initializeNetworkMonitoring() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            isNetworkAvailable = false;
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                isNetworkAvailable = true;
            }

            @Override
            public void onLost(@NonNull Network network) {
                Network activeNetwork = connectivityManager.getActiveNetwork();
                isNetworkAvailable = activeNetwork != null;
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
                isNetworkAvailable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
            Network activeNetwork = connectivityManager.getActiveNetwork();
            isNetworkAvailable = activeNetwork != null;
        } catch (Exception e) {
            Log.e(TAG, "Network callback registration failed", e);
            isNetworkAvailable = false;
        }
    }

    private void unregisterServiceCallbacks() {
        if (isVpnReceiverRegistered) {
            try {
                unregisterReceiver(notificationActionReceiver);
            } catch (Exception ignored) {
            }
            isVpnReceiverRegistered = false;
        }
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
    }

    private void enqueueVpnOperation(Runnable operation) {
        if (destroyRequested.get()) {
            return;
        }
        try {
            vpnExecutor.execute(() -> {
                if (destroyRequested.get()) {
                    return;
                }
                try {
                    operation.run();
                } catch (Exception e) {
                    Log.e(TAG, "VPN operation failed", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Unable to enqueue VPN operation", e);
        }
    }

    private void startVpnInternal() {
        if (destroyRequested.get() || isUserStopped) {
            return;
        }
        if (vpnInterface != null || isHevRunning) {
            stopVpnInternal(false);
        }
        vpnState = VpnState.STARTING;
        globalConnectionStartTime = System.currentTimeMillis();
        resetTrafficStats();
        updateNotification(STATUS_CONNECTING);
        try {
            copyAssetsToFilesDir();
            Libv2ray.initCoreEnv(getFilesDir().getAbsolutePath(), getOrCreateBaseKey());
            if (!startXrayInternal()) {
                throw new IllegalStateException("Xray initialization failed");
            }
            File configFile = new File(getCacheDir(), "tun_config.conf");
            writeTunnelConfigFile(configFile);
            Builder builder = new Builder();
            builder.setMtu(VPN_MTU)
                    .addAddress("10.0.0.1", 32)
                    .addAddress("fc00::1", 128)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer("10.0.0.2")
                    .addDnsServer("fc00::2")
                    .addDisallowedApplication(getPackageName());
            String[] appsToBypass = getResources().getStringArray(R.array.apps_to_bypass);
            PackageManager packageManager = getPackageManager();
            for (String packageName : appsToBypass) {
                try {
                    packageManager.getPackageInfo(packageName, 0);
                    builder.addDisallowedApplication(packageName);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            if (isUserStopped || destroyRequested.get()) {
                throw new IllegalStateException("VPN startup cancelled");
            }
            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                throw new IllegalStateException("VPN interface establishment failed");
            }
            if (!startHevInternal(configFile)) {
                throw new IllegalStateException("HEV tunnel startup failed");
            }
            if (isUserStopped || destroyRequested.get()) {
                throw new IllegalStateException("VPN startup cancelled");
            }
            vpnState = VpnState.CONNECTED;
            setVpnState(VpnState.CONNECTED, STATUS_CONNECTED);
            mainHandler.removeCallbacks(backgroundSyncRunnable);
            mainHandler.post(backgroundSyncRunnable);
        } catch (Exception e) {
            Log.e(TAG, "VPN startup failed", e);
            stopVpnInternal(false);
            vpnState = VpnState.FAILED;
            setVpnState(VpnState.FAILED, STATUS_CONNECTED_FAILED);
        }
    }

    private boolean startXrayInternal() {
        try {
            if (xrayController == null) {
                xrayController = Libv2ray.newCoreController(new libv2ray.CoreCallbackHandler() {
                    @Override
                    public long onEmitStatus(long level, String msg) {
                        Log.d(TAG, "Xray: " + msg);
                        return 0;
                    }

                    @Override
                    public long shutdown() {
                        Log.d(TAG, "Xray shutdown callback");
                        return 0;
                    }

                    @Override
                    public long startup() {
                        Log.d(TAG, "Xray startup callback");
                        return 0;
                    }
                });
            }
            if (xrayController == null) {
                Log.e(TAG, "Unable to create Xray controller");
                return false;
            }
            ConfigManager configManager = new ConfigManager(this);
            String config = configManager.generateTargetXrayConfig();
            if (config == null || config.trim().isEmpty()) {
                Log.e(TAG, "Generated Xray config is empty");
                return false;
            }
            xrayController.startLoop(config, -1);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Xray startup failed", e);
            return false;
        }
    }

    private void stopXrayInternal() {
        if (xrayController == null) {
            return;
        }
        try {
            xrayController.stopLoop();
        } catch (Exception e) {
            Log.e(TAG, "Xray shutdown failed", e);
        } finally {
            xrayController = null;
        }
    }

    private void restartXrayInternal() {
        if (destroyRequested.get() || isUserStopped) {
            return;
        }
        if (vpnInterface == null || !isHevRunning) {
            Log.w(TAG, "Cannot restart Xray: VPN infrastructure is unavailable");
            if (vpnState == VpnState.STOPPED || vpnState == VpnState.FAILED) {
                startVpnInternal();
            }
            return;
        }
        vpnState = VpnState.RESTARTING;
        updateNotification(STATUS_CONNECTING);
        try {
            syncLiveStatsToStorage();
            updateTrafficMetricsInternal();
            stopXrayInternal();
            if (isUserStopped || destroyRequested.get()) {
                return;
            }
            if (!startXrayInternal()) {
                throw new IllegalStateException("Unable to restart Xray");
            }
            if (!TProxyIsRunning()) {
                throw new IllegalStateException("HEV tunnel stopped during Xray restart");
            }
            vpnState = VpnState.CONNECTED;
            setVpnState(VpnState.CONNECTED, STATUS_CONNECTED);
            Log.i(TAG, "Xray restarted successfully;TUN and HEV preserved");
        } catch (Exception e) {
            Log.e(TAG, "Xray restart failed", e);
            stopXrayInternal();
            vpnState = VpnState.FAILED;
            setVpnState(VpnState.FAILED, STATUS_CONNECTED_FAILED);
        }
    }

    private boolean startHevInternal(File configFile) {
        Log.e(TAG, "HEV BEFORE START: " + TProxyIsRunning());
        if (vpnInterface == null) {
            Log.e(TAG, "Cannot start HEV without VPN interface");
            return false;
        }
        if (configFile == null || !configFile.exists() || configFile.length() == 0) {
            Log.e(TAG, "Invalid HEV configuration file");
            return false;
        }
        try {
            if (TProxyIsRunning()) {
                isHevRunning = true;
                Log.i(TAG, "HEV is already running");
                return true;
            }
            int fd = vpnInterface.getFd();
            if (fd < 0) {
                Log.e(TAG, "Invalid VPN file descriptor");
                return false;
            }
            boolean started = TProxyStartService(configFile.getAbsolutePath(), fd);
            if (!started) {
                Log.e(TAG, "TProxyStartService returned false");
                return false;
            }
            isHevRunning = true;
            Log.i(TAG, "HEV started successfully");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "HEV startup failed", e);
            return false;
        }
    }


    private boolean stopHevInternal() {
        try {
            Log.e(TAG, "HEV BEFORE STOP: " + TProxyIsRunning());
            if (!TProxyIsRunning()) {
                isHevRunning = false;
                return true;
            }
            boolean stopped = TProxyStopService();
            Log.e(TAG, "HEV AFTER STOP: " + TProxyIsRunning());
            if (!stopped) {
                Log.e(TAG, "HEV stop returned false");
                isHevRunning = TProxyIsRunning();
                return false;
            }
            boolean stillRunning = TProxyIsRunning();
            if (stillRunning) {
                Log.e(TAG, "HEV is still running after stop request");
                isHevRunning = true;
                return false;
            }
            isHevRunning = false;
            Log.i(TAG, "HEV stopped successfully");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "HEV shutdown failed", e);
            isHevRunning = TProxyIsRunning();
            return false;
        }
    }


    private void stopVpnInternal(boolean resetStatistics) {
        if (vpnState == VpnState.STOPPED && vpnInterface == null && xrayController == null && !isHevRunning) {
            if (resetStatistics) {
                resetTrafficStats();
            }
            return;
        }
        vpnState = VpnState.STOPPING;
        mainHandler.removeCallbacks(backgroundSyncRunnable);
        updateTrafficMetricsInternal();
        syncLiveStatsToStorage();
        stopHevInternal();
        stopXrayInternal();
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "VPN interface close failed", e);
            } finally {
                vpnInterface = null;
            }
        }
        if (resetStatistics) {
            resetTrafficStats();
        }
        globalConnectionStartTime = 0L;
        vpnState = VpnState.STOPPED;
        setVpnState(VpnState.STOPPED, STATUS_DISCONNECTED);
        Log.i(TAG, "VPN stopped");
    }

    private void writeTunnelConfigFile(File file) throws IOException {
        String configContent = String.format(java.util.Locale.ROOT,
                """
                        tunnel:
                          mtu: %d
                          ipv4: '198.18.0.1'
                          ipv6: 'fc00::1'
                          icmp: 'off'
                        
                        socks5:
                          address: '%s'
                          port: %d
                          udp: 'udp'
                          pipeline: true
                        
                        misc:
                          task-stack-size: 86016
                          tcp-buffer-size: 65536
                          udp-recv-buffer-size: 524288
                          udp-copy-buffer-nums: 10
                          max-session-count: 2048
                          connect-timeout: 10000
                          tcp-read-write-timeout: 300000
                          udp-read-write-timeout: 60000
                          log-level: 'warn'
                        """,
                VPN_MTU,
                HOST_ADDRESS,
                SOCKS_PORT);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create HEV config directory");
        }
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(configContent.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    public String getFormattedTrafficMetrics() {
        synchronized (statsLock) {
            if (xrayController == null || !isNetworkAvailable) {
                return formatTrafficResult(totalDownloadedBytes, totalUploadedBytes);
            }
            updateTrafficMetricsInternal();
            return formatTrafficResult(totalDownloadedBytes, totalUploadedBytes);
        }
    }

    private String formatTrafficResult(long downloaded, long uploaded) {
        return formatBytes(downloaded) + ";" + formatBytes(uploaded) + ";" + formatBytes(downloaded + uploaded);
    }

    private void updateTrafficMetricsInternal() {
        if (xrayController == null) {
            return;
        }
        try {
            String stats = xrayController.queryAllOutboundTrafficStats();
            if (stats == null || stats.isEmpty()) {
                return;
            }
            long currentRx = 0L;
            long currentTx = 0L;
            String[] entries = stats.split(";");
            for (String entry : entries) {
                if (entry == null || entry.isEmpty()) {
                    continue;
                }
                String[] values = entry.split(",");
                if (values.length != 3) {
                    continue;
                }
                try {
                    String type = values[1].trim().toLowerCase(Locale.US);
                    long bytes = Long.parseLong(values[2].trim());
                    if (bytes < 0) {
                        continue;
                    }
                    if (type.contains("downlink")) {
                        currentRx += bytes;
                    } else if (type.contains("uplink")) {
                        currentTx += bytes;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            synchronized (statsLock) {
                if (currentRx < lastRawRx) {
                    baseDownloadedBytes += lastRawRx;
                }
                if (currentTx < lastRawTx) {
                    baseUploadedBytes += lastRawTx;
                }
                lastRawRx = currentRx;
                lastRawTx = currentTx;
                totalDownloadedBytes = baseDownloadedBytes + lastRawRx;
                totalUploadedBytes = baseUploadedBytes + lastRawTx;
            }
        } catch (Exception e) {
            Log.e(TAG, "Traffic statistics query failed", e);
        }
    }

    private void syncLiveStatsToStorage() {
        synchronized (statsLock) {
            if (globalConnectionStartTime <= 0 || vpnInterface == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (lastSavedTimestamp == 0) {
                lastSavedTimestamp = globalConnectionStartTime;
            }
            long elapsedSeconds = Math.max(0L, (now - lastSavedTimestamp) / 1000L);
            long newRx = totalDownloadedBytes - lastSavedRx;
            long newTx = totalUploadedBytes - lastSavedTx;
            if (newRx < 0 || newTx < 0) {
                lastSavedRx = totalDownloadedBytes;
                lastSavedTx = totalUploadedBytes;
                lastSavedTimestamp = now;
                return;
            }
            if (elapsedSeconds == 0 && newRx == 0 && newTx == 0) {
                return;
            }
            String todayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(now));
            android.content.SharedPreferences preferences = getSharedPreferences(STATS_PREFS, Context.MODE_PRIVATE);
            long duration = preferences.getLong(todayKey + "_duration", 0L);
            long download = preferences.getLong(todayKey + "_download", 0L);
            long upload = preferences.getLong(todayKey + "_upload", 0L);
            preferences.edit().putLong(todayKey + "_duration", duration + elapsedSeconds).putLong(todayKey + "_download", download + newRx).putLong(todayKey + "_upload", upload + newTx).apply();
            lastSavedRx = totalDownloadedBytes;
            lastSavedTx = totalUploadedBytes;
            lastSavedTimestamp = now;
        }
    }

    private void resetTrafficStats() {
        synchronized (statsLock) {
            totalDownloadedBytes = 0L;
            totalUploadedBytes = 0L;
            baseDownloadedBytes = 0L;
            baseUploadedBytes = 0L;
            lastRawRx = 0L;
            lastRawTx = 0L;
            lastSavedRx = 0L;
            lastSavedTx = 0L;
            lastSavedTimestamp = 0L;
        }
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        int exponent = (int) (Math.log(bytes) / Math.log(1024));
        String units = "KMGTPE";
        char prefix = units.charAt(Math.min(exponent - 1, units.length() - 1));
        return String.format(Locale.US, "%.1f %cB", bytes / Math.pow(1024, exponent), prefix);
    }

    public String getOrCreateBaseKey() {
        android.content.SharedPreferences preferences = getSharedPreferences(XRAY_PREFS, Context.MODE_PRIVATE);
        String savedKey = preferences.getString(XRAY_BASE_KEY, null);
        if (savedKey != null && !savedKey.isEmpty()) {
            return savedKey;
        }
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        String generatedKey = Base64.encodeToString(randomBytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        preferences.edit().putString(XRAY_BASE_KEY, generatedKey).apply();
        return generatedKey;
    }

    private void copyAssetsToFilesDir() throws IOException {
        String[] assets = {"geoip.dat", "geosite.dat"};
        File targetDirectory = getFilesDir();
        byte[] buffer = new byte[8192];
        for (String assetName : assets) {
            File targetFile = new File(targetDirectory, assetName);
            if (targetFile.exists() && targetFile.length() > 0) {
                continue;
            }
            try (InputStream input = getAssets().open(assetName); FileOutputStream output = new FileOutputStream(targetFile)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.flush();
            } catch (IOException e) {
                Log.e(TAG, "Asset extraction failed: " + assetName, e);
                throw e;
            }
        }
    }

    public boolean isVpnConnected() {
        return vpnState == VpnState.CONNECTED;
    }

    public boolean isVpnRunning() {
        return vpnInterface != null && isHevRunning && vpnState == VpnState.CONNECTED;
    }

    public String getCurrentVpnStatus() {
        return currentStatus;
    }

    public ParcelFileDescriptor getVpnInterface() {
        return vpnInterface;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "Foreground service timeout: type=" + fgsType);
        isUserStopped = true;
        enqueueVpnOperation(() -> {
            stopVpnInternal(true);
            stopSelf(startId);
        });
        super.onTimeout(startId, fgsType);
    }
}