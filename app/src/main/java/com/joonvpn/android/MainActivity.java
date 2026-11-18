package com.joonvpn.android;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.cardview.widget.CardView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.core.os.LocaleListCompat;
import androidx.transition.TransitionManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.textview.MaterialTextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int BACK_PRESS_DELAY = 2000;
    private static final String ACTION_START = "ACTION_START";
    private static final String ACTION_STOP = "ACTION_STOP";
    private static final String STATUS_CONNECTED = "CONNECTED";
    private static final String STATUS_CONNECTING = "CONNECTING";
    private static final String STATUS_DISCONNECTED = "DISCONNECTED";
    private static final String STATUS_CONNECTED_FAILED = "CONNECTED_FAILED";
    private static final String DEFAULT_BACKGROUND_COLOR = "#121212";
    private static final String PREF_CONFIG = "JoonVpnConfig";
    private static final String PREF_LAST_MESSAGE_HASH = "last_displayed_message_hash";
    private static final String PREF_VPN_STATS = "VpnStatsPrefs";
    public static long globalConnectionStartTime = 0;
    private boolean isVpnRunning = false;
    private boolean isVpnConnecting = false;
    private boolean isBound = false;
    private boolean isReceiverRegistered = false;
    private boolean isActivityResumed = false;
    private boolean doubleBackToExitPressedOnce = false;
    private JoonVpnService vpnServiceBound;
    private ConfigManager configManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ConstraintLayout mainRootLayout;
    private TextView statusTextView;
    private TextView downloadSpeedTextView;
    private TextView uploadSpeedTextView;
    private TextView totalTrafficTextView;
    private TextView connectionTimerTextView;
    private Button toggleVpnButton;
    private CardView batteryWarningCard;
    private View shareDividerView;
    private View shareLayoutContainer;
    private MaterialTextView shareProxyTextView;
    private int currentBackgroundColor = Color.parseColor(DEFAULT_BACKGROUND_COLOR);
    private ValueAnimator backgroundAnimator;
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isActivityResumed || !isVpnRunning || globalConnectionStartTime <= 0) {
                return;
            }
            long elapsedSeconds = (System.currentTimeMillis() - globalConnectionStartTime) / 1000;
            connectionTimerTextView.setText(formatDuration(elapsedSeconds));
            mainHandler.postDelayed(this, 1000);
        }
    };
    private final Runnable uiStatsRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isActivityResumed || !isVpnRunning) {
                return;
            }
            if (isBound && vpnServiceBound != null && vpnServiceBound.getVpnInterface() != null) {
                String trafficData = vpnServiceBound.getFormattedTrafficMetrics();
                String[] metrics = parseTrafficMetrics(trafficData);
                if (metrics != null) {
                    animateTextChange(downloadSpeedTextView, metrics[0]);
                    animateTextChange(uploadSpeedTextView, metrics[1]);
                    animateTextChange(totalTrafficTextView, metrics[2]);
                }
            }
            mainHandler.postDelayed(this, 3000);
        }
    };
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!(service instanceof JoonVpnServiceBinder)) {
                return;
            }
            vpnServiceBound = ((JoonVpnServiceBinder) service).getService();
            isBound = true;
            if (vpnServiceBound == null) {
                return;
            }
            String status = vpnServiceBound.getCurrentVpnStatus();
            if (vpnServiceBound.isVpnRunning()) {
                isVpnRunning = true;
                isVpnConnecting = false;
                updateUi(STATUS_CONNECTED);
            } else if (STATUS_CONNECTING.equals(status)) {
                isVpnRunning = false;
                isVpnConnecting = true;
                updateUi(STATUS_CONNECTING);
            } else if (STATUS_CONNECTED_FAILED.equals(status)) {
                isVpnRunning = false;
                isVpnConnecting = false;
                updateUi(STATUS_CONNECTED_FAILED);
            } else {
                isVpnRunning = false;
                isVpnConnecting = false;
                updateUi(STATUS_DISCONNECTED);
            }
        }


        @Override
        public void onServiceDisconnected(ComponentName name) {
            vpnServiceBound = null;
            isBound = false;
            mainHandler.removeCallbacks(uiStatsRunnable);
            mainHandler.removeCallbacks(timerRunnable);
        }


        @Override
        public void onBindingDied(ComponentName name) {
            vpnServiceBound = null;
            isBound = false;
            mainHandler.removeCallbacks(uiStatsRunnable);
            mainHandler.removeCallbacks(timerRunnable);
            Log.w(TAG, "VPN service binding died");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            vpnServiceBound = null;
            isBound = false;
            mainHandler.removeCallbacks(uiStatsRunnable);
            mainHandler.removeCallbacks(timerRunnable);
            Log.w(TAG, "VPN service returned null binding");
        }
    };
    private final ActivityResultLauncher<Intent> vpnPermissionLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == RESULT_OK) {
            startVpnService();
        } else {
            isVpnConnecting = false;
            updateUi(STATUS_DISCONNECTED);
        }
    });
    private final ActivityResultLauncher<String> notificationPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
        if (isGranted) {
            proceedToVpnPermission();
        } else {
            isVpnConnecting = false;
            updateUi(STATUS_DISCONNECTED);
            showNotificationPermissionDialog();
        }
    });
    private final BroadcastReceiver vpnStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !JoonVpnService.BROADCAST_VPN_STATE.equals(intent.getAction())) {
                return;
            }
            String status = intent.getStringExtra(JoonVpnService.EXTRA_STATUS);
            updateUi(status);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        configManager = new ConfigManager(this);
        initializeViews();
        setupClickListeners();
        setupBackPressedHandler();
        toggleVpnButton.setEnabled(false);
        mainHandler.postDelayed(() -> toggleVpnButton.setEnabled(true), 3000);
    }


    private void setAppLanguage(String language) {
        getSharedPreferences("Settings", MODE_PRIVATE)
                .edit()
                .putString("My_Lang", language)
                .apply();
        AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(language)
        );
    }

    private void initializeViews() {
        mainRootLayout = findViewById(R.id.mainRootLayout);
        statusTextView = findViewById(R.id.statusTextView);
        downloadSpeedTextView = findViewById(R.id.downloadSpeedTextView);
        uploadSpeedTextView = findViewById(R.id.uploadSpeedTextView);
        totalTrafficTextView = findViewById(R.id.totalTrafficTextView);
        connectionTimerTextView = findViewById(R.id.connectionTimerTextView);
        toggleVpnButton = findViewById(R.id.toggleVpnButton);
        batteryWarningCard = findViewById(R.id.batteryWarningCard);
        shareDividerView = findViewById(R.id.shareDividerView);
        shareLayoutContainer = findViewById(R.id.shareLayoutContainer);
        shareProxyTextView = findViewById(R.id.shareProxyTextView);
        MaterialTextView appNameTv = findViewById(R.id.appTitleTextView);
        AppCompatImageView infoIv = findViewById(R.id.infoIv);
        Button fixBatteryButton = findViewById(R.id.fixBatteryButton);
        appNameTv.setOnClickListener(v -> openTelegramLink());
        infoIv.setOnClickListener(v -> showAboutDialog(this));
        fixBatteryButton.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
    }

    private void setupClickListeners() {
        toggleVpnButton.setOnClickListener(v -> {
            if (configManager.isNetworkUnavailable()) {
                new MaterialAlertDialogBuilder(this).setTitle(R.string.no_network_connection_title).setMessage(R.string.no_network_connection_description).setPositiveButton(R.string.got_it, null).show();
                return;
            }
            if (configManager.getLocalConfig() == null) {
                Toast.makeText(this, R.string.no_config_available, Toast.LENGTH_LONG).show();
                return;
            }
            if (isVpnConnecting) {
                return;
            }
            if (isVpnRunning) {
                stopVpnService();
            } else {
                prepareAndStartVpn();
            }
        });
        shareProxyTextView.setOnClickListener(v -> {
            String proxyText = shareProxyTextView.getText().toString();
            if (proxyText.isEmpty()) {
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                return;
            }
            ClipData clip = ClipData.newPlainText(getString(R.string.proxy_address_title), proxyText);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, R.string.proxy_address_copied, Toast.LENGTH_SHORT).show();
        });
        View.OnClickListener statisticsClickListener = v -> showVpnUsageStatisticsDialog();
        connectionTimerTextView.setOnClickListener(statisticsClickListener);
        uploadSpeedTextView.setOnClickListener(statisticsClickListener);
        downloadSpeedTextView.setOnClickListener(statisticsClickListener);
        totalTrafficTextView.setOnClickListener(statisticsClickListener);
    }

    private void setupBackPressedHandler() {
        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (doubleBackToExitPressedOnce) {
                    finishAffinity();
                    return;
                }
                doubleBackToExitPressedOnce = true;
                Toast.makeText(MainActivity.this, R.string.exit_toast_message, Toast.LENGTH_SHORT).show();
                mainHandler.postDelayed(() -> doubleBackToExitPressedOnce = false, BACK_PRESS_DELAY);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, callback);
    }

    private void openTelegramLink() {
        Uri uri = Uri.parse("https://t.me/Yade1819");
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        }
    }

    private void showServerMessageDialog(String title, String message, String imageUrl, String targetUrl, boolean showEveryTime) {
        String safeTitle = title == null ? "" : title.trim();
        String safeMessage = message == null ? "" : message.trim();
        String safeImageUrl = imageUrl == null ? "" : imageUrl.trim();
        String safeTargetUrl = targetUrl == null ? "" : targetUrl.trim();
        if (safeImageUrl.startsWith("http://")) {
            safeImageUrl = "https://" + safeImageUrl.substring(7);
        }
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (!showEveryTime) {
            SharedPreferences prefs = getSharedPreferences(PREF_CONFIG, Context.MODE_PRIVATE);
            String currentHash = safeTitle + "_" + safeMessage + "_" + safeImageUrl + "_" + safeTargetUrl;
            String lastHash = prefs.getString(PREF_LAST_MESSAGE_HASH, "");
            if (lastHash.equals(currentHash)) {
                return;
            }
            prefs.edit().putString(PREF_LAST_MESSAGE_HASH, currentHash).apply();
        }
        boolean hasText = !safeTitle.isEmpty() || !safeMessage.isEmpty();
        boolean hasImage = !safeImageUrl.isEmpty();
        if (!hasText && !hasImage) {
            return;
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setCancelable(false);
        if (!safeTitle.isEmpty()) {
            builder.setTitle(Html.fromHtml(safeTitle, Html.FROM_HTML_MODE_LEGACY));
        }
        if (!safeMessage.isEmpty()) {
            builder.setMessage(Html.fromHtml(safeMessage, Html.FROM_HTML_MODE_LEGACY));
        }
        final AlertDialog[] dialogRef = new AlertDialog[1];
        ShapeableImageView imageView = null;
        if (hasImage) {
            FrameLayout container = new FrameLayout(this);
            container.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            imageView = new ShapeableImageView(this);
            float density = getResources().getDisplayMetrics().density;
            int sidePadding = (int) (24 * density + 0.5f);
            int topPadding = hasText ? 0 : sidePadding;
            FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            imageParams.setMargins(sidePadding, topPadding, sidePadding, 0);
            imageView.setLayoutParams(imageParams);
            imageView.setAdjustViewBounds(true);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ShapeAppearanceModel shapeModel = new ShapeAppearanceModel.Builder().setAllCorners(CornerFamily.ROUNDED, 12 * density).build();
            imageView.setShapeAppearanceModel(shapeModel);
            container.addView(imageView);
            builder.setView(container);
            imageView.setOnClickListener(v -> {
                if (!safeTargetUrl.isEmpty()) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(safeTargetUrl));
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(this, "Error opening the link.", Toast.LENGTH_SHORT).show();
                    }
                }
                if (dialogRef[0] != null) {
                    dialogRef[0].dismiss();
                }
            });
        }
        builder.setPositiveButton(R.string.ok, (dialog, which) -> dialog.dismiss());
        dialogRef[0] = builder.create();
        dialogRef[0].setCancelable(false);
        dialogRef[0].setCanceledOnTouchOutside(false);
        dialogRef[0].show();
        if (hasImage) {
            Window window = dialogRef[0].getWindow();
            if (window != null) {
                window.getDecorView().setAlpha(0f);
            }
            final ShapeableImageView finalImageView = imageView;
            Glide.with(this).load(safeImageUrl).placeholder(android.R.drawable.progress_horizontal).error(android.R.drawable.stat_notify_error).listener(new RequestListener<>() {
                private void revealDialog() {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    finalImageView.post(() -> {
                        if (dialogRef[0] == null || !dialogRef[0].isShowing()) {
                            return;
                        }
                        Window dialogWindow = dialogRef[0].getWindow();
                        if (dialogWindow != null) {
                            dialogWindow.getDecorView().animate().alpha(1f).setDuration(200).start();
                        }
                    });
                }

                @Override
                public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                    revealDialog();
                    return false;
                }

                @Override
                public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                    revealDialog();
                    return false;
                }
            }).into(finalImageView);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivityResumed = true;
        checkBatteryOptimizationStatus();
        registerVpnStateReceiver();
        if (isBound && vpnServiceBound != null) {
            String status = vpnServiceBound.getCurrentVpnStatus();

            if (vpnServiceBound.isVpnRunning()) {
                updateUi(STATUS_CONNECTED);
            } else if (STATUS_CONNECTING.equals(status)) {
                updateUi(STATUS_CONNECTING);
            } else if (STATUS_CONNECTED_FAILED.equals(status)) {
                updateUi(STATUS_CONNECTED_FAILED);
            } else {
                updateUi(STATUS_DISCONNECTED);
            }
        }
        if (isVpnRunning || isVpnConnecting) {
            startPulseAnimation(toggleVpnButton);
        } else {
            stopPulseAnimation(toggleVpnButton);
        }
        if (isVpnRunning) {
            mainHandler.removeCallbacks(timerRunnable);
            mainHandler.post(timerRunnable);
            if (isBound) {
                mainHandler.removeCallbacks(uiStatsRunnable);
                mainHandler.post(uiStatsRunnable);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (backgroundAnimator != null) {
            backgroundAnimator.cancel();
            backgroundAnimator = null;
        }
        stopPulseAnimation(toggleVpnButton);
        unregisterVpnStateReceiver();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerVpnStateReceiver() {
        if (isReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(JoonVpnService.BROADCAST_VPN_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(vpnStateReceiver, filter);
        }
        isReceiverRegistered = true;
    }

    private void unregisterVpnStateReceiver() {
        if (!isReceiverRegistered) {
            return;
        }
        unregisterReceiver(vpnStateReceiver);
        isReceiverRegistered = false;
    }

    private void loadApplicationConfig() {
        configManager.fetchRemoteConfig(new ConfigManager.ConfigFetchCallback() {
            @Override
            public void onSuccess(JSONObject configJson) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    handleServerMessagesAndUpdates(configJson);
                    syncApplicationConfig();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    JSONObject localConfig = configManager.getLocalConfig();
                    if (localConfig != null) {
                        Log.w(TAG, "Using cached configuration: " + error);
                        handleServerMessagesAndUpdates(localConfig);
                        syncApplicationConfig();
                    } else {
                        toggleVpnButton.setEnabled(false);
                        statusTextView.setText(R.string.download_error);
                        if ("NoInternet".equals(error)) {
                            showNoInternetDialog();
                        } else {
                            showFetchErrorDialog(error);
                        }
                    }
                });
            }
        });
    }

    private void syncApplicationConfig() {
        if (configManager.getLocalConfig() == null) {
            toggleVpnButton.setEnabled(false);
            statusTextView.setText(R.string.download_error);
            return;
        }
        configManager.generateTargetXrayConfig(new ConfigManager.XrayConfigCallback() {
            @Override
            public void onSuccess(String xrayConfig, String operator) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    try {
                        new JSONObject(xrayConfig);
                        toggleVpnButton.setEnabled(true);
                        if (!isVpnRunning && !isVpnConnecting) {
                            toggleVpnButton.setText(R.string.connect);
                            statusTextView.setText(R.string.disconnected);
                        }
                        Log.i(TAG, "Selected operator: " + operator);
                    } catch (Exception e) {
                        Log.e(TAG, "Generated config parsing failed", e);
                        toggleVpnButton.setEnabled(false);
                        showFetchErrorDialog("Invalid generated configuration");
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    toggleVpnButton.setEnabled(false);
                    statusTextView.setText(R.string.download_error);
                    showFetchErrorDialog(error);
                });
            }
        });
    }

    private void handleServerMessagesAndUpdates(JSONObject config) {
        if (config == null) {
            return;
        }
        handleAppUpdate(config);
        handleServerMessage(config);
    }

    private void handleAppUpdate(JSONObject config) {
        try {
            JSONObject updateObj = config.getJSONObject("appUpdate");
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            long currentVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo);
            long latestVersionCode = updateObj.getLong("versionCode");
            long minVersionCode = updateObj.optLong("minVersionCode", 0);
            if (latestVersionCode <= currentVersionCode) {
                return;
            }
            boolean isForce = currentVersionCode < minVersionCode;
            String changeLog = updateObj.optString("changeLog", "");
            String updateUrl = updateObj.optString("updateUrl", "");
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this).setTitle(R.string.update_available).setMessage(changeLog.isEmpty() ? getString(R.string.default_changelog) : changeLog).setCancelable(!isForce).setPositiveButton(R.string.update, (dialog, which) -> {
                if (!updateUrl.isEmpty()) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl));
                        startActivity(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "Unable to open update URL", e);
                    }
                }
                if (isForce) {
                    finishAffinity();
                }
            });
            if (isForce) {
                builder.setNegativeButton(R.string.exit, (dialog, which) -> finishAffinity());
            } else {
                builder.setNegativeButton(R.string.later, null);
            }
            AlertDialog dialog = builder.create();
            if (isForce) {
                dialog.setOnKeyListener((dialogInterface, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);
            }
            dialog.show();
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Package information unavailable", e);
        } catch (JSONException e) {
            Log.w(TAG, "Update information unavailable", e);
        }
    }

    private void handleServerMessage(JSONObject config) {
        try {
            JSONObject msgObj = config.getJSONObject("serverMessage");
            String title = msgObj.optString("title", "");
            String message = msgObj.optString("message", "");
            String imageUrl = msgObj.optString("imageUrl", "");
            String targetUrl = msgObj.optString("targetUrl", "");
            boolean showEveryTime = msgObj.optBoolean("showEveryTime", false);
            if (title.trim().isEmpty() && message.trim().isEmpty() && imageUrl.trim().isEmpty()) {
                return;
            }
            showServerMessageDialog(title, message, imageUrl, targetUrl, showEveryTime);
        } catch (JSONException e) {
            Log.w(TAG, "Server message unavailable", e);
        }
    }

    private void prepareAndStartVpn() {
        isVpnConnecting = true;
        updateUi(STATUS_CONNECTING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                proceedToVpnPermission();
            }
        } else {
            proceedToVpnPermission();
        }
    }

    private void proceedToVpnPermission() {
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent);
        } else {
            startVpnService();
        }
    }

    private void startVpnService() {
        Intent intent = new Intent(this, JoonVpnService.class);
        intent.setAction(ACTION_START);
        ContextCompat.startForegroundService(this, intent);
    }

    private void stopVpnService() {
        isVpnConnecting = false;
        isVpnRunning = false;
        globalConnectionStartTime = 0;
        mainHandler.removeCallbacks(uiStatsRunnable);
        mainHandler.removeCallbacks(timerRunnable);
        Intent intent = new Intent(this, JoonVpnService.class);
        intent.setAction(ACTION_STOP);
        ContextCompat.startForegroundService(this, intent);
        updateUi(STATUS_DISCONNECTED);
    }

    private void updateUi(String status) {
        if (status == null || isFinishing() || isDestroyed()) {
            return;
        }
        switch (status) {
            case STATUS_CONNECTING:
                isVpnRunning = false;
                isVpnConnecting = true;
                toggleVpnButton.setText(R.string.connecting);
                toggleVpnButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFB300")));
                animateBackground(Color.parseColor("#1A160D"));
                statusTextView.setText(R.string.connecting);
                statusTextView.setTextColor(Color.parseColor("#FFB300"));
                connectionTimerTextView.setVisibility(View.INVISIBLE);
                mainHandler.removeCallbacks(timerRunnable);
                mainHandler.removeCallbacks(uiStatsRunnable);
                startPulseAnimation(toggleVpnButton);
                break;
            case STATUS_CONNECTED:
                isVpnRunning = true;
                isVpnConnecting = false;
                toggleVpnButton.setText(R.string.disconnect);
                toggleVpnButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2E7D32")));
                animateBackground(Color.parseColor("#0A140C"));
                statusTextView.setText(R.string.connected);
                statusTextView.setTextColor(Color.parseColor("#4CAF50"));
                if (globalConnectionStartTime == 0) {
                    globalConnectionStartTime = System.currentTimeMillis();
                }
                connectionTimerTextView.setVisibility(View.VISIBLE);
                startPulseAnimation(toggleVpnButton);
                if (isActivityResumed) {
                    mainHandler.removeCallbacks(timerRunnable);
                    mainHandler.post(timerRunnable);
                }
                updateShareProxyAddress();
                if (isBound && isActivityResumed) {
                    mainHandler.removeCallbacks(uiStatsRunnable);
                    mainHandler.post(uiStatsRunnable);
                }
                break;
            case STATUS_DISCONNECTED:
                resetConnectionUi();
                toggleVpnButton.setText(R.string.connect);
                toggleVpnButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#1A73E8")));
                animateBackground(Color.parseColor("#121212"));
                statusTextView.setText(R.string.disconnected);
                statusTextView.setTextColor(Color.parseColor("#A0A5B0"));
                break;
            case STATUS_CONNECTED_FAILED:
                resetConnectionUi();
                toggleVpnButton.setText(R.string.connect);
                toggleVpnButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#C62828")));
                animateBackground(Color.parseColor("#1A0D0D"));
                statusTextView.setText(R.string.connection_failed);
                statusTextView.setTextColor(Color.parseColor("#FF5252"));
                break;
        }
    }

    private void resetConnectionUi() {
        isVpnRunning = false;
        isVpnConnecting = false;
        stopPulseAnimation(toggleVpnButton);
        mainHandler.removeCallbacks(timerRunnable);
        mainHandler.removeCallbacks(uiStatsRunnable);
        connectionTimerTextView.setVisibility(View.INVISIBLE);
        downloadSpeedTextView.setText("0 B");
        uploadSpeedTextView.setText("0 B");
        totalTrafficTextView.setText("0 B");
        TransitionManager.beginDelayedTransition(mainRootLayout);
        shareDividerView.setVisibility(View.GONE);
        shareLayoutContainer.setVisibility(View.GONE);
    }

    private void updateShareProxyAddress() {
        String localIp = getLocalIpAddress();
        TransitionManager.beginDelayedTransition(mainRootLayout);
        if (!localIp.equals("Unknown IP")) {
            shareDividerView.setVisibility(View.VISIBLE);
            shareLayoutContainer.setVisibility(View.VISIBLE);
            shareProxyTextView.setText(String.format(Locale.US, "SOCKS5: %s:1819\nHTTP: %s:1918", localIp, localIp));
        } else {
            shareDividerView.setVisibility(View.GONE);
            shareLayoutContainer.setVisibility(View.GONE);
        }
    }

    private void showNotificationPermissionDialog() {
        new MaterialAlertDialogBuilder(this).setTitle("Notification Required").setMessage("Android requires notification permission to keep " + "the VPN notification visible. You can enable it " + "in app settings.").setPositiveButton("Settings", (dialog, which) -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }).setNegativeButton("Cancel", null).show();
    }

    private void checkBatteryOptimizationStatus() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean isIgnoring = powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
        batteryWarningCard.setVisibility(isIgnoring ? View.GONE : View.VISIBLE);
    }

    @SuppressLint("BatteryLife")
    private void requestIgnoreBatteryOptimizations() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        } catch (Exception ignored) {
        }
        String brand = Build.MANUFACTURER.toLowerCase(Locale.US);
        PackageManager packageManager = getPackageManager();
        Intent customIntent = null;
        if (brand.contains("xiaomi")) {
            customIntent = new Intent();
            customIntent.setComponent(new ComponentName("com.miui.securitycenter", "com.miui.powercenter.insstyle.PowerUsageModelActivity"));
        } else if (brand.contains("huawei") || brand.contains("honor")) {
            customIntent = new Intent();
            customIntent.setComponent(new ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"));
        } else if (brand.contains("samsung")) {
            customIntent = new Intent();
            customIntent.setComponent(new ComponentName("com.samsung.android.loaddetector", "com.samsung.android.loaddetector.View.BatteryActivity"));
            if (packageManager.resolveActivity(customIntent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
                customIntent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            }
        } else if (brand.contains("oppo")) {
            customIntent = new Intent();
            customIntent.setComponent(new ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
        } else if (brand.contains("vivo")) {
            customIntent = new Intent();
            customIntent.setComponent(new ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
        } else if (brand.contains("oneplus")) {
            customIntent = new Intent();
            customIntent.setComponent(new ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"));
        }
        if (customIntent != null) {
            if (packageManager.resolveActivity(customIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                try {
                    startActivity(customIntent);
                    return;
                } catch (Exception ignored) {
                }
            }
        }
        try {
            Intent fallbackIntent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(fallbackIntent);
        } catch (Exception ignored) {
        }
    }

    private String getLocalIpAddress() {
        String wifiIpv4 = null;
        String hotspotIpv4 = null;
        String fallbackIpv4 = null;
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface networkInterface : interfaces) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                String interfaceName = networkInterface.getName().toLowerCase(Locale.US);
                if (interfaceName.contains("tun") || interfaceName.contains("p2p") || interfaceName.contains("loop") || interfaceName.contains("dummy")) {
                    continue;
                }
                List<InetAddress> addresses = Collections.list(networkInterface.getInetAddresses());
                for (InetAddress address : addresses) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                        continue;
                    }
                    String hostAddress = address.getHostAddress();
                    if (hostAddress == null) {
                        continue;
                    }
                    fallbackIpv4 = hostAddress;
                    if (interfaceName.contains("ap") || interfaceName.contains("wlan1") || interfaceName.contains("softap")) {
                        hotspotIpv4 = hostAddress;
                    } else if (interfaceName.contains("wlan0") || interfaceName.contains("wl")) {
                        wifiIpv4 = hostAddress;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to retrieve local IP address", e);
        }
        if (hotspotIpv4 != null) return hotspotIpv4;
        if (wifiIpv4 != null) return wifiIpv4;
        if (fallbackIpv4 != null) return fallbackIpv4;
        return "127.0.0.1";
    }

    private void animateTextChange(TextView textView, String newText) {
        if (textView == null || textView.getText().toString().equals(newText)) {
            return;
        }
        textView.animate().setDuration(150).withEndAction(() -> {
            textView.setText(newText);
            textView.animate().alpha(1f).setDuration(150).start();
        }).start();
    }

    private void animateBackground(int targetColor) {
        ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), currentBackgroundColor, targetColor);
        animator.setDuration(500);
        animator.addUpdateListener(animation -> {
            int color = (int) animation.getAnimatedValue();
            mainRootLayout.setBackgroundColor(color);
            currentBackgroundColor = color;
        });
        animator.start();
    }

    private void startPulseAnimation(View view) {
        stopPulseAnimation(view);
        long duration = isVpnRunning ? 1500 : 800;
        float scaleTarget = isVpnRunning ? 1.04f : 1.06f;
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(view, PropertyValuesHolder.ofFloat(View.SCALE_X, scaleTarget), PropertyValuesHolder.ofFloat(View.SCALE_Y, scaleTarget));
        animator.setDuration(duration);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        view.setTag(animator);
        animator.start();
    }

    private void stopPulseAnimation(View view) {
        if (view.getTag() instanceof ObjectAnimator animator) {
            animator.cancel();
            animator.removeAllListeners();
            view.setTag(null);
        }
        view.animate().cancel();
        view.setScaleX(1f);
        view.setScaleY(1f);
    }

    @Override
    protected void onStart() {
        super.onStart();
        loadApplicationConfig();
        Intent intent = new Intent(this, JoonVpnService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }


    @Override
    protected void onPause() {
        super.onPause();
        isActivityResumed = false;
        mainHandler.removeCallbacks(uiStatsRunnable);
        mainHandler.removeCallbacks(timerRunnable);
        unregisterVpnStateReceiver();
    }


    @Override
    protected void onStop() {
        super.onStop();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
            vpnServiceBound = null;
        }
    }

    private void showNoInternetDialog() {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.no_internet_connection).setMessage(R.string.internet_connection_require).setCancelable(false).setPositiveButton(R.string.retry, (dialog, which) -> loadApplicationConfig()).setNegativeButton(R.string.exit, (dialog, which) -> finish()).show();
    }

    private void showFetchErrorDialog(String errorDetail) {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.getting_config_error_title).setMessage(getString(R.string.getting_config_error_description) + errorDetail).setCancelable(false).setPositiveButton(R.string.retry, (dialog, which) -> loadApplicationConfig()).setNegativeButton(R.string.later, null).show();
    }

    private void showVpnUsageStatisticsDialog() {
        SharedPreferences prefs = getSharedPreferences(PREF_VPN_STATS, Context.MODE_PRIVATE);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar calendar = Calendar.getInstance();
        long todayDuration;
        long todayRx;
        long todayTx;
        long weekDuration = 0;
        long weekRx = 0;
        long weekTx = 0;
        long monthDuration = 0;
        long monthRx = 0;
        long monthTx = 0;
        String todayKey = dateFormat.format(calendar.getTime());
        todayDuration = prefs.getLong(todayKey + "_duration", 0);
        todayRx = prefs.getLong(todayKey + "_download", 0);
        todayTx = prefs.getLong(todayKey + "_upload", 0);
        for (int i = 0; i < 7; i++) {
            String date = dateFormat.format(calendar.getTime());
            weekDuration += prefs.getLong(date + "_duration", 0);
            weekRx += prefs.getLong(date + "_download", 0);
            weekTx += prefs.getLong(date + "_upload", 0);
            calendar.add(Calendar.DATE, -1);
        }
        calendar = Calendar.getInstance();
        for (int i = 0; i < 30; i++) {
            String date = dateFormat.format(calendar.getTime());
            monthDuration += prefs.getLong(date + "_duration", 0);
            monthRx += prefs.getLong(date + "_download", 0);
            monthTx += prefs.getLong(date + "_upload", 0);
            calendar.add(Calendar.DATE, -1);
        }
        if (isVpnRunning && globalConnectionStartTime > 0) {
            long activeDuration = (System.currentTimeMillis() - globalConnectionStartTime) / 1000;
            todayDuration += activeDuration;
            weekDuration += activeDuration;
            monthDuration += activeDuration;
            if (isBound && vpnServiceBound != null) {
                try {
                    String trafficData = vpnServiceBound.getFormattedTrafficMetrics();
                    if (trafficData != null && !trafficData.isEmpty()) {
                        String[] metrics = trafficData.split(";");
                        if (metrics.length == 3) {
                            long activeRx = parseFormattedBytesToLong(metrics[0]);
                            long activeTx = parseFormattedBytesToLong(metrics[1]);
                            todayRx += activeRx;
                            weekRx += activeRx;
                            monthRx += activeRx;
                            todayTx += activeTx;
                            weekTx += activeTx;
                            monthTx += activeTx;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Unable to retrieve active traffic statistics", e);
                }
            }
        }
        String todaySection = getString(R.string.report_section_today) + "\n" + getString(R.string.report_row_duration, formatDuration(todayDuration)) + "\n" + getString(R.string.report_row_traffic, formatBytesValue(todayRx), formatBytesValue(todayTx)) + "\n" + getString(R.string.report_row_total, formatBytesValue(todayRx + todayTx));
        String weekSection = getString(R.string.report_section_week) + "\n" + getString(R.string.report_row_duration, formatDuration(weekDuration)) + "\n" + getString(R.string.report_row_traffic, formatBytesValue(weekRx), formatBytesValue(weekTx)) + "\n" + getString(R.string.report_row_total, formatBytesValue(weekRx + weekTx));
        String monthSection = getString(R.string.report_section_month) + "\n" + getString(R.string.report_row_duration, formatDuration(monthDuration)) + "\n" + getString(R.string.report_row_traffic, formatBytesValue(monthRx), formatBytesValue(monthTx)) + "\n" + getString(R.string.report_row_total, formatBytesValue(monthRx + monthTx));
        String report = todaySection + "\n\n" + weekSection + "\n\n" + monthSection;
        new MaterialAlertDialogBuilder(this).setTitle(R.string.vpn_usage).setMessage(report).setPositiveButton(R.string.close, null).show();
    }

    private String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private String formatBytesValue(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        int exponent = (int) (Math.log(bytes) / Math.log(1024));
        String prefixes = "KMGTPE";
        char prefix = prefixes.charAt(Math.min(exponent - 1, prefixes.length() - 1));
        return String.format(Locale.US, "%.1f %cB", bytes / Math.pow(1024, exponent), prefix);
    }

    private long parseFormattedBytesToLong(String formatted) {
        if (formatted == null || formatted.trim().isEmpty()) {
            return 0;
        }
        try {
            String normalized = formatted.replace(",", ".").trim().toUpperCase(Locale.US);
            if (normalized.equals("0 B")) {
                return 0;
            }
            String numericPart = normalized.replaceAll("[^0-9.]", "");
            String unit = normalized.replaceAll("[0-9.]", "").trim();
            if (numericPart.isEmpty()) {
                return 0;
            }
            double value = Double.parseDouble(numericPart);
            int multiplier = switch (unit) {
                case "KB" -> 1;
                case "MB" -> 2;
                case "GB" -> 3;
                case "TB" -> 4;
                default -> 0;
            };
            return (long) (value * Math.pow(1024, multiplier));
        } catch (Exception e) {
            Log.e(TAG, "Unable to parse traffic value: " + formatted, e);
            return 0;
        }
    }

    private void showAboutDialog(Context context) {
        View customView = LayoutInflater.from(context).inflate(R.layout.about_dialog, null);
        MaterialTextView footerTextView = customView.findViewById(R.id.txtDesignerAndChannel);
        String footerText = context.getString(R.string.about_footer);
        SpannableString spannable = new SpannableString(footerText);
        addTelegramLink(spannable, footerText, "@IHEJE", "https://t.me/IHEJE");
        addTelegramLink(spannable, footerText, "@Yade1819", "https://t.me/Yade1819");
        footerTextView.setText(spannable);
        footerTextView.setMovementMethod(LinkMovementMethod.getInstance());
        footerTextView.setOnLongClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("JoonVPN Info", footerText));
                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        new MaterialAlertDialogBuilder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered).setTitle(R.string.about_title).setView(customView).setPositiveButton(R.string.about_positive_button, null).show();
    }

    private void addTelegramLink(SpannableString spannable, String source, String text, String url) {
        int start = source.indexOf(text);
        if (start < 0) {
            return;
        }
        spannable.setSpan(new URLSpan(url), start, start + text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    public static class JoonVpnServiceBinder extends Binder {
        private final WeakReference<JoonVpnService> serviceRef;

        public JoonVpnServiceBinder(JoonVpnService service) {
            serviceRef = new WeakReference<>(service);
        }

        public JoonVpnService getService() {
            return serviceRef.get();
        }
    }

    private String[] parseTrafficMetrics(String trafficData) {
        if (trafficData == null || trafficData.isEmpty()) {
            return null;
        }
        String[] metrics = trafficData.split(";", -1);
        if (metrics.length != 3) {
            return null;
        }
        return metrics;
    }
}