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

    private InstagramBridge() {}

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
        Context ctx = appContext;
        if (ctx == null) return null;
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

        for (File f : files) {
            String prefName = f.getName().substring(0, f.getName().length() - 4);
            try {
                SharedPreferences sp = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE);
                Map<String, ?> all = sp.getAll();
                for (Map.Entry<String, ?> e : all.entrySet()) {
                    Object v = e.getValue();
                    if (!(v instanceof String)) continue;
                    String s = (String) v;
                    int idx = s.indexOf("IGT:2:");
                    if (idx < 0) continue;
                    String token = s.startsWith("Bearer ") ? s : ("Bearer " + s.substring(idx));
                    AuthInfo info = new AuthInfo();
                    info.bearer = token.trim();
                    info.prefFile = prefName;
                    info.myUid = extractUidFromIgt(info.bearer);
                    return info;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

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
            return new JSONObject().put("ok", false).put("error", "no IGT:2 token in shared_prefs");
        }
        String masked = auth.bearer.length() > 24
                ? auth.bearer.substring(0, 20) + "..."
                : "***";
        return new JSONObject()
                .put("ok", true)
                .put("pref_file", auth.prefFile)
                .put("my_uid", auth.myUid)
                .put("token_prefix", masked)
                .put("ua", userAgent());
    }

    private static JSONObject handleProfile(Map<String, String> params) throws Exception {
        AuthInfo auth = requireAuth();
        String uid = params.getOrDefault("uid", "").trim();
        String username = cleanUsername(params.getOrDefault("username", ""));
        JSONObject user = resolveUser(auth, uid, username);
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
            JSONObject user = resolveUser(auth, "", username);
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

        boolean wantLike = "1".equals(type);
        String actionPath = wantLike
                ? "/media/" + mediaId + "/like/"
                : "/media/" + mediaId + "/unlike/";
        Map<String, String> form = new LinkedHashMap<>();
        form.put("media_id", mediaId);
        if (!auth.myUid.isEmpty()) form.put("_uid", auth.myUid);
        form.put("d", "0");
        form.put("module_name", "feed_timeline");

        HttpResult res = request(auth, "POST", IG_API + actionPath, form);
        JSONObject json = parseJson(res.body);
        String apiStatus = json != null ? json.optString("status", "") : "";
        if (res.code < 200 || res.code >= 300 || !"ok".equalsIgnoreCase(apiStatus)) {
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
            if (j != null && j.has("user")) return j.optJSONObject("user");
        }
        if (!username.isEmpty()) {
            HttpResult r = request(auth, "GET",
                    IG_API + "/users/" + URLEncoder.encode(username, "UTF-8") + "/usernameinfo/", null);
            JSONObject j = parseJson(r.body);
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
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Authorization", auth.bearer);
        conn.setRequestProperty("User-Agent", userAgent());
        conn.setRequestProperty("X-IG-App-ID", IG_APP_ID);
        conn.setRequestProperty("X-IG-Connection-Type", "WIFI");
        conn.setRequestProperty("Accept-Language", "en-US");
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
