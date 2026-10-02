package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

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
    private static final String KEY_REDIRECT_IP = "gemini_redirect_ip";

    private static final String GEMINI_HOST = "generativelanguage.googleapis.com";
    private static final int GEMINI_PORT = 443;

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

    public static String getRedirectIp() {
        try {
            String ip = prefs().getString(KEY_REDIRECT_IP, "");
            return ip == null ? "" : ip.trim();
        } catch (Throwable e) {
            return "";
        }
    }

    public static void setRedirectIp(String ip) {
        try {
            prefs().edit().putString(KEY_REDIRECT_IP, ip == null ? "" : ip.trim()).apply();
        } catch (Throwable ignored) {}
    }

    public static boolean isValidIpv4(String ip) {
        if (TextUtils.isEmpty(ip)) {
            return false;
        }
        String[] parts = ip.trim().split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    /** Validated redirect target, or null when disabled. Empty = direct connection. */
    private static String activeRedirectIp() {
        String ip = getRedirectIp();
        return isValidIpv4(ip) ? ip : null;
    }

    private static class HttpResult {
        int code;
        String body;
    }

    private static String readAsciiLine(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buf.write(b);
            }
        }
        if (b == -1 && buf.size() == 0) {
            return null;
        }
        return buf.toString("US-ASCII");
    }

    private static void readFully(InputStream in, byte[] b, int off, int len) throws java.io.IOException {
        while (len > 0) {
            int r = in.read(b, off, len);
            if (r == -1) {
                throw new java.io.IOException("unexpected end of stream");
            }
            off += r;
            len -= r;
        }
    }

    /**
     * Direct-dial HTTPS client with Xray freedom-style "redirect" semantics:
     * the TCP connection goes to redirectIp:443 instead of DNS, while TLS SNI
     * and certificate verification still use the real hostname. Used only when
     * the user configured a Redirect IP; otherwise the normal path applies.
     */
    private static HttpResult httpsExchange(String method, String path, String jsonBody) throws java.io.IOException {
        String redirectIp = activeRedirectIp();
        if (redirectIp == null) {
            throw new java.io.IOException("redirect not configured");
        }
        InetAddress addr = InetAddress.getByName(redirectIp);
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(addr, GEMINI_PORT), 15000);
            plain.setSoTimeout(30000);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) factory.createSocket(plain, GEMINI_HOST, GEMINI_PORT, true);
            try {
                ssl.startHandshake();
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(GEMINI_HOST, ssl.getSession())) {
                    throw new SSLException("hostname verification failed for " + GEMINI_HOST);
                }
                byte[] payload = jsonBody != null ? jsonBody.getBytes(StandardCharsets.UTF_8) : new byte[0];
                StringBuilder req = new StringBuilder(256);
                req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
                req.append("Host: ").append(GEMINI_HOST).append("\r\n");
                req.append("Content-Type: application/json\r\n");
                req.append("x-goog-api-key: ").append(getApiKey()).append("\r\n");
                req.append("Content-Length: ").append(payload.length).append("\r\n");
                req.append("Connection: close\r\n\r\n");
                OutputStream out = ssl.getOutputStream();
                out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
                out.write(payload);
                out.flush();

                InputStream in = ssl.getInputStream();
                int code = 0;
                String status = readAsciiLine(in);
                if (status != null) {
                    String[] parts = status.split(" ", 3);
                    if (parts.length >= 2) {
                        try {
                            code = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException ignored) {}
                    }
                }
                int contentLength = -1;
                boolean chunked = false;
                String header;
                while ((header = readAsciiLine(in)) != null && !header.isEmpty()) {
                    int colon = header.indexOf(':');
                    if (colon > 0) {
                        String name = header.substring(0, colon).trim().toLowerCase(Locale.US);
                        String value = header.substring(colon + 1).trim().toLowerCase(Locale.US);
                        if ("content-length".equals(name)) {
                            try {
                                contentLength = Integer.parseInt(value);
                            } catch (NumberFormatException ignored) {}
                        } else if ("transfer-encoding".equals(name) && value.contains("chunked")) {
                            chunked = true;
                        }
                    }
                }
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                if (chunked) {
                    for (;;) {
                        String chunkLine = readAsciiLine(in);
                        if (chunkLine == null) {
                            break;
                        }
                        int semi = chunkLine.indexOf(';');
                        int size;
                        try {
                            size = Integer.parseInt(chunkLine.substring(0, semi >= 0 ? semi : chunkLine.length()).trim(), 16);
                        } catch (NumberFormatException e) {
                            throw new java.io.IOException("bad chunk size");
                        }
                        if (size == 0) {
                            while ((chunkLine = readAsciiLine(in)) != null && !chunkLine.isEmpty()) {
                            }
                            break;
                        }
                        if (size < 0 || size > 16 * 1024 * 1024) {
                            throw new java.io.IOException("bad chunk size");
                        }
                        byte[] chunk = new byte[size];
                        readFully(in, chunk, 0, size);
                        body.write(chunk);
                        readAsciiLine(in);
                    }
                } else if (contentLength >= 0) {
                    if (contentLength > 16 * 1024 * 1024) {
                        throw new java.io.IOException("response too large");
                    }
                    byte[] buf = new byte[contentLength];
                    readFully(in, buf, 0, contentLength);
                    body.write(buf);
                } else {
                    byte[] tmp = new byte[8192];
                    int r;
                    while ((r = in.read(tmp)) != -1) {
                        body.write(tmp, 0, r);
                    }
                }
                HttpResult result = new HttpResult();
                result.code = code;
                result.body = new String(body.toByteArray(), StandardCharsets.UTF_8);
                return result;
            } finally {
                try {
                    ssl.close();
                } catch (Exception ignored) {}
            }
        } catch (java.io.IOException e) {
            try {
                plain.close();
            } catch (Exception ignored) {}
            throw e;
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

    private static String buildBodyJson(String prompt) throws org.json.JSONException {
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
        return body.toString();
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
            // Redirect IP set: dial it directly (Xray freedom-style redirect).
            if (activeRedirectIp() != null) {
                try {
                    HttpResult res = httpsExchange("POST", "/v1beta/models/" + model + ":generateContent", buildBodyJson(prompt));
                    if (res.code == 429) {
                        AndroidUtilities.runOnUIThread(() -> done.run(null, true));
                        return;
                    }
                    if (res.code != 200) {
                        AndroidUtilities.runOnUIThread(() -> done.run(null, false));
                        return;
                    }
                    String result = parseResult(res.body);
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
                }
                return;
            }
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

                byte[] payload = buildBodyJson(prompt).getBytes(StandardCharsets.UTF_8);
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
            // Redirect IP set: dial it directly (Xray freedom-style redirect).
            if (activeRedirectIp() != null) {
                try {
                    HttpResult res = httpsExchange("GET", "/v1beta/models?pageSize=100", null);
                    if (res.code != HttpURLConnection.HTTP_OK) {
                        AndroidUtilities.runOnUIThread(() -> done.run(null));
                        return;
                    }
                    ArrayList<String> models = parseModels(res.body);
                    if (models == null) {
                        AndroidUtilities.runOnUIThread(() -> done.run(null));
                        return;
                    }
                    synchronized (GeminiTranslator.class) {
                        cachedModels = new ArrayList<>(models);
                        cachedModelsTime = System.currentTimeMillis();
                    }
                    final ArrayList<String> result = models;
                    AndroidUtilities.runOnUIThread(() -> done.run(result));
                } catch (Exception e) {
                    FileLog.e(e, false);
                    AndroidUtilities.runOnUIThread(() -> done.run(null));
                }
                return;
            }
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
                ArrayList<String> models = parseModels(buffer.toString());
                if (models == null) {
                    AndroidUtilities.runOnUIThread(() -> done.run(null));
                    return;
                }
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

    private static ArrayList<String> parseModels(String json) {
        try {
            ArrayList<String> models = new ArrayList<>();
            JSONObject root = new JSONObject(json);
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
                return null;
            }
            Collections.sort(models);
            return models;
        } catch (Exception e) {
            FileLog.e(e, false);
            return null;
        }
    }

    public static class TestResult {
        public boolean ok;
        public int httpCode = -1;
        public String translated;
        public String error;
        public final ArrayList<String> log = new ArrayList<>();
    }

    private static void tlog(ArrayList<String> log, String line) {
        if (log != null) {
            log.add(line);
        }
    }

    /**
     * End-to-end self test: fa -> en translation of a fixed sentence, with a
     * step-by-step log (settings, network/TLS, HTTP status, server body) for
     * diagnosis. Works even when the master toggle is off; needs the API key.
     * Always async; done runs on the UI thread, never null result object.
     */
    public static void testConnection(Utilities.Callback<TestResult> done) {
        final TestResult res = new TestResult();
        final String apiKey = getApiKey();
        final boolean enabled = isEnabled();
        final String redirect = activeRedirectIp();
        final String model = getModel();
        final String prompt = getPrompt();
        new Thread(() -> {
            tlog(res.log, "enabled=" + enabled);
            if (TextUtils.isEmpty(apiKey)) {
                res.error = "API key is empty";
                tlog(res.log, "ERROR: API key is empty, enter it in settings");
                AndroidUtilities.runOnUIThread(() -> done.run(res));
                return;
            }
            tlog(res.log, "api key: length=" + apiKey.length() + " (" + getMaskedKey() + ")");
            tlog(res.log, "route: " + (redirect != null ? "redirect TCP -> " + redirect + ":443, TLS SNI=" + GEMINI_HOST : "direct (system DNS)"));
            tlog(res.log, "model=" + model);
            tlog(res.log, "prompt: " + (isCustomPrompt() ? "custom" : "default") + " len=" + prompt.length());
            String sample = "سلام، این یک آزمایش اتصال است";
            String body;
            try {
                body = buildBodyJson(buildPrompt(sample, "fa", "en"));
                tlog(res.log, "request body bytes=" + body.getBytes(StandardCharsets.UTF_8).length);
            } catch (Exception e) {
                res.error = "prompt build failed: " + e;
                tlog(res.log, "ERROR: " + res.error);
                AndroidUtilities.runOnUIThread(() -> done.run(res));
                return;
            }
            if (redirect != null) {
                tlog(res.log, "dial: TCP connect " + redirect + ":443 ...");
                try {
                    HttpResult r = httpsExchange("POST", "/v1beta/models/" + model + ":generateContent", body);
                    res.httpCode = r.code;
                    tlog(res.log, "TLS handshake + hostname verify: OK");
                    tlog(res.log, "HTTP status=" + r.code);
                    tlog(res.log, "response body: " + snippet(r.body));
                    finishTest(res, r.code == 200 ? parseResult(r.body) : null, done);
                } catch (Exception e) {
                    res.error = e.getClass().getSimpleName() + ": " + e.getMessage();
                    tlog(res.log, "ERROR: " + res.error);
                    AndroidUtilities.runOnUIThread(() -> done.run(res));
                }
                return;
            }
            tlog(res.log, "dial: system DNS + TLS to " + GEMINI_HOST + " ...");
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://" + GEMINI_HOST + "/v1beta/models/" + model + ":generateContent");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("x-goog-api-key", apiKey);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setDoOutput(true);
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                    out.flush();
                }
                int code = connection.getResponseCode();
                res.httpCode = code;
                tlog(res.log, "HTTP status=" + code);
                String respBody = "";
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(code == 200 ? connection.getInputStream() : connection.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    StringBuilder sb = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    respBody = sb.toString();
                } catch (Exception ignored) {}
                tlog(res.log, "response body: " + snippet(respBody));
                finishTest(res, code == 200 ? parseResult(respBody) : null, done);
            } catch (Exception e) {
                res.error = e.getClass().getSimpleName() + ": " + e.getMessage();
                tlog(res.log, "ERROR: " + res.error);
                AndroidUtilities.runOnUIThread(() -> done.run(res));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private static void finishTest(TestResult res, String translated, Utilities.Callback<TestResult> done) {
        if (!TextUtils.isEmpty(translated)) {
            res.ok = true;
            res.translated = translated;
            tlog(res.log, "translated: " + translated);
            tlog(res.log, "RESULT: OK");
        } else {
            res.ok = false;
            if (res.httpCode == 429) {
                res.error = "rate limited (HTTP 429)";
            } else if (res.error == null) {
                res.error = res.httpCode <= 0 ? "no response" : "HTTP " + res.httpCode + " (bad key/model or blocked?)";
            }
            tlog(res.log, "RESULT: FAILED (" + res.error + ")");
        }
        AndroidUtilities.runOnUIThread(() -> done.run(res));
    }

    private static String snippet(String body) {
        if (body == null) {
            return "<empty>";
        }
        String oneLine = body.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > 500) {
            return oneLine.substring(0, 500) + " ...[truncated, total " + body.length() + " chars]";
        }
        return oneLine.isEmpty() ? "<empty>" : oneLine;
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
