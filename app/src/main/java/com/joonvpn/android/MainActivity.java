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
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import androidx.appcompat.widget.AppCompatImageView;
import androidx.cardview.widget.CardView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.PackageInfoCompat;
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

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import libv2ray.Libv2ray;

public class MainActivity extends AppCompatActivity {

    private static final int BACK_PRESS_DELAY = 2000;
    public static long globalConnectionStartTime = 0;
    private final Handler uiStatsHandler = new Handler(Looper.getMainLooper());
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private ConstraintLayout mainRootLayout;
    private TextView statusTextView;
    private TextView downloadSpeedTextView;
    private TextView uploadSpeedTextView;
    private TextView totalTrafficTextView;
    private TextView connectionTimerTextView;
    private Button toggleVpnButton;
    private CardView batteryWarningCard;
    private boolean isVpnRunning = false;
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isVpnRunning && globalConnectionStartTime > 0) {
                long elapsedSeconds = (System.currentTimeMillis() - globalConnectionStartTime) / 1000;
                long hours = elapsedSeconds / 3600;
                long minutes = (elapsedSeconds % 3600) / 60;
                long seconds = elapsedSeconds % 60;
                connectionTimerTextView.setText(String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds));
                timerHandler.postDelayed(this, 1000);
            }
        }
    };
    private boolean isVpnConnecting = false;
    private int currentBackgroundColor = Color.parseColor("#121212");
    private JoonVpnService vpnServiceBound = null;
    private boolean isBound = false;
    private final Runnable uiStatsRunnable = new Runnable() {
        @Override
        public void run() {
            if (isBound && vpnServiceBound != null && vpnServiceBound.getVpnInterface() != null && isVpnRunning) {
                String trafficData = vpnServiceBound.getFormattedTrafficMetrics();
                if (trafficData != null && !trafficData.isEmpty()) {
                    String[] metrics = trafficData.split(";");
                    if (metrics.length == 3) {
                        animateTextChange(downloadSpeedTextView, metrics[0]);
                        animateTextChange(uploadSpeedTextView, metrics[1]);
                        animateTextChange(totalTrafficTextView, metrics[2]);
                    }
                }
            }
            if (isVpnRunning) {
                uiStatsHandler.postDelayed(this, 3000);
            }
        }
    };
    private View shareDividerView;
    private View shareLayoutContainer;
    private MaterialTextView shareProxyTextView;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service instanceof JoonVpnServiceBinder) {
                vpnServiceBound = ((JoonVpnServiceBinder) service).getService();
                isBound = true;
                if (vpnServiceBound != null) {
                    if (vpnServiceBound.getVpnInterface() != null) {
                        isVpnRunning = true;
                        isVpnConnecting = false;
                        updateUi("CONNECTED");
                        uiStatsHandler.removeCallbacks(uiStatsRunnable);
                        uiStatsHandler.post(uiStatsRunnable);
                    } else {
                        if (isVpnConnecting) {
                            updateUi("CONNECTING");
                        } else {
                            updateUi("DISCONNECTED");
                        }
                    }
                }
            }
        }


        @Override
        public void onServiceDisconnected(ComponentName name) {
            vpnServiceBound = null;
            isBound = false;
            uiStatsHandler.removeCallbacks(uiStatsRunnable);
            timerHandler.removeCallbacks(timerRunnable);
        }
    };
    private final ActivityResultLauncher<Intent> vpnPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    startVpnService();
                } else {
                    isVpnConnecting = false;
                    updateUi("DISCONNECTED");
                }
            }
    );
    private final ActivityResultLauncher<String> notificationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    proceedToVpnPermission();
                } else {
                    isVpnConnecting = false;
                    updateUi("DISCONNECTED");

                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Notification Required")
                            .setMessage("Android requires notification permission to keep the VPN connected stably in the background. Please enable it in app settings.")
                            .setPositiveButton("Settings", (dialog, which) -> {
                                Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }
            }
    );
    private final BroadcastReceiver vpnStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && JoonVpnService.BROADCAST_VPN_STATE.equals(intent.getAction())) {
                String status = intent.getStringExtra(JoonVpnService.EXTRA_STATUS);
                updateUi(status);
            }
        }
    };
    private ConfigManager configManager;
    private boolean doubleBackToExitPressedOnce = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        configManager = new ConfigManager(this);

        mainRootLayout = findViewById(R.id.mainRootLayout);
        statusTextView = findViewById(R.id.statusTextView);
        downloadSpeedTextView = findViewById(R.id.downloadSpeedTextView);
        uploadSpeedTextView = findViewById(R.id.uploadSpeedTextView);
        totalTrafficTextView = findViewById(R.id.totalTrafficTextView);
        connectionTimerTextView = findViewById(R.id.connectionTimerTextView);
        toggleVpnButton = findViewById(R.id.toggleVpnButton);
        batteryWarningCard = findViewById(R.id.batteryWarningCard);
        Button fixBatteryButton = findViewById(R.id.fixBatteryButton);
        shareDividerView = findViewById(R.id.shareDividerView);
        shareLayoutContainer = findViewById(R.id.shareLayoutContainer);
        shareProxyTextView = findViewById(R.id.shareProxyTextView);

        MaterialTextView appNameTv = findViewById(R.id.appTitleTextView);
        appNameTv.setOnClickListener(v -> openTelegramLink());
        AppCompatImageView infoIv = findViewById(R.id.infoIv);
        infoIv.setOnClickListener(v -> showAboutDialog(MainActivity.this));

        toggleVpnButton.setEnabled(false);
        new Handler(Looper.getMainLooper()).postDelayed(() -> toggleVpnButton.setEnabled(true), 3000);
        toggleVpnButton.setOnClickListener(v -> {
            if (configManager.isNetworkUnavailable()) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.no_network_connection_title)
                        .setMessage(R.string.no_network_connection_description)
                        .setPositiveButton(R.string.got_it, null)
                        .show();
                return;
            }
            if (configManager.getLocalConfig() == null) {
                Toast.makeText(this, R.string.no_config_available, Toast.LENGTH_LONG).show();
                return;
            }
            if (isVpnConnecting) return;
            if (isVpnRunning) {
                stopVpnService();
            } else {
                prepareAndStartVpn();
            }
        });

        fixBatteryButton.setOnClickListener(v -> requestIgnoreBatteryOptimizations());

        shareProxyTextView.setOnClickListener(v -> {
            String proxyText = shareProxyTextView.getText().toString();
            if (!proxyText.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    ClipData clip = ClipData.newPlainText(getString(R.string.proxy_address_title), proxyText);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, R.string.proxy_address_copied, Toast.LENGTH_SHORT).show();
                }
            }
        });

        connectionTimerTextView.setOnClickListener(v -> showVpnUsageStatisticsDialog());
        uploadSpeedTextView.setOnClickListener(v -> showVpnUsageStatisticsDialog());
        downloadSpeedTextView.setOnClickListener(v -> showVpnUsageStatisticsDialog());
        totalTrafficTextView.setOnClickListener(v -> showVpnUsageStatisticsDialog());

        syncApplicationConfig();

        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (doubleBackToExitPressedOnce) {
                    finishAffinity();
                    return;
                }

                doubleBackToExitPressedOnce = true;

                Toast.makeText(MainActivity.this, R.string.exit_toast_message, Toast.LENGTH_SHORT).show();

                new Handler(Looper.getMainLooper()).postDelayed(() ->
                        doubleBackToExitPressedOnce = false, BACK_PRESS_DELAY);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, callback);
    }


    private void syncApplicationConfig() {
        if (configManager.isNetworkUnavailable()) {
            if (configManager.getLocalConfig() == null) {
                toggleVpnButton.setEnabled(false);
                statusTextView.setText("");
                showNoInternetDialog();
            }
            return;
        }

        configManager.fetchRemoteConfig(new ConfigManager.ConfigCallback() {
            @Override
            public void onSuccess(JSONObject configJson) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    toggleVpnButton.setEnabled(true);
                    if (!isVpnRunning && !isVpnConnecting) {
                        toggleVpnButton.setText(R.string.connect);
                        statusTextView.setText(R.string.disconnected);
                    }
                    handleServerMessagesAndUpdates(configJson);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (configManager.getLocalConfig() != null) {
                        toggleVpnButton.setEnabled(true);
                    } else {
                        toggleVpnButton.setEnabled(false);
                        statusTextView.setText(R.string.download_error);
                        showFetchErrorDialog(error);
                    }
                });
            }
        });
    }


    private void handleServerMessagesAndUpdates(JSONObject config) {
        try {
            JSONObject updateObj = config.getJSONObject("appUpdate");

            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            long currentVersionCode = PackageInfoCompat.getLongVersionCode(pInfo);

            long latestVersionCode = updateObj.getLong("versionCode");
            long minVersionCode = updateObj.optLong("minVersionCode", 0);

            if (latestVersionCode > currentVersionCode) {
                final boolean isForce = currentVersionCode < minVersionCode;

                String changeLog = updateObj.getString("changeLog");
                String updateUrl = updateObj.getString("updateUrl");

                MaterialAlertDialogBuilder updateDialogBuilder =
                        new MaterialAlertDialogBuilder(this)
                                .setTitle(getString(R.string.update_available))
                                .setMessage(changeLog.isEmpty() ? getString(R.string.default_changelog) : changeLog)
                                .setCancelable(!isForce)
                                .setPositiveButton(R.string.update, (d, w) -> {
                                    if (!updateUrl.isEmpty()) {
                                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)));
                                    }
                                    if (isForce) {
                                        finishAffinity();
                                    }
                                });

                if (!isForce) {
                    updateDialogBuilder.setNegativeButton(R.string.later, null);
                } else {
                    updateDialogBuilder.setNegativeButton(R.string.exit, (dialog, which) -> finishAffinity());
                }

                AlertDialog materialDialog = updateDialogBuilder.create();

                if (isForce) {
                    materialDialog.setOnKeyListener((dialogInterface, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);
                }

                materialDialog.show();

                if (isForce) return;
            }


            JSONObject msgObj = config.getJSONObject("serverMessage");
            String title = msgObj.optString("title", "");
            String message = msgObj.optString("message", "");
            String imageUrl = msgObj.optString("imageUrl", "");
            String targetUrl = msgObj.optString("targetUrl", "");
            boolean showEveryTime = msgObj.optBoolean("showEveryTime", false);
            if (!title.trim().isEmpty() || !message.trim().isEmpty() || !imageUrl.trim().isEmpty()) {
                showServerMessageDialog(title, message, imageUrl, targetUrl, showEveryTime);
            }

        } catch (Exception ignored) {
        }
    }

    private void showServerMessageDialog(String title, String message, String imageUrl, String targetUrl, boolean showEveryTime) {
        String safeTitle = (title != null) ? title.trim() : "";
        String safeMessage = (message != null) ? message.trim() : "";
        String initialImageUrl = (imageUrl != null) ? imageUrl.trim() : "";
        String safeTargetUrl = (targetUrl != null) ? targetUrl.trim() : "";

        if (initialImageUrl.startsWith("http://")) {
            initialImageUrl = initialImageUrl.replace("http://", "https://");
        }

        final String safeImageUrl = initialImageUrl;

        if (!showEveryTime) {
            SharedPreferences prefs = getSharedPreferences("JoonVpnConfig", Context.MODE_PRIVATE);
            String lastMsgHash = prefs.getString("last_displayed_message_hash", "");
            String currentHash = safeTitle + "_" + safeMessage + "_" + safeImageUrl + "_" + safeTargetUrl;

            if (lastMsgHash.equals(currentHash)) return;

            prefs.edit().putString("last_displayed_message_hash", currentHash).apply();
        }

        boolean hasText = !safeTitle.isEmpty() || !safeMessage.isEmpty();
        boolean hasImage = !safeImageUrl.isEmpty();

        if (!hasText && !hasImage) return;
        if (isFinishing() || isDestroyed()) return;

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setCancelable(false);


        if (!safeTitle.isEmpty()) {
            builder.setTitle(Html.fromHtml(safeTitle, Html.FROM_HTML_MODE_LEGACY));
        }

        if (!safeMessage.isEmpty()) {
            builder.setMessage(Html.fromHtml(safeMessage, Html.FROM_HTML_MODE_LEGACY));
        }

        final AlertDialog[] dialogRef = new AlertDialog[1];
        FrameLayout container;
        ShapeableImageView imageView = null;

        if (hasImage) {
            container = new FrameLayout(this);
            container.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            imageView = new ShapeableImageView(this);
            FrameLayout.LayoutParams imgParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);

            float scale = getResources().getDisplayMetrics().density;
            int topPadding = hasText ? 0 : 24;
            int sidePadding = 24;

            imgParams.setMargins(
                    (int) (sidePadding * scale + 0.5f),
                    (int) (topPadding * scale + 0.5f),
                    (int) (sidePadding * scale + 0.5f),
                    0
            );
            imageView.setLayoutParams(imgParams);
            imageView.setAdjustViewBounds(true);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

            ShapeAppearanceModel shapeAppearanceModel = new ShapeAppearanceModel()
                    .toBuilder()
                    .setAllCorners(CornerFamily.ROUNDED, 12 * scale)
                    .build();
            imageView.setShapeAppearanceModel(shapeAppearanceModel);

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

        if (hasImage) {
            if (dialogRef[0].getWindow() != null) {
                dialogRef[0].getWindow().getDecorView().setAlpha(0.0f);
            }
            dialogRef[0].show();

            final ShapeableImageView finalImageView = imageView;
            Glide.with(this)
                    .load(safeImageUrl)
                    .placeholder(android.R.drawable.progress_horizontal)
                    .error(android.R.drawable.stat_notify_error)
                    .listener(new RequestListener<>() {
                        private void revealDialog() {
                            runOnUiThread(() -> {
                                if (dialogRef[0] != null && dialogRef[0].isShowing() && dialogRef[0].getWindow() != null) {
                                    finalImageView.post(() -> {
                                        if (dialogRef[0] != null && dialogRef[0].getWindow() != null) {
                                            dialogRef[0].getWindow().getDecorView().animate().alpha(1.0f).setDuration(200).start();
                                        }
                                    });
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
                    })
                    .into(finalImageView);
        } else {
            dialogRef[0].show();
        }
    }


    private void checkBatteryOptimizationStatus() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            batteryWarningCard.setVisibility(View.GONE);
        } else {
            batteryWarningCard.setVisibility(View.VISIBLE);
        }
    }

    private void prepareAndStartVpn() {
        isVpnConnecting = true;
        updateUi("CONNECTING");

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
        intent.setAction("ACTION_START");
        ContextCompat.startForegroundService(this, intent);
    }

    private void stopVpnService() {
        isVpnConnecting = false;
        isVpnRunning = false;
        globalConnectionStartTime = 0;
        uiStatsHandler.removeCallbacks(uiStatsRunnable);
        timerHandler.removeCallbacks(timerRunnable);
        Intent intent = new Intent(this, JoonVpnService.class);
        intent.setAction("ACTION_STOP");
        ContextCompat.startForegroundService(this, intent);
        stopService(intent);
        updateUi("DISCONNECTED");
    }

    private void updateUi(String status) {
        if (status == null) return;
        switch (status) {
            case "CONNECTING" -> {
                isVpnRunning = false;
                toggleVpnButton.setText(R.string.connecting);
                toggleVpnButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFB300")));
                animateBackground(Color.parseColor("#1A160D"));
                statusTextView.setText(R.string.connecting);
                statusTextView.setTextColor(Color.parseColor("#FFB300"));
                connectionTimerTextView.setVisibility(View.INVISIBLE);
                startPulseAnimation(toggleVpnButton);
            }
            case "CONNECTED" -> {
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
                timerHandler.removeCallbacks(timerRunnable);
                timerHandler.post(timerRunnable);
                stopPulseAnimation(toggleVpnButton);
                startPulseAnimation(toggleVpnButton);
                String localIp = getLocalIpAddress();
                TransitionManager.beginDelayedTransition(mainRootLayout);
                if (!localIp.equals("Unknown IP")) {
                    shareDividerView.setVisibility(View.VISIBLE);
                    shareLayoutContainer.setVisibility(View.VISIBLE);
                    shareProxyTextView.setText(String.format("SOCKS5: %s:10801\nHTTP: %s:10802", localIp, localIp));
                } else {
                    shareDividerView.setVisibility(View.GONE);
                    shareLayoutContainer.setVisibility(View.GONE);
                }
                if (isBound) {
                    uiStatsHandler.removeCallbacks(uiStatsRunnable);
                    uiStatsHandler.post(uiStatsRunnable);
                }
            }
            case "DISCONNECTED" -> {
                isVpnRunning = false;
                isVpnConnecting = false;
                stopPulseAnimation(toggleVpnButton);
                toggleVpnButton.setText(R.string.connect);
                toggleVpnButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#1A73E8")));
                animateBackground(Color.parseColor("#121212"));
                statusTextView.setText(R.string.disconnected);
                statusTextView.setTextColor(Color.parseColor("#A0A5B0"));
                connectionTimerTextView.setVisibility(View.INVISIBLE);
                downloadSpeedTextView.setText("0 B");
                uploadSpeedTextView.setText("0 B");
                totalTrafficTextView.setText("0 B");
                TransitionManager.beginDelayedTransition(mainRootLayout);
                shareDividerView.setVisibility(View.GONE);
                shareLayoutContainer.setVisibility(View.GONE);
            }

            case "CONNECTED_FAILED" -> {
                isVpnRunning = false;
                isVpnConnecting = false;
                stopPulseAnimation(toggleVpnButton);
                toggleVpnButton.setText(R.string.connect);
                toggleVpnButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#C62828")));
                animateBackground(Color.parseColor("#1A0D0D"));
                statusTextView.setText(R.string.connection_failed);
                statusTextView.setTextColor(Color.parseColor("#FF5252"));
                connectionTimerTextView.setVisibility(View.INVISIBLE);
                downloadSpeedTextView.setText("0 B");
                uploadSpeedTextView.setText("0 B");
                totalTrafficTextView.setText("0 B");
                shareDividerView.setVisibility(View.GONE);
                shareLayoutContainer.setVisibility(View.GONE);
            }
        }
    }

    @SuppressLint("BatteryLife")
    private void requestIgnoreBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        } catch (Exception ignored) {
        }
        String brand = Build.MANUFACTURER.toLowerCase(Locale.US);
        Intent customIntent = null;
        PackageManager pmCheck = getPackageManager();
        if (brand.contains("xiaomi")) {
            customIntent = new Intent();
            customIntent.setComponent(new ComponentName("com.miui.securitycenter", "com.miui.powercenter.insstyle.PowerUsageModelActivity"));
        } else if (brand.contains("huawei") || brand.contains("honor")) {
            customIntent = new Intent();
            customIntent.setComponent(new ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"));
        } else if (brand.contains("samsung")) {
            customIntent = new Intent();
            customIntent.setComponent(new ComponentName("com.samsung.android.loaddetector", "com.samsung.android.loaddetector.View.BatteryActivity"));
            if (pmCheck.resolveActivity(customIntent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
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
            customIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pmCheck.resolveActivity(customIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                try {
                    startActivity(customIntent);
                    return;
                } catch (Exception ignored) {
                }
            }
        }
        try {
            Intent fallbackIntent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(fallbackIntent);
        } catch (Exception ignored) {
        }
    }

    public void openTelegramLink() {
        Uri uri = Uri.parse("https://t.me/JoonVPN");
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        }
    }

    private void animateTextChange(TextView textView, String newText) {
        if (textView.getText().toString().equals(newText)) return;
        textView.animate().alpha(0.0f).setDuration(150).withEndAction(() -> {
            textView.setText(newText);
            textView.animate().alpha(1.0f).setDuration(150).start();
        }).start();
    }

    public String getLocalIpAddress() {
        String wifiIpv4 = null;
        String hotspotIpv4 = null;
        String fallbackIpv4 = null;

        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                String ifaceName = intf.getName().toLowerCase(Locale.US);

                if (ifaceName.contains("tun") || ifaceName.contains("p2p") ||
                        ifaceName.contains("loop") || ifaceName.contains("dummy")) {
                    continue;
                }

                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
                        continue;
                    }

                    if (addr instanceof Inet4Address) {
                        String hostAddress = addr.getHostAddress();
                        if (hostAddress == null) {
                            continue;
                        }

                        fallbackIpv4 = hostAddress;

                        if (ifaceName.contains("ap") || ifaceName.contains("wlan1") || ifaceName.contains("softap")) {
                            hotspotIpv4 = hostAddress;
                        } else if (ifaceName.contains("wlan0") || ifaceName.contains("wl")) {
                            wifiIpv4 = hostAddress;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (hotspotIpv4 != null) return hotspotIpv4;
        if (wifiIpv4 != null) return wifiIpv4;
        if (fallbackIpv4 != null) return fallbackIpv4;

        return "127.0.0.1";
    }


    private void animateBackground(int targetColor) {
        ValueAnimator colorAnimation = ValueAnimator.ofObject(new ArgbEvaluator(), currentBackgroundColor, targetColor);
        colorAnimation.setDuration(500);
        colorAnimation.addUpdateListener(animator -> {
            int color = (int) animator.getAnimatedValue();
            mainRootLayout.setBackgroundColor(color);
            currentBackgroundColor = color;
        });
        colorAnimation.start();
    }

    private void startPulseAnimation(View view) {
        stopPulseAnimation(view);
        long duration = isVpnRunning ? 1500 : 800;
        float scaleTarget = isVpnRunning ? 1.04f : 1.06f;
        PropertyValuesHolder pvhX = PropertyValuesHolder.ofFloat(View.SCALE_X, scaleTarget);
        PropertyValuesHolder pvhY = PropertyValuesHolder.ofFloat(View.SCALE_Y, scaleTarget);
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(view, pvhX, pvhY);
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
        view.setScaleX(1.0f);
        view.setScaleY(1.0f);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, JoonVpnService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();
        checkBatteryOptimizationStatus();

        IntentFilter filter = new IntentFilter(JoonVpnService.BROADCAST_VPN_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(vpnStateReceiver, filter);
        }

        if (isBound && vpnServiceBound != null && vpnServiceBound.getVpnInterface() != null) {
            isVpnRunning = true;
            isVpnConnecting = false;
        }
        if (isVpnRunning || isVpnConnecting) {
            startPulseAnimation(toggleVpnButton);
        } else {
            stopPulseAnimation(toggleVpnButton);
        }
        if (isVpnRunning && isBound) {
            uiStatsHandler.removeCallbacks(uiStatsRunnable);
            uiStatsHandler.post(uiStatsRunnable);
            timerHandler.removeCallbacks(timerRunnable);
            timerHandler.post(timerRunnable);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiStatsHandler.removeCallbacks(uiStatsRunnable);
        timerHandler.removeCallbacks(timerRunnable);
        try {
            unregisterReceiver(vpnStateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    private void showNoInternetDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.no_internet_connection)
                .setMessage(R.string.internet_connection_require)
                .setCancelable(false)
                .setPositiveButton(R.string.retry, (dialog, which) -> syncApplicationConfig())
                .setNegativeButton(R.string.exit, (dialog, which) -> finish())
                .show();
    }

    private void showFetchErrorDialog(String errorDetail) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.getting_config_error_title)
                .setMessage(getString(R.string.getting_config_error_description) + errorDetail)
                .setCancelable(false)
                .setPositiveButton(R.string.retry, (dialog, which) -> syncApplicationConfig())
                .setNegativeButton(R.string.later, null)
                .show();
    }

    private void showVpnUsageStatisticsDialog() {
        SharedPreferences statsPrefs = getSharedPreferences("VpnStatsPrefs", Context.MODE_PRIVATE);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        Calendar cal = Calendar.getInstance();

        long todayDuration, todayRx, todayTx;
        long weekDuration = 0, weekRx = 0, weekTx = 0;
        long monthDuration = 0, monthRx = 0, monthTx = 0;

        String todayStr = sdf.format(cal.getTime());
        todayDuration = statsPrefs.getLong(todayStr + "_duration", 0);
        todayRx = statsPrefs.getLong(todayStr + "_download", 0);
        todayTx = statsPrefs.getLong(todayStr + "_upload", 0);

        for (int i = 0; i < 7; i++) {
            String dateStr = sdf.format(cal.getTime());
            weekDuration += statsPrefs.getLong(dateStr + "_duration", 0);
            weekRx += statsPrefs.getLong(dateStr + "_download", 0);
            weekTx += statsPrefs.getLong(dateStr + "_upload", 0);
            cal.add(java.util.Calendar.DATE, -1);
        }

        cal = java.util.Calendar.getInstance();
        for (int i = 0; i < 30; i++) {
            String dateStr = sdf.format(cal.getTime());
            monthDuration += statsPrefs.getLong(dateStr + "_duration", 0);
            monthRx += statsPrefs.getLong(dateStr + "_download", 0);
            monthTx += statsPrefs.getLong(dateStr + "_upload", 0);
            cal.add(java.util.Calendar.DATE, -1);
        }

        if (isVpnRunning && globalConnectionStartTime > 0) {
            long activeSessionSec = (System.currentTimeMillis() - globalConnectionStartTime) / 1000;
            todayDuration += activeSessionSec;
            weekDuration += activeSessionSec;
            monthDuration += activeSessionSec;

            if (vpnServiceBound != null) {
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
                } catch (Exception ignored) {
                }
            }
        }

        String todaySection = getString(R.string.report_section_today) + "\n" +
                getString(R.string.report_row_duration, formatDuration(todayDuration)) + "\n" +
                getString(R.string.report_row_traffic, formatBytesValue(todayRx), formatBytesValue(todayTx)) + "\n" +
                getString(R.string.report_row_total, formatBytesValue(todayRx + todayTx));

        String weekSection = getString(R.string.report_section_week) + "\n" +
                getString(R.string.report_row_duration, formatDuration(weekDuration)) + "\n" +
                getString(R.string.report_row_traffic, formatBytesValue(weekRx), formatBytesValue(weekTx)) + "\n" +
                getString(R.string.report_row_total, formatBytesValue(weekRx + weekTx));

        String monthSection = getString(R.string.report_section_month) + "\n" +
                getString(R.string.report_row_duration, formatDuration(monthDuration)) + "\n" +
                getString(R.string.report_row_traffic, formatBytesValue(monthRx), formatBytesValue(monthTx)) + "\n" +
                getString(R.string.report_row_total, formatBytesValue(monthRx + monthTx));

        String message = todaySection + "\n\n" + weekSection + "\n\n" + monthSection;


        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vpn_usage)
                .setMessage(message)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private String formatBytesValue(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE";
        char prefix = (exp - 1 < pre.length()) ? pre.charAt(exp - 1) : 'E';
        return String.format(java.util.Locale.US, "%.1f %cB", bytes / Math.pow(1024, exp), prefix);
    }

    private long parseFormattedBytesToLong(String formattedStr) {
        try {
            if (formattedStr == null || formattedStr.trim().isEmpty() || formattedStr.equals("0 B"))
                return 0;

            String normalized = formattedStr.replace(",", ".");
            String cleaned = normalized.replaceAll("[^0-9.]", "").trim();
            if (cleaned.isEmpty()) return 0;

            double value = Double.parseDouble(cleaned);
            String unit = normalized.replaceAll("[0-9. ]", "").trim().toUpperCase(java.util.Locale.US);
            return switch (unit) {
                case "KB" -> (long) (value * 1024);
                case "MB" -> (long) (value * 1024 * 1024);
                case "GB" -> (long) (value * 1024 * 1024 * 1024);
                case "TB" -> (long) (value * 1024 * 1024 * 1024 * 1024);
                default -> (long) value;
            };
        } catch (Exception e) {
            return 0;
        }
    }

    public void showAboutDialog(Context context) {
        View customView = LayoutInflater.from(context).inflate(R.layout.about_dialog, null);
        MaterialTextView txtDesignerAndChannel = customView.findViewById(R.id.txtDesignerAndChannel);

        String footerText = context.getString(R.string.about_footer);
        SpannableString spannableFooter = new SpannableString(footerText);

        int designerStart = footerText.indexOf("@IHEJE");
        if (designerStart >= 0) {
            int designerEnd = designerStart + "@IHEJE".length();
            spannableFooter.setSpan(new URLSpan("https://t.me/IHEJE"), designerStart, designerEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        int channelStart = footerText.indexOf("@JoonVPN");
        if (channelStart >= 0) {
            int channelEnd = channelStart + "@JoonVPN".length();
            spannableFooter.setSpan(new URLSpan("https://t.me/JoonVPN"), channelStart, channelEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        txtDesignerAndChannel.setText(spannableFooter);
        txtDesignerAndChannel.setMovementMethod(LinkMovementMethod.getInstance());

        txtDesignerAndChannel.setOnLongClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("JoonVPN Info", footerText);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        new MaterialAlertDialogBuilder(context,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered)
                .setTitle(context.getString(R.string.about_title))
                .setView(customView)
                .setPositiveButton(R.string.about_positive_button, null)
                .show();
    }

    public static class JoonVpnServiceBinder extends Binder {
        private final java.lang.ref.WeakReference<JoonVpnService> serviceRef;

        public JoonVpnServiceBinder(JoonVpnService service) {
            this.serviceRef = new java.lang.ref.WeakReference<>(service);
        }

        public JoonVpnService getService() {
            return serviceRef.get();
        }
    }

}
