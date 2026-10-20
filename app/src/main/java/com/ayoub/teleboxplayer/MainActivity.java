package com.ayoub.teleboxplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0 Mobile Safari/537.36";

    private WebView web;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private String pendingShared;
    private LinearLayout loginPanel;
    private WebView loginWeb;
    private TextView loginStatus;

    static final String LOGIN_URL = "https://www.linkbox.to/admin/account";

    /** Collects the session/API tokens the signed-in site shows or stores. */
    static final String FIND_TOKEN_JS =
            "(function(){try{var keys=[],cands=[],best='',src='';"
            // 1) The account page prints the API token as: Token <value> Copy
            + "var txt=(document.body&&document.body.innerText)||'';"
            + "var m=txt.match(/\\bToken\\s*[:\\n\\t ]\\s*([A-Za-z0-9_\\-]{10,64})/);"
            + "if(m&&!/^copy$/i.test(m[1])){best=m[1];src='page';cands.push({k:'page',v:m[1]});}"
            // 2) Token-like values in storage and cookies (t, sid, token, auth, session, ...)
            + "function look(k,v,d){if(d>4||v==null)return;"
            + " if(typeof v==='string'){var t=v.trim();"
            + "  if((t[0]==='{'||t[0]==='[')&&d<4){try{look(k,JSON.parse(t),d+1);}catch(e){}return;}"
            + "  if(/^(t|sid)$|token|auth|session|logkey/i.test(k)&&/^[A-Za-z0-9._\\-]{10,300}$/.test(t)&&/[A-Za-z]/.test(t)"
            + "   &&cands.length<10&&!cands.some(function(c){return c.v===t;})){cands.push({k:k,v:t});if(!best){best=t;src='storage';}}return;}"
            + " if(typeof v==='object'){for(var x in v){look(x,v[x],d+1);}}}"
            + "[localStorage,sessionStorage].forEach(function(s){for(var i=0;i<s.length;i++){var k=s.key(i);keys.push(k);look(k,s.getItem(k),0);}});"
            + "document.cookie.split(';').forEach(function(c){var p=c.split('=');var k=(p.shift()||'').trim();keys.push('cookie:'+k);"
            + " look(k,decodeURIComponent(p.join('=')),0);});"
            + "return JSON.stringify({token:best,src:src,keys:keys,cands:cands});}catch(e){return JSON.stringify({token:'',keys:[String(e)],cands:[]});}})()";

    /** Media requests made by the official player that we can hand to our own player. */
    static final java.util.regex.Pattern MEDIA = java.util.regex.Pattern.compile(
            "\\.(m3u8|mp4|mkv|webm|mov)(\\?|$)", java.util.regex.Pattern.CASE_INSENSITIVE);
    static final java.util.regex.Pattern TELEBOX_HOST = java.util.regex.Pattern.compile(
            "nuplink|linkbox|telebox|lbx\\.|aht\\.", java.util.regex.Pattern.CASE_INSENSITIVE);

    private LinearLayout sniffPanel;
    private WebView sniffWeb;
    private volatile boolean sniffDone;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0E1116"));
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(UA);
        web.setBackgroundColor(Color.parseColor("#0E1116"));
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                setFullscreenUi(true);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        pendingShared = extractShared(getIntent());
        web.loadUrl("file:///android_asset/index.html");
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("telebox", MODE_PRIVATE);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showLogin() {
        if (loginPanel != null) return;
        CookieManager.getInstance().setAcceptCookie(true);

        loginPanel = new LinearLayout(this);
        loginPanel.setOrientation(LinearLayout.VERTICAL);
        loginPanel.setBackgroundColor(Color.parseColor("#0E1116"));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(24, 16, 16, 16);
        loginStatus = new TextView(this);
        loginStatus.setText("Sign in with your TeleBox account");
        loginStatus.setTextColor(Color.parseColor("#E8ECF1"));
        loginStatus.setTextSize(15);
        bar.addView(loginStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = new Button(this);
        close.setText("Close");
        close.setOnClickListener(v -> checkLogin(true));
        bar.addView(close);
        loginPanel.addView(bar);

        loginWeb = new WebView(this);
        WebSettings s = loginWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(UA);
        CookieManager.getInstance().setAcceptThirdPartyCookies(loginWeb, true);
        loginWeb.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                main.postDelayed(() -> checkLogin(false), 1200);
            }
        });
        loginPanel.addView(loginWeb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(loginPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loginWeb.loadUrl(LOGIN_URL);
    }

    /** Reads the token from the login page; closes the panel when found (or when closing). */
    private void checkLogin(boolean closing) {
        if (loginWeb == null) return;
        loginWeb.evaluateJavascript(FIND_TOKEN_JS, raw -> {
            String token = "", keys = "", src = "", cands = "[]";
            try {
                // evaluateJavascript returns a JSON-encoded string
                String json = new org.json.JSONArray("[" + raw + "]").getString(0);
                JSONObject o = new JSONObject(json);
                token = o.optString("token", "");
                src = o.optString("src", "");
                if (o.optJSONArray("cands") != null) cands = o.optJSONArray("cands").toString();
                keys = o.optJSONArray("keys") == null ? "" : o.optJSONArray("keys").toString();
            } catch (Exception ignored) { }

            // While browsing the login site, only trust the token shown on the account page;
            // storage values are used only when the user taps Close.
            if (!closing && !"page".equals(src)) token = "";
            if (!token.isEmpty()) {
                prefs().edit().putString("token", token).putString("tokens", cands).apply();
                hideLogin();
                web.evaluateJavascript("window.onLogin && window.onLogin(" + JSONObject.quote(token) + ")", null);
                Toast.makeText(this, "Signed in", Toast.LENGTH_SHORT).show();
            } else if (closing) {
                hideLogin();
                web.evaluateJavascript("window.onLoginFailed && window.onLoginFailed(" + JSONObject.quote(keys) + ")", null);
            }
        });
    }

    private void hideLogin() {
        if (loginPanel == null) return;
        root.removeView(loginPanel);
        loginWeb.destroy();
        loginWeb = null;
        loginPanel = null;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showSniff(String pageUrl) {
        if (sniffPanel != null) return;
        sniffDone = false;
        sniffPanel = new LinearLayout(this);
        sniffPanel.setOrientation(LinearLayout.VERTICAL);
        sniffPanel.setBackgroundColor(Color.parseColor("#0E1116"));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(24, 16, 16, 16);
        TextView msg = new TextView(this);
        msg.setText("Open the video and tap play. The app takes over when it starts.");
        msg.setTextColor(Color.parseColor("#E8ECF1"));
        msg.setTextSize(14);
        bar.addView(msg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = new Button(this);
        close.setText("Close");
        close.setOnClickListener(v -> hideSniff());
        bar.addView(close);
        sniffPanel.addView(bar);

        sniffWeb = new WebView(this);
        WebSettings s = sniffWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(UA);
        CookieManager.getInstance().setAcceptThirdPartyCookies(sniffWeb, true);
        sniffWeb.setWebChromeClient(new WebChromeClient());
        sniffWeb.setWebViewClient(new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (!sniffDone && MEDIA.matcher(u).find() && TELEBOX_HOST.matcher(u).find()) {
                    sniffDone = true;
                    main.post(() -> {
                        hideSniff();
                        web.evaluateJavascript("window.onSniffed && window.onSniffed(" + JSONObject.quote(u) + ")", null);
                    });
                }
                return null; // never block or change the page's own requests
            }
        });
        sniffPanel.addView(sniffWeb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(sniffPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sniffWeb.loadUrl(pageUrl);
    }

    private void hideSniff() {
        if (sniffPanel == null) return;
        root.removeView(sniffPanel);
        sniffWeb.stopLoading();
        sniffWeb.destroy();
        sniffWeb = null;
        sniffPanel = null;
    }

    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        web.setVisibility(View.VISIBLE);
        setFullscreenUi(false);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @SuppressWarnings("deprecation")
    private void setFullscreenUi(boolean on) {
        View d = getWindow().getDecorView();
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String link = extractShared(intent);
        if (link != null) {
            web.evaluateJavascript("window.openShare && window.openShare(" + JSONObject.quote(link) + ")", null);
        }
    }

    private String extractShared(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return null;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(text);
        return m.find() ? m.group() : null;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (customView != null) { exitFullscreen(); return; }
        if (sniffPanel != null) {
            if (sniffWeb != null && sniffWeb.canGoBack()) sniffWeb.goBack();
            else hideSniff();
            return;
        }
        if (loginPanel != null) {
            if (loginWeb != null && loginWeb.canGoBack()) loginWeb.goBack();
            else checkLogin(true);
            return;
        }
        web.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

    /** Methods callable from the page as window.Native.* */
    class Bridge {

        /** HTTP GET done natively (no CORS). Result goes to window.__native(id, result). */
        @JavascriptInterface
        public void fetch(final String id, final String url) {
            pool.execute(() -> {
                JSONObject res = new JSONObject();
                try {
                    String current = url;
                    HttpURLConnection c = null;
                    for (int hop = 0; hop < 8; hop++) {
                        c = (HttpURLConnection) new URL(current).openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(20000);
                        c.setRequestProperty("User-Agent", UA);
                        c.setRequestProperty("Accept", "application/json, text/html, */*");
                        try {
                            String ck = CookieManager.getInstance().getCookie(current);
                            if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
                        } catch (Exception ignored) { }
                        int code = c.getResponseCode();
                        if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                            current = new URL(new URL(current), c.getHeaderField("Location")).toString();
                            c.disconnect();
                            continue;
                        }
                        break;
                    }
                    int code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    if (in != null) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0 && out.size() < 8_000_000) out.write(buf, 0, n);
                        in.close();
                    }
                    c.disconnect();
                    res.put("status", code);
                    res.put("url", current);
                    res.put("body", out.toString("UTF-8"));
                } catch (Exception e) {
                    try {
                        res.put("status", 0);
                        res.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    } catch (Exception ignored) { }
                }
                final String js = "window.__native(" + JSONObject.quote(id) + "," + res + ")";
                main.post(() -> web.evaluateJavascript(js, null));
            });
        }

        @JavascriptInterface
        public void openExternal(String url, String title) {
            main.post(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(Uri.parse(url), "video/*");
                    i.putExtra("title", title);
                    startActivity(Intent.createChooser(i, "Play with"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "No video player found", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void copy(String text) {
            main.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("link", text));
                Toast.makeText(MainActivity.this, "Link copied", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void toast(String text) {
            main.post(() -> Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void login() {
            main.post(MainActivity.this::showLogin);
        }

        @JavascriptInterface
        public String getToken() {
            return prefs().getString("token", "");
        }

        @JavascriptInterface
        public void setToken(String t) {
            prefs().edit().putString("token", t == null ? "" : t.trim()).apply();
        }

        @JavascriptInterface
        public String getTokens() {
            return prefs().getString("tokens", "[]");
        }

        @JavascriptInterface
        public void sniff(String pageUrl) {
            main.post(() -> showSniff(pageUrl));
        }

        @JavascriptInterface
        public void logout() {
            prefs().edit().remove("token").remove("tokens").apply();
            main.post(() -> {
                CookieManager.getInstance().removeAllCookies(null);
                // Only the login site's storage: keeps the app's saved links.
                android.webkit.WebStorage.getInstance().deleteOrigin("https://www.linkbox.to");
            });
        }

        /** A link shared into the app before the page loaded (consumed once). */
        @JavascriptInterface
        public String takeSharedLink() {
            String s = pendingShared;
            pendingShared = null;
            return s == null ? "" : s;
        }
    }
}
