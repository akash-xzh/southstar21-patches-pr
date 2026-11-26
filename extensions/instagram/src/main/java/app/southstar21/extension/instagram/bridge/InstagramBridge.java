package app.southstar21.extension.instagram.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("unused")
public final class InstagramBridge {
    private static final String TAG = "InstagramBridge";
    private static final int PORT = 48913;
    private static final String IG_API = "https://i.instagram.com/api/v1";
    private static final String IG_APP_ID = "567067343352427";
    private static final String SHORTCODE_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final ExecutorService EXEC = Executors.newCachedThreadPool();
    private static volatile Context appContext;
    private static volatile String capturedBearer;
    private static volatile String capturedMid;
    private static final java.util.concurrent.ConcurrentHashMap<String, String> capturedHeaders =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Native Instagram SyncHttpService + Tigon state
    private static volatile java.lang.reflect.Method syncSendMethod;
    private static volatile Class<?> syncReqClass;
    private static volatile Class<?> igReqClass;
    private static volatile Class<?> headerClass;
    private static volatile Object capturedPolicy;
    private static volatile Integer capturedMethodGet;
    private static volatile Integer capturedMethodPost;

    private InstagramBridge() {}

    public static void captureSyncRequest(Object syncReq) {
        if (syncReq == null) return;
        try {
            if (syncSendMethod == null) {
                Class<?> reqCls = syncReq.getClass();
                syncReqClass = reqCls;
                StackTraceElement[] st = Thread.currentThread().getStackTrace();
                for (int i = 0; i < st.length; i++) {
                    if (InstagramBridge.class.getName().equals(st[i].getClassName())
                            && "captureSyncRequest".equals(st[i].getMethodName())
                            && i + 1 < st.length) {
                        Class<?> syncCls = Class.forName(st[i + 1].getClassName(), false, reqCls.getClassLoader());
                        for (java.lang.reflect.Method m : syncCls.getDeclaredMethods()) {
                            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                                    && m.getParameterTypes().length == 1
                                    && m.getParameterTypes()[0] == reqCls) {
                                m.setAccessible(true);
                                syncSendMethod = m;
                                break;
                            }
                        }
                        break;
                    }
                }
            }
            Object igReq = null;
            Object policy = null;
            for (java.lang.reflect.Field f : syncReq.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(syncReq);
                if (v == null) continue;
                String fn = f.getType().getName();
                if (fn.startsWith("java.") || fn.equals("boolean")) continue;
                if (hasUriField(v)) igReq = v;
                else if (hasStringField(v, "i.instagram.com") || v.getClass().getDeclaredFields().length > 10) policy = v;
            }
            if (igReq != null && policy != null) {
                captureNativeRequest(igReq, policy);
            }
        } catch (Throwable ignored) {}
    }

    public static void captureNativeRequest(Object igReq, Object policy) {
        if (igReq == null || policy == null) return;
        try {
            if (capturedPolicy == null) capturedPolicy = policy;
            if (igReqClass == null) igReqClass = igReq.getClass();
            String uriStr = null;
            Integer methodInt = null;
            Object bodyObj = null;
            java.util.List<?> hdrs = null;
            for (java.lang.reflect.Field f : igReq.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(igReq);
                if (v == null) continue;
                if (v instanceof java.net.URI && uriStr == null) {
                    uriStr = v.toString();
                } else if (v instanceof Integer && methodInt == null && !java.lang.reflect.Modifier.isPublic(f.getModifiers()) == false) {
                    if (java.lang.reflect.Modifier.isFinal(f.getModifiers())) methodInt = (Integer) v;
                } else if (v instanceof java.util.List) {
                    hdrs = (java.util.List<?>) v;
                } else if (f.getType().isInterface() && hasInputStreamMethod(f.getType())) {
                    bodyObj = v;
                }
            }
            if (hdrs != null && !hdrs.isEmpty() && headerClass == null) {
                Object first = hdrs.get(0);
                if (first != null) headerClass = first.getClass();
            }
            if (uriStr != null && uriStr.contains("instagram.com/api/v1/")) {
                capturedPolicy = policy;
                if (methodInt != null) {
                    if (bodyObj == null && capturedMethodGet == null) capturedMethodGet = methodInt;
                    if (bodyObj != null && capturedMethodPost == null) capturedMethodPost = methodInt;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static boolean hasUriField(Object o) {
        for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
            if (f.getType() == java.net.URI.class) return true;
        }
        return false;
    }

    private static boolean hasStringField(Object o, String sub) {
        return false;
    }

    private static boolean hasInputStreamMethod(Class<?> cls) {
        for (java.lang.reflect.Method m : cls.getMethods()) {
            if (m.getParameterTypes().length == 0 && m.getReturnType() == InputStream.class) return true;
        }
        return false;
    }

    public static void captureHeader(String name, String value) {
        if (name == null || value == null || value.isEmpty()) return;
        if ("Authorization".equalsIgnoreCase(name)) {
            captureToken(value);
            return;
        }
        if ("X-MID".equalsIgnoreCase(name)) {
            capturedMid = value;
        }
        String lower = name.toLowerCase(java.util.Locale.US);
        if (lower.equals("user-agent")
                || lower.startsWith("x-ig-")
                || lower.startsWith("ig-")
                || lower.startsWith("x-bloks-")
                || lower.equals("accept-language")
                || lower.equals("x-fb-connection-type")) {
            capturedHeaders.put(name, value);
        }
    }

    public static void captureToken(String token) {
        if (token != null && token.startsWith("Bearer IGT:2:") && token.length() > 20) {
            if (!token.equals(capturedBearer)) {
                capturedBearer = token;
                Context ctx = appContext;
                if (ctx != null) {
                    try {
                        ctx.getSharedPreferences("ig_bridge_cache", Context.MODE_PRIVATE)
                                .edit().putString("bearer", token).apply();
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    public static void install(Activity activity) {
        if (activity != null && appContext == null) {
            appContext = activity.getApplicationContext();
        }
        startServer();
    }

    public static void install(Context context) {
        if (context != null && appContext == null) {
            appContext = context.getApplicationContext();
        }
        startServer();
    }

    private static void startServer() {
        if (!STARTED.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try (ServerSocket server = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))) {
                Log.i(TAG, "Listening on 127.0.0.1:" + PORT);
                while (true) {
                    Socket client = server.accept();
                    EXEC.submit(() -> handleClient(client));
                }
            } catch (Throwable ex) {
                STARTED.set(false);
                Log.e(TAG, "Server failed", ex);
            }
        }, "ig-bridge-http");
        t.setDaemon(true);
        t.start();
    }

    private static void handleClient(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(20000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String reqLine = reader.readLine();
            if (reqLine == null || reqLine.isEmpty()) return;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // drain headers
            }
            String[] parts = reqLine.split(" ");
            String target = parts.length > 1 ? parts[1] : "/";
            int qIdx = target.indexOf('?');
            String path = qIdx >= 0 ? target.substring(0, qIdx) : target;
            Map<String, String> params = parseQuery(qIdx >= 0 ? target.substring(qIdx + 1) : "");

            JSONObject respJson;
            int httpStatus = 200;
            try {
                if ("/ping".equals(path)) {
                    respJson = new JSONObject().put("ok", true).put("bridge", "instagram");
                } else if ("/auth_check".equals(path)) {
                    respJson = handleAuthCheck();
                } else if ("/self_check".equals(path)) {
                    respJson = handleSelfCheck();
                } else if ("/profile".equals(path)) {
                    respJson = handleProfile(params);
                } else if ("/follow".equals(path)) {
                    respJson = handleFollow(params);
                } else if ("/like".equals(path)) {
                    respJson = handleLike(params);
                } else {
                    httpStatus = 404;
                    respJson = new JSONObject().put("ok", false).put("error", "unknown endpoint: " + path);
                }
            } catch (Throwable ex) {
                httpStatus = 500;
                Throwable root = ex;
                while (root.getCause() != null && root.getMessage() == null) root = root.getCause();
                Log.e(TAG, "Request failed: " + target, root);
                respJson = new JSONObject()
                        .put("ok", false)
                        .put("error", root.getClass().getSimpleName() + ": " + String.valueOf(root.getMessage()));
            }

            byte[] body = respJson.toString().getBytes(StandardCharsets.UTF_8);
            String hdr = "HTTP/1.1 " + httpStatus + " OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(hdr.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private static final class AuthInfo {
        String bearer;
        String myUid;
        String prefFile;
    }

    private static AuthInfo findAuth() {
        String live = capturedBearer;
        if (live != null && live.startsWith("Bearer IGT:2:") && live.length() > 20) {
            AuthInfo info = new AuthInfo();
            info.bearer = live;
            info.prefFile = "memory_hook";
            info.myUid = extractUidFromIgt(live);
            return info;
        }
        Context ctx = appContext;
        if (ctx == null) return null;
        try {
            String cached = ctx.getSharedPreferences("ig_bridge_cache", Context.MODE_PRIVATE)
                    .getString("bearer", null);
            if (cached != null && cached.startsWith("Bearer IGT:2:") && cached.length() > 20) {
                capturedBearer = cached;
                AuthInfo info = new AuthInfo();
                info.bearer = cached;
                info.prefFile = "ig_bridge_cache";
                info.myUid = extractUidFromIgt(cached);
                return info;
            }
        } catch (Throwable ignored) {}
        File prefsDir = new File(ctx.getApplicationInfo().dataDir, "shared_prefs");
        File[] files = prefsDir.listFiles((d, name) -> name.endsWith(".xml"));
        if (files == null) return null;

        // Prefer AuthHeaderPrefs first
        java.util.Arrays.sort(files, (a, b) -> {
            boolean aAuth = a.getName().contains("AuthHeader");
            boolean bAuth = b.getName().contains("AuthHeader");
            if (aAuth != bAuth) return aAuth ? -1 : 1;
            return a.getName().compareTo(b.getName());
        });

        java.util.List<String> debugPrefs = new java.util.ArrayList<>();
        for (File f : files) {
            String prefName = f.getName().substring(0, f.getName().length() - 4);
            try {
                SharedPreferences sp = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE);
                Map<String, ?> all = sp.getAll();
                if (prefName.toLowerCase().contains("auth") || prefName.toLowerCase().contains("session")) {
                    debugPrefs.add(prefName + ":" + all.keySet());
                }
                for (Map.Entry<String, ?> e : all.entrySet()) {
                    Object v = e.getValue();
                    if (!(v instanceof String)) continue;
                    String s = ((String) v).trim();
                    int idx = s.indexOf("IGT:2:");
                    if (idx >= 0) {
                        String token = s.startsWith("Bearer ") ? s : ("Bearer " + s.substring(idx));
                        AuthInfo info = new AuthInfo();
                        info.bearer = token.trim();
                        info.prefFile = prefName + "#" + e.getKey();
                        info.myUid = extractUidFromIgt(info.bearer);
                        return info;
                    }
                    // Instagram often stores the raw Base64 JSON without the "Bearer IGT:2:" prefix
                    if (s.startsWith("eyJ") && s.length() > 30) {
                        try {
                            byte[] decoded = Base64.decode(s, Base64.DEFAULT);
                            String jsonStr = new String(decoded, StandardCharsets.UTF_8);
                            if (jsonStr.contains("ds_user_id") || jsonStr.contains("sessionid")) {
                                AuthInfo info = new AuthInfo();
                                info.bearer = "Bearer IGT:2:" + s;
                                info.prefFile = prefName + "#" + e.getKey();
                                info.myUid = extractUidFromIgt(info.bearer);
                                return info;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }
        lastDebugPrefs = debugPrefs.toString();
        return null;
    }

    private static volatile String lastDebugPrefs = "";

    private static String extractUidFromIgt(String bearer) {
        try {
            int idx = bearer.indexOf("IGT:2:");
            if (idx < 0) return "";
            String b64 = bearer.substring(idx + 6).trim();
            byte[] decoded = Base64.decode(b64, Base64.DEFAULT);
            JSONObject obj = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            return obj.optString("ds_user_id", "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String userAgent() {
        String ver = "456.1.0.56.78";
        try {
            if (appContext != null) {
                PackageInfo pi = appContext.getPackageManager()
                        .getPackageInfo(appContext.getPackageName(), 0);
                if (pi != null && pi.versionName != null) ver = pi.versionName;
            }
        } catch (Throwable ignored) {}
        return "Instagram " + ver + " Android ("
                + Build.VERSION.SDK_INT + "/" + Build.VERSION.RELEASE
                + "; 420dpi; 1080x2400; "
                + Build.MANUFACTURER + "; " + Build.MODEL + "; "
                + Build.DEVICE + "; " + Build.HARDWARE + "; en_US)";
    }

    private static JSONObject handleAuthCheck() throws Exception {
        AuthInfo auth = findAuth();
        if (auth == null) {
            return new JSONObject()
                    .put("ok", false)
                    .put("error", "no IGT:2 token in shared_prefs")
                    .put("debug_prefs", lastDebugPrefs);
        }
        String masked = auth.bearer.length() > 24
                ? auth.bearer.substring(0, 20) + "..."
                : "***";
        boolean nativeReady = syncSendMethod != null && syncReqClass != null
                && igReqClass != null && capturedPolicy != null && headerClass != null;
        return new JSONObject()
                .put("ok", true)
                .put("pref_file", auth.prefFile)
                .put("my_uid", auth.myUid)
                .put("token_prefix", masked)
                .put("transport", nativeReady ? "native_tigon" : "httpurlconnection_fallback")
                .put("has_get", capturedMethodGet != null)
                .put("has_post", capturedMethodPost != null)
                .put("captured_headers", capturedHeaders.size())
                .put("ua", capturedHeaders.getOrDefault("User-Agent", userAgent()));
    }

    private static final class AccountLockedException extends Exception {
        final int httpCode;
        final String raw;
        AccountLockedException(int httpCode, String msg, String raw) {
            super(msg);
            this.httpCode = httpCode;
            this.raw = raw;
        }
    }

    private static boolean isLockedError(int code, JSONObject j, String body) {
        String msg = j != null ? j.optString("message", "") : "";
        String errType = j != null ? j.optString("error_type", "") : "";
        String combined = (msg + " " + errType + " " + (body == null ? "" : body)).toLowerCase();
        if (combined.contains("checkpoint_required")
                || combined.contains("challenge_required")
                || combined.contains("login_required")
                || combined.contains("user_has_logged_out")
                || combined.contains("consent_required")
                || combined.contains("account_suspended")) {
            return true;
        }
        return code == 401 || code == 403;
    }

    private static JSONObject lockedJson(int code, String msg, String raw) throws Exception {
        return new JSONObject()
                .put("ok", false)
                .put("locked", true)
                .put("http_code", code)
                .put("error", msg == null || msg.isEmpty() ? "checkpoint_required" : msg)
                .put("raw", truncate(raw, 300));
    }

    private static JSONObject handleSelfCheck() throws Exception {
        AuthInfo auth = requireAuth();
        HttpResult r = request(auth, "GET", IG_API + "/accounts/current_user/?edit=true", null);
        JSONObject j = parseJson(r.body);
        if (r.code >= 200 && r.code < 300 && j != null && "ok".equalsIgnoreCase(j.optString("status"))) {
            JSONObject u = j.optJSONObject("user");
            return new JSONObject()
                    .put("ok", true)
                    .put("locked", false)
                    .put("my_uid", auth.myUid)
                    .put("username", u != null ? u.optString("username", "") : "");
        }
        boolean locked = isLockedError(r.code, j, r.body);
        String msg = j != null ? j.optString("message", "") : "";
        return new JSONObject()
                .put("ok", false)
                .put("locked", locked)
                .put("http_code", r.code)
                .put("error", msg.isEmpty() ? ("http_" + r.code) : msg)
                .put("raw", truncate(r.body, 300));
    }

    private static JSONObject handleProfile(Map<String, String> params) throws Exception {
        AuthInfo auth = requireAuth();
        String uid = params.getOrDefault("uid", "").trim();
        String username = cleanUsername(params.getOrDefault("username", ""));
        JSONObject user;
        try {
            user = resolveUser(auth, uid, username);
        } catch (AccountLockedException ale) {
            return lockedJson(ale.httpCode, ale.getMessage(), ale.raw);
        }
        if (user == null) {
            return new JSONObject().put("ok", false).put("error", "user not found");
        }
        String pk = user.optString("pk", user.optString("id", ""));
        if (pk.isEmpty() && user.has("pk_id")) pk = user.optString("pk_id", "");
        JSONObject fs = friendshipShow(auth, pk);
        return new JSONObject()
                .put("ok", true)
                .put("pk", pk)
                .put("username", user.optString("username", username))
                .put("following", fs != null && fs.optBoolean("following", false));
    }

    private static JSONObject handleFollow(Map<String, String> params) throws Exception {
        AuthInfo auth = requireAuth();
        String uid = params.getOrDefault("uid", "").trim();
        String username = cleanUsername(params.getOrDefault("username", ""));
        String type = params.getOrDefault("type", "1").trim();
        int verifyDelay = parseInt(params.get("verify_delay"), 2500);

        if (uid.isEmpty()) {
            JSONObject user;
            try {
                user = resolveUser(auth, "", username);
            } catch (AccountLockedException ale) {
                return lockedJson(ale.httpCode, ale.getMessage(), ale.raw);
            }
            if (user == null) {
                return new JSONObject().put("ok", false).put("error", "user not found");
            }
            uid = user.optString("pk", user.optString("id", ""));
            if (uid.isEmpty() && user.has("pk_id")) uid = user.optString("pk_id", "");
        }
        if (uid.isEmpty()) {
            return new JSONObject().put("ok", false).put("error", "missing uid/username");
        }

        boolean wantFollow = "1".equals(type);
        String actionPath = wantFollow
                ? "/friendships/create/" + uid + "/"
                : "/friendships/destroy/" + uid + "/";
        Map<String, String> form = new LinkedHashMap<>();
        form.put("user_id", uid);
        if (!auth.myUid.isEmpty()) form.put("_uid", auth.myUid);
        form.put("container_module", "profile");

        HttpResult res = request(auth, "POST", IG_API + actionPath, form);
        JSONObject json = parseJson(res.body);
        String apiStatus = json != null ? json.optString("status", "") : "";
        if (res.code < 200 || res.code >= 300 || !"ok".equalsIgnoreCase(apiStatus)) {
            if (isLockedError(res.code, json, res.body)) {
                return lockedJson(res.code, json != null ? json.optString("message", "") : "", res.body);
            }
            return new JSONObject()
                    .put("ok", false)
                    .put("reverted", false)
                    .put("http_code", res.code)
                    .put("status", apiStatus)
                    .put("pk", uid)
                    .put("raw", truncate(res.body, 300));
        }

        if (verifyDelay > 0) Thread.sleep(verifyDelay);

        JSONObject fs = friendshipShow(auth, uid);
        boolean following = fs != null && fs.optBoolean("following", false);
        boolean persisted = wantFollow ? following : !following;
        return new JSONObject()
                .put("ok", persisted)
                .put("verified", persisted)
                .put("reverted", wantFollow && !persisted)
                .put("following", following)
                .put("pk", uid)
                .put("status", apiStatus);
    }

    private static JSONObject handleLike(Map<String, String> params) throws Exception {
        AuthInfo auth = requireAuth();
        String mediaId = params.getOrDefault("media_id", "").trim();
        String shortcode = params.getOrDefault("shortcode", "").trim();
        String url = params.getOrDefault("url", "").trim();
        String type = params.getOrDefault("type", "1").trim();
        int verifyDelay = parseInt(params.get("verify_delay"), 2000);

        if (shortcode.isEmpty() && !url.isEmpty()) {
            shortcode = extractShortcode(url);
        }
        if (mediaId.isEmpty() && !shortcode.isEmpty()) {
            mediaId = shortcodeToMediaId(shortcode);
        }
        if (mediaId.isEmpty()) {
            return new JSONObject().put("ok", false).put("error", "missing media_id/shortcode/url");
        }

        // Pre-fetch media info like opening the post/reel before liking
        try {
            request(auth, "GET", IG_API + "/media/" + mediaId + "/info/", null);
        } catch (Throwable ignored) {}

        boolean isReel = url.toLowerCase(java.util.Locale.US).contains("/reel");
        boolean wantLike = "1".equals(type);
        String actionPath = wantLike
                ? "/media/" + mediaId + "/like/"
                : "/media/" + mediaId + "/unlike/";
        Map<String, String> form = new LinkedHashMap<>();
        form.put("media_id", mediaId);
        if (!auth.myUid.isEmpty()) form.put("_uid", auth.myUid);
        form.put("d", "0");
        form.put("module_name", isReel ? "clips_viewer_clips_tab" : "feed_timeline");

        HttpResult res = request(auth, "POST", IG_API + actionPath, form);
        JSONObject json = parseJson(res.body);
        String apiStatus = json != null ? json.optString("status", "") : "";
        if (res.code < 200 || res.code >= 300 || !"ok".equalsIgnoreCase(apiStatus)) {
            if (isLockedError(res.code, json, res.body)) {
                return lockedJson(res.code, json != null ? json.optString("message", "") : "", res.body);
            }
            return new JSONObject()
                    .put("ok", false)
                    .put("reverted", false)
                    .put("http_code", res.code)
                    .put("status", apiStatus)
                    .put("media_id", mediaId)
                    .put("shortcode", shortcode)
                    .put("raw", truncate(res.body, 300));
        }

        if (verifyDelay > 0) Thread.sleep(verifyDelay);

        HttpResult infoRes = request(auth, "GET", IG_API + "/media/" + mediaId + "/info/", null);
        JSONObject infoJson = parseJson(infoRes.body);
        boolean hasLiked = false;
        boolean checked = false;
        if (infoJson != null) {
            JSONArray items = infoJson.optJSONArray("items");
            if (items != null && items.length() > 0) {
                JSONObject item = items.optJSONObject(0);
                if (item != null && item.has("has_liked")) {
                    hasLiked = item.optBoolean("has_liked", false);
                    checked = true;
                }
            }
        }
        boolean persisted = checked && (wantLike ? hasLiked : !hasLiked);
        return new JSONObject()
                .put("ok", persisted)
                .put("verified", persisted)
                .put("reverted", wantLike && checked && !hasLiked)
                .put("has_liked", hasLiked)
                .put("media_id", mediaId)
                .put("shortcode", shortcode)
                .put("status", apiStatus);
    }

    private static AuthInfo requireAuth() {
        AuthInfo auth = findAuth();
        if (auth == null || auth.bearer == null || auth.bearer.isEmpty()) {
            throw new IllegalStateException("Instagram session token not found");
        }
        return auth;
    }

    private static JSONObject resolveUser(AuthInfo auth, String uid, String username) throws Exception {
        if (!uid.isEmpty()) {
            HttpResult r = request(auth, "GET", IG_API + "/users/" + uid + "/info/", null);
            JSONObject j = parseJson(r.body);
            if (isLockedError(r.code, j, r.body)) {
                throw new AccountLockedException(r.code, j != null ? j.optString("message", "") : "", r.body);
            }
            if (j != null && j.has("user")) return j.optJSONObject("user");
        }
        if (!username.isEmpty()) {
            HttpResult r = request(auth, "GET",
                    IG_API + "/users/" + URLEncoder.encode(username, "UTF-8") + "/usernameinfo/", null);
            JSONObject j = parseJson(r.body);
            if (isLockedError(r.code, j, r.body)) {
                throw new AccountLockedException(r.code, j != null ? j.optString("message", "") : "", r.body);
            }
            if (j != null && j.has("user")) return j.optJSONObject("user");
        }
        return null;
    }

    private static JSONObject friendshipShow(AuthInfo auth, String uid) throws Exception {
        if (uid == null || uid.isEmpty()) return null;
        HttpResult r = request(auth, "GET", IG_API + "/friendships/show/" + uid + "/", null);
        return parseJson(r.body);
    }

    private static final class HttpResult {
        int code;
        String body;
    }

    private static HttpResult request(AuthInfo auth, String method, String urlStr, Map<String, String> form)
            throws Exception {
        if (syncSendMethod != null && syncReqClass != null && igReqClass != null
                && capturedPolicy != null && headerClass != null) {
            try {
                HttpResult nr = nativeRequest(auth, method, urlStr, form);
                if (nr != null) return nr;
            } catch (Throwable ex) {
                Log.w(TAG, "Native SyncHttpService fallback to HttpURLConnection: " + ex.getMessage());
            }
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Authorization", auth.bearer);
        if (capturedMid != null && !capturedMid.isEmpty()) {
            conn.setRequestProperty("X-MID", capturedMid);
        }
        conn.setRequestProperty("User-Agent", capturedHeaders.getOrDefault("User-Agent", userAgent()));
        conn.setRequestProperty("X-IG-App-ID", capturedHeaders.getOrDefault("X-IG-App-ID", IG_APP_ID));
        conn.setRequestProperty("X-IG-Connection-Type", "WIFI");
        conn.setRequestProperty("Accept-Language", capturedHeaders.getOrDefault("Accept-Language", "en-US"));
        for (Map.Entry<String, String> h : capturedHeaders.entrySet()) {
            String k = h.getKey();
            if ("Authorization".equalsIgnoreCase(k) || "User-Agent".equalsIgnoreCase(k)
                    || "X-IG-App-ID".equalsIgnoreCase(k) || "Accept-Language".equalsIgnoreCase(k)) continue;
            conn.setRequestProperty(k, h.getValue());
        }
        if (form != null && "POST".equals(method)) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8"));
                sb.append('=');
                sb.append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
            byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
            }
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = "";
        if (is != null) {
            try (InputStream in = is) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                body = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        HttpResult res = new HttpResult();
        res.code = code;
        res.body = body;
        return res;
    }

    private static Object newHeader(String name, String value) throws Exception {
        Object h = headerClass.getDeclaredConstructor().newInstance();
        int strIdx = 0;
        for (java.lang.reflect.Field f : headerClass.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() == String.class) {
                f.setAccessible(true);
                if (strIdx == 0) f.set(h, name);
                else if (strIdx == 1) f.set(h, value);
                strIdx++;
            }
        }
        return h;
    }

    private static HttpResult nativeRequest(AuthInfo auth, String method, String urlStr, Map<String, String> form)
            throws Exception {
        boolean isPost = "POST".equalsIgnoreCase(method);
        Integer methodInt = isPost
                ? (capturedMethodPost != null ? capturedMethodPost : Integer.valueOf(1))
                : (capturedMethodGet != null ? capturedMethodGet : Integer.valueOf(2));

        java.lang.reflect.Constructor<?> igCtor = null;
        for (java.lang.reflect.Constructor<?> c : igReqClass.getDeclaredConstructors()) {
            if (c.getParameterTypes().length == 9) {
                igCtor = c;
                break;
            }
        }
        if (igCtor == null) return null;
        igCtor.setAccessible(true);
        Class<?>[] pTypes = igCtor.getParameterTypes();
        Class<?> bodyIface = pTypes[1];

        Object bodyProxy = null;
        if (isPost && form != null) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8"));
                sb.append('=');
                sb.append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
            final byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
            final Object contentTypeHdr = newHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            bodyProxy = java.lang.reflect.Proxy.newProxyInstance(
                    bodyIface.getClassLoader(),
                    new Class<?>[]{bodyIface},
                    (proxy, m, args) -> {
                        if (m.getReturnType() == InputStream.class) {
                            return new java.io.ByteArrayInputStream(data);
                        }
                        if (m.getReturnType() == long.class) {
                            return (long) data.length;
                        }
                        if (m.getReturnType() == headerClass) {
                            return "Bup".equals(m.getName()) ? contentTypeHdr : null;
                        }
                        return null;
                    });
        }

        java.util.List<Object> hdrs = new java.util.ArrayList<>();
        hdrs.add(newHeader("Authorization", auth.bearer));
        if (capturedMid != null && !capturedMid.isEmpty()) {
            hdrs.add(newHeader("X-MID", capturedMid));
        }
        hdrs.add(newHeader("User-Agent", capturedHeaders.getOrDefault("User-Agent", userAgent())));
        hdrs.add(newHeader("X-IG-App-ID", capturedHeaders.getOrDefault("X-IG-App-ID", IG_APP_ID)));
        hdrs.add(newHeader("X-IG-Connection-Type", "WIFI"));
        hdrs.add(newHeader("Accept-Language", capturedHeaders.getOrDefault("Accept-Language", "en-US")));
        for (Map.Entry<String, String> h : capturedHeaders.entrySet()) {
            String k = h.getKey();
            if ("Authorization".equalsIgnoreCase(k) || "User-Agent".equalsIgnoreCase(k)
                    || "X-IG-App-ID".equalsIgnoreCase(k) || "Accept-Language".equalsIgnoreCase(k)
                    || "X-MID".equalsIgnoreCase(k) || "X-IG-Connection-Type".equalsIgnoreCase(k)) continue;
            hdrs.add(newHeader(k, h.getValue()));
        }

        Object igReq = igCtor.newInstance(
                null, bodyProxy, methodInt, urlStr, hdrs, new LinkedHashMap<>(),
                Boolean.TRUE, Boolean.TRUE, Boolean.FALSE);
        java.lang.reflect.Constructor<?> syncCtor =
                syncReqClass.getDeclaredConstructor(igReqClass, capturedPolicy.getClass());
        syncCtor.setAccessible(true);
        Object syncReq = syncCtor.newInstance(igReq, capturedPolicy);

        Object resp = syncSendMethod.invoke(null, syncReq);
        if (resp == null) return null;

        int statusCode = 0;
        Object respBody = null;
        for (java.lang.reflect.Field f : resp.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            if (f.getType() == int.class) {
                int v = f.getInt(resp);
                if ("A02".equals(f.getName())) {
                    statusCode = v;
                } else if (statusCode == 0 && v >= 100 && v <= 599) {
                    statusCode = v;
                }
            } else if (f.getType().isInterface() && hasInputStreamMethod(f.getType())) {
                respBody = f.get(resp);
            }
        }
        String bodyStr = "";
        if (respBody != null) {
            java.lang.reflect.Method inMethod = null;
            for (java.lang.reflect.Method m : respBody.getClass().getMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType() == InputStream.class) {
                    inMethod = m;
                    break;
                }
            }
            if (inMethod != null) {
                try (InputStream is = (InputStream) inMethod.invoke(respBody)) {
                    if (is != null) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                        byte[] rawBytes = baos.toByteArray();
                        if (rawBytes.length >= 2 && rawBytes[0] == (byte) 0x1f && rawBytes[1] == (byte) 0x8b) {
                            try (java.util.zip.GZIPInputStream gis =
                                         new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(rawBytes))) {
                                ByteArrayOutputStream ungzip = new ByteArrayOutputStream();
                                while ((n = gis.read(buf)) > 0) ungzip.write(buf, 0, n);
                                rawBytes = ungzip.toByteArray();
                            }
                        }
                        bodyStr = new String(rawBytes, StandardCharsets.UTF_8);
                    }
                }
            }
        }
        HttpResult out = new HttpResult();
        out.code = statusCode > 0 ? statusCode : 200;
        out.body = bodyStr;
        Log.i(TAG, "native " + method + " " + urlStr + " -> " + out.code + " len=" + bodyStr.length());
        return out;
    }

    private static String cleanUsername(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int idx = s.indexOf("instagram.com/");
        if (idx >= 0) {
            s = s.substring(idx + "instagram.com/".length());
            int q = s.indexOf('?');
            if (q >= 0) s = s.substring(0, q);
            int sl = s.indexOf('/');
            if (sl >= 0) s = s.substring(0, sl);
        }
        return s.startsWith("@") ? s.substring(1) : s;
    }

    private static String extractShortcode(String url) {
        if (url == null) return "";
        String[] prefixes = {"/p/", "/reel/", "/reels/", "/tv/"};
        for (String p : prefixes) {
            int idx = url.indexOf(p);
            if (idx >= 0) {
                String rest = url.substring(idx + p.length());
                int q = rest.indexOf('?');
                if (q >= 0) rest = rest.substring(0, q);
                int sl = rest.indexOf('/');
                if (sl >= 0) rest = rest.substring(0, sl);
                return rest.trim();
            }
        }
        return "";
    }

    private static String shortcodeToMediaId(String shortcode) {
        BigInteger id = BigInteger.ZERO;
        BigInteger base = BigInteger.valueOf(64);
        for (int i = 0; i < shortcode.length(); i++) {
            int val = SHORTCODE_ALPHABET.indexOf(shortcode.charAt(i));
            if (val < 0) return "";
            id = id.multiply(base).add(BigInteger.valueOf(val));
        }
        return id.toString();
    }

    private static JSONObject parseJson(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return new JSONObject(s);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static Map<String, String> parseQuery(String qs) {
        Map<String, String> map = new LinkedHashMap<>();
        if (qs == null || qs.isEmpty()) return map;
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                map.put(k, v);
            } catch (Throwable ignored) {}
        }
        return map;
    }
}
