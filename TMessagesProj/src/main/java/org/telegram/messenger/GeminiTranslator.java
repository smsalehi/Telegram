package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Gemini-backed translation engine.
 *
 * When enabled (with a user-provided API key), all client translation flows
 * (per-message popup, per-chat auto-translate) are routed here instead of
 * Telegram's server endpoint, so it works without Premium. Language
 * detection stays on-device (ML Kit, see LanguageDetector).
 *
 * Nothing is sent anywhere unless this is enabled; the key is stored only
 * in the app's private preferences.
 */
public class GeminiTranslator {

    private static final String PREFS = "mainconfig";
    private static final String KEY_ENABLED = "gemini_translate_enabled";
    private static final String KEY_API_KEY = "gemini_translate_key";

    private static final String MODEL = "gemini-2.0-flash";
    private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";

    private static SharedPreferences prefs() {
        Context ctx = ApplicationLoader.applicationContext;
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        try {
            return prefs().getBoolean(KEY_ENABLED, false) && !TextUtils.isEmpty(getApiKey());
        } catch (Throwable e) {
            return false;
        }
    }

    public static void setEnabled(boolean enabled) {
        try {
            prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
        } catch (Throwable ignored) {}
    }

    public static String getApiKey() {
        try {
            String key = prefs().getString(KEY_API_KEY, "");
            return key == null ? "" : key.trim();
        } catch (Throwable e) {
            return "";
        }
    }

    public static void setApiKey(String key) {
        try {
            prefs().edit().putString(KEY_API_KEY, key == null ? "" : key.trim()).apply();
        } catch (Throwable ignored) {}
    }

    public static String getMaskedKey() {
        String key = getApiKey();
        if (TextUtils.isEmpty(key)) {
            return "";
        }
        if (key.length() <= 8) {
            return "****";
        }
        return "****" + key.substring(key.length() - 4);
    }

    private static String baseCode(String lang) {
        if (TextUtils.isEmpty(lang)) {
            return "";
        }
        String code = lang.split("_")[0].toLowerCase(Locale.US);
        if ("nb".equals(code)) {
            code = "no";
        }
        return code;
    }

    private static final int CACHE_SIZE = 200;
    private static final Map<String, String> cache = new LinkedHashMap<String, String>(CACHE_SIZE + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private static String cacheKey(String text, String from, String to) {
        return baseCode(from) + "|" + baseCode(to) + "|" + text.length() + "|" + text.hashCode();
    }

    /**
     * Translate text with Gemini. Always async; done runs on the UI thread.
     * done = (translatedText, rateLimited). translatedText null = failure.
     */
    public static void translate(String text, String fromLng, String toLng, Utilities.Callback2<String, Boolean> done) {
        if (done == null) {
            return;
        }
        if (TextUtils.isEmpty(text)) {
            AndroidUtilities.runOnUIThread(() -> done.run("", false));
            return;
        }
        final String apiKey = getApiKey();
        if (TextUtils.isEmpty(apiKey)) {
            AndroidUtilities.runOnUIThread(() -> done.run(null, false));
            return;
        }
        String from = baseCode(fromLng);
        String to = baseCode(toLng);
        if (TextUtils.isEmpty(to)) {
            to = "en";
        }
        final String toFinal = to;
        // Same language: nothing to do, no network, no cost.
        if (!TextUtils.isEmpty(from) && from.equals(toFinal)) {
            AndroidUtilities.runOnUIThread(() -> done.run(text, false));
            return;
        }
        final String cacheLookupKey = cacheKey(text, from, toFinal);
        synchronized (cache) {
            String cached = cache.get(cacheLookupKey);
            if (cached != null) {
                final String result = cached;
                AndroidUtilities.runOnUIThread(() -> done.run(result, false));
                return;
            }
        }
        final String promptFrom = TextUtils.isEmpty(from) ? "auto-detected source language" : from;
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(ENDPOINT);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("x-goog-api-key", apiKey);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setDoOutput(true);

                JSONObject generationConfig = new JSONObject();
                generationConfig.put("temperature", 0);
                generationConfig.put("maxOutputTokens", 4096);

                JSONObject part = new JSONObject();
                part.put("text", "Translate the following text from " + promptFrom + " to " + toFinal +
                        ". Output only the translation, without explanations, quotes or extra formatting. " +
                        "If the text is already in " + toFinal + ", return it unchanged:\n\n" + text);

                JSONObject content = new JSONObject();
                JSONArray parts = new JSONArray();
                parts.put(part);
                content.put("parts", parts);

                JSONObject body = new JSONObject();
                JSONArray contents = new JSONArray();
                contents.put(content);
                body.put("contents", contents);
                body.put("generationConfig", generationConfig);

                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                    out.flush();
                }

                int code = connection.getResponseCode();
                if (code == 429) {
                    AndroidUtilities.runOnUIThread(() -> done.run(null, true));
                    return;
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    AndroidUtilities.runOnUIThread(() -> done.run(null, false));
                    return;
                }

                StringBuilder buffer = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        buffer.append(line).append('\n');
                    }
                }
                String result = parseResult(buffer.toString());
                if (TextUtils.isEmpty(result)) {
                    AndroidUtilities.runOnUIThread(() -> done.run(null, false));
                    return;
                }
                synchronized (cache) {
                    cache.put(cacheLookupKey, result);
                }
                final String finalResult = result;
                AndroidUtilities.runOnUIThread(() -> done.run(finalResult, false));
            } catch (Exception e) {
                FileLog.e(e, false);
                AndroidUtilities.runOnUIThread(() -> done.run(null, false));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private static String parseResult(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                return null;
            }
            JSONObject candidate = candidates.optJSONObject(0);
            if (candidate == null) {
                return null;
            }
            JSONObject content = candidate.optJSONObject("content");
            if (content == null) {
                return null;
            }
            JSONArray parts = content.optJSONArray("parts");
            if (parts == null || parts.length() == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p != null) {
                    String t = p.optString("text", null);
                    if (t != null) {
                        sb.append(t);
                    }
                }
            }
            String result = sb.toString().trim();
            return TextUtils.isEmpty(result) ? null : result;
        } catch (Exception e) {
            FileLog.e(e, false);
            return null;
        }
    }
}
