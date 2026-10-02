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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final String KEY_MODEL = "gemini_translate_model";
    private static final String KEY_PROMPT = "gemini_translate_prompt";

    public static final String[] MODELS = new String[]{
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-2.5-flash"
    };
    public static final String DEFAULT_MODEL = MODELS[0];
    public static final String DEFAULT_PROMPT =
            "Translate the following text from {from} to {to}. " +
            "Output only the translation, without explanations, quotes or extra formatting. " +
            "If the text is already in {to}, return it unchanged:\n\n{text}";

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

    public static String getModel() {
        try {
            String model = prefs().getString(KEY_MODEL, DEFAULT_MODEL);
            if (TextUtils.isEmpty(model)) {
                return DEFAULT_MODEL;
            }
            return model;
        } catch (Throwable e) {
            return DEFAULT_MODEL;
        }
    }

    public static void setModel(String model) {
        try {
            prefs().edit().putString(KEY_MODEL, TextUtils.isEmpty(model) ? DEFAULT_MODEL : model).apply();
        } catch (Throwable ignored) {}
    }

    public static String getPrompt() {
        try {
            String prompt = prefs().getString(KEY_PROMPT, DEFAULT_PROMPT);
            if (TextUtils.isEmpty(prompt)) {
                return DEFAULT_PROMPT;
            }
            return prompt;
        } catch (Throwable e) {
            return DEFAULT_PROMPT;
        }
    }

    public static void setPrompt(String prompt) {
        try {
            prefs().edit().putString(KEY_PROMPT, prompt == null ? "" : prompt).apply();
        } catch (Throwable ignored) {}
    }

    public static boolean isCustomPrompt() {
        try {
            return prefs().contains(KEY_PROMPT) && !TextUtils.isEmpty(prefs().getString(KEY_PROMPT, ""));
        } catch (Throwable e) {
            return false;
        }
    }

    private static String buildPrompt(String text, String from, String to) {
        String template = getPrompt();
        if (!template.contains("{text}")) {
            template = DEFAULT_PROMPT;
        }
        if (TextUtils.isEmpty(from)) {
            from = "auto-detected source language";
        }
        return template.replace("{from}", from).replace("{to}", to).replace("{text}", text);
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
        final String prompt = buildPrompt(text, from, toFinal);
        final String model = getModel();
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent");
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
                part.put("text", prompt);

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

    private static ArrayList<String> cachedModels;
    private static long cachedModelsTime;

    /**
     * Fetch the model list from Google (models.list). Always async; done runs
     * on the UI thread with the model names (without "models/" prefix), or
     * null on failure (caller should fall back to MODELS).
     */
    public static void fetchModels(Utilities.Callback<ArrayList<String>> done) {
        if (done == null) {
            return;
        }
        final String apiKey = getApiKey();
        if (TextUtils.isEmpty(apiKey)) {
            AndroidUtilities.runOnUIThread(() -> done.run(null));
            return;
        }
        synchronized (GeminiTranslator.class) {
            if (cachedModels != null && System.currentTimeMillis() - cachedModelsTime < 24 * 60 * 60 * 1000L) {
                final ArrayList<String> copy = new ArrayList<>(cachedModels);
                AndroidUtilities.runOnUIThread(() -> done.run(copy));
                return;
            }
        }
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("x-goog-api-key", apiKey);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    AndroidUtilities.runOnUIThread(() -> done.run(null));
                    return;
                }
                StringBuilder buffer = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        buffer.append(line).append('\n');
                    }
                }
                ArrayList<String> models = new ArrayList<>();
                JSONObject root = new JSONObject(buffer.toString());
                JSONArray list = root.optJSONArray("models");
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject m = list.optJSONObject(i);
                        if (m == null) {
                            continue;
                        }
                        String name = m.optString("name", "");
                        if (name.startsWith("models/")) {
                            name = name.substring("models/".length());
                        }
                        if (TextUtils.isEmpty(name)) {
                            continue;
                        }
                        boolean canGenerate = false;
                        JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                        if (methods != null) {
                            for (int j = 0; j < methods.length(); j++) {
                                if ("generateContent".equals(methods.optString(j))) {
                                    canGenerate = true;
                                    break;
                                }
                            }
                        }
                        if (canGenerate && !models.contains(name)) {
                            models.add(name);
                        }
                    }
                }
                if (models.isEmpty()) {
                    AndroidUtilities.runOnUIThread(() -> done.run(null));
                    return;
                }
                Collections.sort(models);
                synchronized (GeminiTranslator.class) {
                    cachedModels = new ArrayList<>(models);
                    cachedModelsTime = System.currentTimeMillis();
                }
                final ArrayList<String> result = models;
                AndroidUtilities.runOnUIThread(() -> done.run(result));
            } catch (Exception e) {
                FileLog.e(e, false);
                AndroidUtilities.runOnUIThread(() -> done.run(null));
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
