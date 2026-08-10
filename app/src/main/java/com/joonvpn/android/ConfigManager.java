package com.joonvpn.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.telephony.TelephonyManager;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ConfigManager {
    private static final String CONFIG_URL = "https://raw.githubusercontent.com/oowdov/JoonVPN/refs/heads/main/config.json";
    private static final String PREF_NAME = "JoonVpnPrefs";
    private static final String KEY_LOCAL_CONFIG = "cached_config";

    private final Context context;
    private final SharedPreferences prefs;
    private final OkHttpClient client;

    public ConfigManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.client = new OkHttpClient();
    }

    public boolean isNetworkUnavailable() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (capabilities == null) return true;
        return !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
    }

    public void fetchRemoteConfig(ConfigCallback callback) {
        if (isNetworkUnavailable()) {
            callback.onError("NoInternet");
            return;
        }

        Request request = new Request.Builder().url(CONFIG_URL).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    callback.onError("Server error: " + response.code());
                    return;
                }
                String body = response.body().string();
                try {
                    JSONObject json = new JSONObject(body);
                    prefs.edit().putString(KEY_LOCAL_CONFIG, body).apply();
                    callback.onSuccess(json);
                } catch (Exception e) {
                    callback.onError("Invalid JSON structure");
                }
            }
        });
    }

    public JSONObject getLocalConfig() {
        String saved = prefs.getString(KEY_LOCAL_CONFIG, null);
        if (saved == null) return null;
        try {
            return new JSONObject(saved);
        } catch (Exception e) {
            return null;
        }
    }

    public String getMobileOperator() {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "UNKNOWN";

            String networkName = tm.getNetworkOperatorName();
            if (networkName != null && !networkName.trim().isEmpty() && !networkName.equalsIgnoreCase("Android")) {
                String nameUpper = networkName.toUpperCase(java.util.Locale.US);
                if (nameUpper.contains("MCI") || nameUpper.contains("HAMRAH")) return "MCI";
                if (nameUpper.contains("IRANCELL")) return "IRANCELL";
                if (nameUpper.contains("RIGHTEL")) return "RIGHTEL";
                if (nameUpper.contains("SHATEL")) return "SHATEL";
                if (nameUpper.contains("SAMANTEL")) return "SAMANTEL";
                if (nameUpper.contains("APTEL")) return "APTEL";
                if (nameUpper.contains("LOTUS")) return "LOTUSTEL";
                if (nameUpper.contains("AZAR")) return "AZARTEL";
                if (nameUpper.contains("TALIYA")) return "TALIYA";
                if (nameUpper.contains("TKC")) return "TKC";
            }

            String simName = tm.getSimOperatorName();
            if (simName != null && !simName.trim().isEmpty()) {
                String simUpper = simName.toUpperCase(java.util.Locale.US);
                if (simUpper.contains("MCI") || simUpper.contains("HAMRAH")) return "MCI";
                if (simUpper.contains("IRANCELL")) return "IRANCELL";
                if (simUpper.contains("RIGHTEL")) return "RIGHTEL";
                if (simUpper.contains("SHATEL")) return "SHATEL";
                if (simUpper.contains("SAMANTEL")) return "SAMANTEL";
                if (simUpper.contains("APTEL")) return "APTEL";
                if (simUpper.contains("LOTUS")) return "LOTUSTEL";
                if (simUpper.contains("AZAR")) return "AZARTEL";
                if (simUpper.contains("TALIYA")) return "TALIYA";
                if (simUpper.contains("TKC")) return "TKC";
            }

            String operatorCode = tm.getNetworkOperator();
            if (operatorCode == null || operatorCode.trim().isEmpty()) {
                operatorCode = tm.getSimOperator();
            }

            if (operatorCode != null && operatorCode.length() >= 5) {
                String mccMnc = operatorCode.substring(0, 5);
                return switch (mccMnc) {
                    case "43211", "43219", "43270" -> "MCI";
                    case "43235" -> "IRANCELL";
                    case "43220" -> "RIGHTEL";
                    case "43208" -> "SHATEL";
                    case "43210" -> "SAMANTEL";
                    case "43206" -> "APTEL";
                    case "43212" -> "LOTUSTEL";
                    case "43202" -> "AZARTEL";
                    case "43232" -> "TALIYA";
                    case "43214" -> "TKC";
                    default -> "DEFAULT";
                };
            }
        } catch (Exception ignored) {
        }
        return "UNKNOWN";
    }

    public String generateTargetXrayConfig() {
        JSONObject fullConfig = getLocalConfig();
        if (fullConfig == null) return null;

        try {
            JSONObject baseConfig = fullConfig.getJSONObject("baseConfig");
            String operator = getMobileOperator();
            JSONArray outboundsToUse = null;

            String customKey = switch (operator) {
                case "MCI" -> "MciOutbounds";
                case "IRANCELL" -> "IrancellOutbounds";
                case "RIGHTEL" -> "RightelOutbounds";
                case "SHATEL" -> "ShatelOutbounds";
                case "SAMANTEL" -> "SamantelOutbounds";
                case "APTEL" -> "AptelOutbounds";
                case "LOTUSTEL" -> "LotusTelOutbounds";
                case "AZARTEL" -> "AzartelOutbounds";
                case "TALIYA" -> "TaliyaOutbounds";
                case "TKC" -> "TkcOutbounds";
                default -> null;
            };

            if (customKey != null && fullConfig.has(customKey)) {
                JSONArray targetArray = fullConfig.getJSONArray(customKey);
                if (targetArray.length() > 0) {
                    outboundsToUse = targetArray;
                }
            }

            if (outboundsToUse == null && fullConfig.has("DefaultOutbounds")) {
                outboundsToUse = fullConfig.getJSONArray("DefaultOutbounds");
            }

            if (outboundsToUse == null) return null;

            JSONArray baseOutbounds = baseConfig.has("outbounds") ? baseConfig.getJSONArray("outbounds") : new JSONArray();
            for (int i = 0; i < outboundsToUse.length(); i++) {
                baseOutbounds.put(outboundsToUse.get(i));
            }
            baseConfig.put("outbounds", baseOutbounds);

            return baseConfig.toString();
        } catch (Exception e) {
            return null;
        }
    }


    public interface ConfigCallback {
        void onSuccess(JSONObject configJson);

        void onError(String error);
    }

}

