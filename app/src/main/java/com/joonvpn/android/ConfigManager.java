package com.joonvpn.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ConfigManager {

    private static final String CONFIG_URL = "https://raw.githubusercontent.com/oowdov/JoonVPN/refs/heads/main/config.json";
    private static final String PRIMARY_API = "https://ipwho.is/";
    private static final String FALLBACK_API = "https://api.techniknews.net/ipgeo/";
    private static final String PREF_NAME = "JoonVpnPrefs";
    private static final String KEY_LOCAL_CONFIG = "cached_config";
    private static final String TAG = "ConfigManager";
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler mainHandler;
    private final OkHttpClient geoClient;
    private volatile JSONObject remoteConfig;
    private volatile String lastDetectedOperator = "default";
    private final Object fetchLock = new Object();
    private final List<FetchWaiter> fetchWaiters = new ArrayList<>();
    private boolean fetchInProgress = false;

    private static class FetchWaiter {
        final ConfigFetchCallback callback;
        boolean completed = false;

        FetchWaiter(ConfigFetchCallback callback) {
            this.callback = callback;
        }
    }

    public interface ConfigFetchCallback {
        void onSuccess(JSONObject configJson);

        void onError(String error);
    }

    public interface IspCallback {
        void onResult(String operator);
    }

    public interface XrayConfigCallback {
        void onSuccess(String xrayConfig, String operator);

        void onError(String error);
    }

    private static final Map<String, String> ISP_MAP = new LinkedHashMap<>();

    static {
        ISP_MAP.put("as209596", "samantel");
        ISP_MAP.put("samantel", "samantel");
        ISP_MAP.put("saman tel", "samantel");
        ISP_MAP.put("as205647", "shatelmobile");
        ISP_MAP.put("shatel mobile", "shatelmobile");
        ISP_MAP.put("shatel-mobile", "shatelmobile");
        ISP_MAP.put("as31549", "shatel");
        ISP_MAP.put("shatel", "shatel");
        ISP_MAP.put("aria shatel", "shatel");
        ISP_MAP.put("as43754", "asiatech");
        ISP_MAP.put("as41689", "asiatech");
        ISP_MAP.put("asiatech", "asiatech");
        ISP_MAP.put("asia tech", "asiatech");
        ISP_MAP.put("as58224", "tci");
        ISP_MAP.put("as199496", "tci");
        ISP_MAP.put("as50119", "tci");
        ISP_MAP.put("as24707", "tci");
        ISP_MAP.put("as48159", "tci");
        ISP_MAP.put("as51433", "tci");
        ISP_MAP.put("telecommunication company", "tci");
        ISP_MAP.put("tci", "tci");
        ISP_MAP.put("tct", "tci");
        ISP_MAP.put("iran telecommunication", "tci");
        ISP_MAP.put("khuzestan telecom", "tci");
        ISP_MAP.put("isfahan telecom", "tci");
        ISP_MAP.put("as50810", "mobinnet");
        ISP_MAP.put("as47330", "mobinnet");
        ISP_MAP.put("as39501", "mobinnet");
        ISP_MAP.put("mobinnet", "mobinnet");
        ISP_MAP.put("mobin net", "mobinnet");
        ISP_MAP.put("mobin-net", "mobinnet");
        ISP_MAP.put("as56771", "hiweb");
        ISP_MAP.put("hiweb", "hiweb");
        ISP_MAP.put("hi-web", "hiweb");
        ISP_MAP.put("as16335", "parsonline");
        ISP_MAP.put("parsonline", "parsonline");
        ISP_MAP.put("pars online", "parsonline");
        ISP_MAP.put("as44244", "mtn");
        ISP_MAP.put("irancell", "mtn");
        ISP_MAP.put("mtn", "mtn");
        ISP_MAP.put("iran cell", "mtn");
        ISP_MAP.put("as197207", "mci");
        ISP_MAP.put("mci", "mci");
        ISP_MAP.put("hamrah", "mci");
        ISP_MAP.put("mobile communication", "mci");
        ISP_MAP.put("as208342", "aptel");
        ISP_MAP.put("aptel", "aptel");
        ISP_MAP.put("ap tel", "aptel");
        ISP_MAP.put("as57218", "rightel");
        ISP_MAP.put("rightel", "rightel");
        ISP_MAP.put("righ tel", "rightel");
        ISP_MAP.put("as42337", "pishgaman");
        ISP_MAP.put("pishgaman", "pishgaman");
        ISP_MAP.put("as49175", "zitel");
        ISP_MAP.put("zitel", "zitel");
        ISP_MAP.put("as25184", "afranet");
        ISP_MAP.put("afranet", "afranet");
        ISP_MAP.put("lotustel", "lotustel");
        ISP_MAP.put("lotus tel", "lotustel");
    }

    public ConfigManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.geoClient = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
        this.remoteConfig = getLocalConfig();
    }

    public String getConnectionType() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "UNKNOWN";
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) return "UNKNOWN";
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
            if (capabilities == null) return "UNKNOWN";
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return "WIFI";
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return "CELLULAR";
            }
            return "UNKNOWN";
        } catch (Exception e) {
            Log.e(TAG, "Connection detection failed", e);
            return "UNKNOWN";
        }
    }

    public boolean isNetworkUnavailable() {
        return "UNKNOWN".equals(getConnectionType());
    }

    public String getMobileOperator() {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "UNKNOWN";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                int activeDataSubId = SubscriptionManager.getActiveDataSubscriptionId();
                if (activeDataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    tm = tm.createForSubscriptionId(activeDataSubId);
                }
            }
            String operatorCode = tm.getSimOperator();
            if (operatorCode == null || operatorCode.trim().isEmpty()) {
                operatorCode = tm.getNetworkOperator();
            }
            if (operatorCode != null && operatorCode.length() >= 5) {
                String code = operatorCode.trim();
                if (code.startsWith("43211") || code.startsWith("43214") || code.startsWith("43219") || code.startsWith("43299")) {
                    return "mci";
                } else if (code.startsWith("43235") || code.startsWith("43232") || code.startsWith("43293")) {
                    return "mtn";
                } else if (code.startsWith("43220") || code.startsWith("43221")) {
                    return "rightel";
                } else if (code.startsWith("43208") || code.startsWith("43212") || code.startsWith("43250")) {
                    return "shatelmobile";
                } else if (code.startsWith("43210")) {
                    return "samantel";
                } else if (code.startsWith("43202") || code.startsWith("43206")) {
                    return "aptel";
                } else if (code.startsWith("43207")) {
                    return "lotustel";
                }
            }
            String simName = tm.getSimOperatorName();
            if (simName != null && !simName.trim().isEmpty()) {
                String name = simName.toUpperCase(Locale.US).trim();
                if (name.contains("SAMANTEL") || name.contains("SAMAN")) return "samantel";
                if (name.contains("APTEL")) return "aptel";
                if (name.contains("MCI") || name.contains("HAMRAH")) return "mci";
                if (name.contains("IRANCELL") || name.contains("MTN")) return "mtn";
                if (name.contains("RIGHTEL")) return "rightel";
                if (name.contains("SHATEL")) return "shatelmobile";
                if (name.contains("LOTUS")) return "lotustel";
            }
            String networkName = tm.getNetworkOperatorName();
            if (networkName != null && !networkName.trim().isEmpty() && !networkName.equalsIgnoreCase("Android")) {
                String name = networkName.toUpperCase(Locale.US).trim();
                if (name.contains("SAMANTEL") || name.contains("SAMAN")) return "samantel";
                if (name.contains("APTEL")) return "aptel";
                if (name.contains("MCI") || name.contains("HAMRAH")) return "mci";
                if (name.contains("IRANCELL") || name.contains("MTN")) return "mtn";
                if (name.contains("RIGHTEL")) return "rightel";
                if (name.contains("SHATEL")) return "shatelmobile";
                if (name.contains("LOTUS")) return "lotustel";
            }
        } catch (Exception e) {
            Log.e(TAG, "Mobile operator detection failed", e);
        }
        return "UNKNOWN";
    }

    public void fetchRemoteConfig(ConfigFetchCallback callback) {
        if (callback == null) return;
        synchronized (fetchLock) {
            if (fetchInProgress) {
                fetchWaiters.add(new FetchWaiter(callback));
                return;
            }
            if (isNetworkUnavailable()) {
                JSONObject cached = getCurrentConfig();
                if (cached != null) {
                    mainHandler.post(() -> callback.onSuccess(cached));
                } else {
                    mainHandler.post(() -> callback.onError("NoInternet"));
                }
                return;
            }
            fetchInProgress = true;
            fetchWaiters.add(new FetchWaiter(callback));
        }
        startGithubRequest();
    }

    private void startGithubRequest() {
        Request request = new Request.Builder().url(CONFIG_URL).get().build();
        geoClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "GitHub request failed", e);
                finishFetch(getCurrentConfig(), "GitHub unavailable: " + (e.getMessage() != null ? e.getMessage() : "Network request failed"));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response res = response) {
                    int code = res.code();
                    if (code == 403 || code == 429) {
                        Log.w(TAG, "GitHub rate limit: " + code);
                        finishFetch(getCurrentConfig(), "GitHub rate limit exceeded");
                        return;
                    }
                    if (!res.isSuccessful()) {
                        finishFetch(getCurrentConfig(), "GitHub server error: " + code);
                        return;
                    }
                    String body = res.body().string();
                    JSONObject newConfig = new JSONObject(body);
                    if (!isValidConfig(newConfig)) {
                        Log.e(TAG, "Invalid remote configuration");
                        finishFetch(getCurrentConfig(), "Invalid remote configuration");
                        return;
                    }
                    if (!setRemoteConfig(newConfig)) {
                        finishFetch(getCurrentConfig(), "Failed to save remote configuration");
                        return;
                    }
                    Log.i(TAG, "Remote config updated successfully");
                    finishFetch(getCurrentConfig(), null);
                } catch (Exception e) {
                    Log.e(TAG, "Remote config processing failed", e);
                    finishFetch(getCurrentConfig(), "Invalid GitHub response");
                }
            }
        });
    }

    private void finishFetch(JSONObject config, String error) {
        List<FetchWaiter> pending;
        synchronized (fetchLock) {
            fetchInProgress = false;
            pending = new ArrayList<>(fetchWaiters);
            fetchWaiters.clear();
            for (FetchWaiter waiter : pending) {
                waiter.completed = true;
            }
        }
        for (FetchWaiter waiter : pending) {
            if (config != null) {
                mainHandler.post(() -> waiter.callback.onSuccess(config));
            } else {
                mainHandler.post(() -> waiter.callback.onError(error != null ? error : "Config unavailable"));
            }
        }
    }

    public synchronized boolean setRemoteConfig(JSONObject config) {
        if (!isValidConfig(config)) {
            Log.e(TAG, "Invalid remote config");
            return false;
        }
        try {
            JSONObject copy = new JSONObject(config.toString());
            boolean saved = prefs.edit().putString(KEY_LOCAL_CONFIG, copy.toString()).commit();
            if (!saved) {
                Log.e(TAG, "Config disk save failed");
                return false;
            }
            remoteConfig = copy;
            Log.i(TAG, "Remote config saved successfully");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Config saving failed", e);
            return false;
        }
    }

    public JSONObject getLocalConfig() {
        String saved = prefs.getString(KEY_LOCAL_CONFIG, null);
        if (saved == null) return null;
        try {
            JSONObject config = new JSONObject(saved);
            return isValidConfig(config) ? config : null;
        } catch (Exception e) {
            Log.e(TAG, "Local config parsing failed", e);
            return null;
        }
    }

    private boolean isValidConfig(JSONObject config) {
        if (config == null) return false;
        JSONObject baseConfig = config.optJSONObject("baseConfig");
        if (baseConfig == null) return false;
        JSONArray baseOutbounds = baseConfig.optJSONArray("outbounds");
        if (baseOutbounds == null) return false;
        JSONArray defaultOutbounds = config.optJSONArray("default");
        if (defaultOutbounds == null || defaultOutbounds.length() == 0) {
            return false;
        }
        if (!areValidOutbounds(baseOutbounds)) {
            return false;
        }
        if (!areValidOutbounds(defaultOutbounds)) {
            return false;
        }
        java.util.Iterator<String> keys = config.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("baseConfig".equals(key) || "appUpdate".equals(key) || "serverMessage".equals(key)) {
                continue;
            }
            Object value = config.opt(key);
            if (!(value instanceof JSONArray)) {
                return false;
            }
            if (!areValidOutbounds((JSONArray) value)) {
                return false;
            }
        }
        return true;
    }

    private boolean areValidOutbounds(JSONArray array) {
        if (array == null) return false;
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (!(item instanceof JSONObject outbound)) {
                return false;
            }
            if (outbound.optString("protocol", "").trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private synchronized JSONObject getCurrentConfig() {
        if (isValidConfig(remoteConfig)) {
            try {
                return new JSONObject(remoteConfig.toString());
            } catch (JSONException ignored) {
            }
        }
        remoteConfig = getLocalConfig();
        if (remoteConfig == null) return null;
        try {
            return new JSONObject(remoteConfig.toString());
        } catch (JSONException e) {
            return null;
        }
    }

    private void detectWifiIsp(IspCallback callback) {
        Request request = new Request.Builder().url(PRIMARY_API).get().build();
        geoClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "Primary ISP API failed", e);
                tryFallbackIsp(callback);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response res = response) {
                    if (res.isSuccessful()) {
                        JSONObject json = new JSONObject(res.body().string());
                        if (json.optBoolean("success", false)) {
                            JSONObject connection = json.optJSONObject("connection");
                            String asn = "";
                            String isp = "";
                            if (connection != null) {
                                asn = normalizeValue(connection.optString("asn", ""));
                                isp = normalizeValue(connection.optString("isp", ""));
                            }
                            String operator = categorizeIsp(asn + " " + isp);
                            if (!"default".equals(operator)) {
                                postIspResult(callback, operator);
                                return;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Primary ISP parsing failed", e);
                }
                tryFallbackIsp(callback);
            }
        });
    }

    private void tryFallbackIsp(IspCallback callback) {
        Request request = new Request.Builder().url(FALLBACK_API).get().build();
        geoClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "Fallback ISP API failed", e);
                postIspResult(callback, "default");
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response res = response) {
                    if (res.isSuccessful()) {
                        JSONObject json = new JSONObject(res.body().string());
                        String rawAs = normalizeValue(json.optString("as", ""));
                        String rawIsp = normalizeValue(json.optString("isp", json.optString("org", "")));
                        String operator = categorizeIsp(rawAs + " " + rawIsp);
                        postIspResult(callback, operator);
                        return;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Fallback ISP parsing failed", e);
                }
                postIspResult(callback, "default");
            }
        });
    }

    private String normalizeValue(String value) {
        if (value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim())) {
            return "";
        }
        return value.trim();
    }

    private void postIspResult(IspCallback callback, String operator) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onResult(operator == null ? "default" : operator);
            }
        });
    }

    private String categorizeIsp(String rawIspData) {
        if (rawIspData == null || rawIspData.trim().isEmpty()) {
            return "default";
        }
        String data = rawIspData.toLowerCase(Locale.US).trim();
        Matcher matcher = Pattern.compile("(?i)\\bAS\\s*(\\d+)\\b").matcher(data);
        if (matcher.find()) {
            String asn = "as" + matcher.group(1);
            String operator = ISP_MAP.get(asn);
            if (operator != null) {
                return operator;
            }
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(ISP_MAP.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        for (Map.Entry<String, String> entry : entries) {
            String key = entry.getKey().toLowerCase(Locale.US);
            if (key.startsWith("as")) continue;
            if (data.contains(key)) {
                return entry.getValue();
            }
        }
        return "default";
    }

    public void generateTargetXrayConfig(XrayConfigCallback callback) {
        if (callback == null) return;
        JSONObject config = getCurrentConfig();
        if (config == null) {
            postXrayError(callback, "Config unavailable");
            return;
        }
        String connectionType = getConnectionType();
        if ("CELLULAR".equals(connectionType)) {
            String operator = getMobileOperator();
            lastDetectedOperator = "UNKNOWN".equals(operator) ? "default" : operator;
            finishConfigGeneration(config, lastDetectedOperator, callback);
            return;
        }
        if ("WIFI".equals(connectionType)) {
            detectWifiIsp(operator -> {
                lastDetectedOperator = operator == null ? "default" : operator;
                finishConfigGeneration(config, lastDetectedOperator, callback);
            });
            return;
        }
        lastDetectedOperator = "default";
        finishConfigGeneration(config, "default", callback);
    }

    private void finishConfigGeneration(JSONObject config, String operator, XrayConfigCallback callback) {
        String result = buildXrayConfig(config, operator);
        if (result == null) {
            postXrayError(callback, "Unable to generate Xray configuration");
            return;
        }
        mainHandler.post(() -> callback.onSuccess(result, operator));
    }

    private String buildXrayConfig(JSONObject fullConfig, String operator) {
        if (fullConfig == null) return null;
        try {
            JSONObject originalBase = fullConfig.getJSONObject("baseConfig");
            JSONObject baseConfig = new JSONObject(originalBase.toString());
            String selectedKey = normalizeOperatorKey(operator);
            JSONArray selectedOutbounds = fullConfig.optJSONArray(selectedKey);
            if (selectedOutbounds == null || selectedOutbounds.length() == 0) {
                selectedKey = "default";
                selectedOutbounds = fullConfig.optJSONArray("default");
            }
            if (selectedOutbounds == null || selectedOutbounds.length() == 0) {
                Log.e(TAG, "Default outbounds are empty");
                return null;
            }
            JSONArray baseOutbounds = baseConfig.optJSONArray("outbounds");
            if (baseOutbounds == null) {
                baseOutbounds = new JSONArray();
            }
            LinkedHashMap<String, JSONObject> outboundMap = new LinkedHashMap<>();
            List<JSONObject> untaggedOutbounds = new ArrayList<>();
            for (int i = 0; i < baseOutbounds.length(); i++) {
                JSONObject outbound = baseOutbounds.optJSONObject(i);
                if (outbound == null) return null;
                String tag = outbound.optString("tag", "");
                if (tag.isEmpty()) {
                    untaggedOutbounds.add(outbound);
                } else {
                    outboundMap.put(tag, outbound);
                }
            }
            for (int i = 0; i < selectedOutbounds.length(); i++) {
                JSONObject outbound = selectedOutbounds.optJSONObject(i);
                if (outbound == null) return null;
                JSONObject copy = new JSONObject(outbound.toString());
                String tag = copy.optString("tag", "");
                if (tag.isEmpty()) {
                    untaggedOutbounds.add(copy);
                } else {
                    outboundMap.put(tag, copy);
                }
            }
            JSONArray finalOutbounds = new JSONArray();
            for (JSONObject outbound : outboundMap.values()) {
                finalOutbounds.put(outbound);
            }
            for (JSONObject outbound : untaggedOutbounds) {
                finalOutbounds.put(outbound);
            }
            baseConfig.put("outbounds", finalOutbounds);
            Log.i(TAG, "Config generated for operator: " + selectedKey);
            return baseConfig.toString();
        } catch (Exception e) {
            Log.e(TAG, "Xray config generation failed", e);
            return null;
        }
    }

    private String normalizeOperatorKey(String operator) {
        if (operator == null) return "default";
        String value = operator.toLowerCase(Locale.US).trim();
        return switch (value) {
            case "samantel", "shatelmobile", "shatel", "hiweb", "parsonline", "aptel", "rightel",
                 "pishgaman", "zitel", "afranet", "lotustel", "mobinnet", "tci", "asiatech", "mtn",
                 "mci" -> value;
            default -> "default";
        };
    }

    public String generateTargetXrayConfig() {
        JSONObject config = getCurrentConfig();
        if (config == null) return null;
        String connectionType = getConnectionType();
        String operator = "default";
        if ("CELLULAR".equals(connectionType)) {
            operator = getMobileOperator();
            if ("UNKNOWN".equals(operator)) {
                operator = "default";
            }
        } else if ("WIFI".equals(connectionType)) {
            operator = lastDetectedOperator;
            if (operator == null || "UNKNOWN".equals(operator)) {
                operator = "default";
            }
        }
        lastDetectedOperator = operator;
        return buildXrayConfig(config, operator);
    }

    private void postXrayError(XrayConfigCallback callback, String error) {
        mainHandler.post(() -> callback.onError(error));
    }
}