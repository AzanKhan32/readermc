package com.azan.readermc;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * In-app browser used to pass Cloudflare checks and mint site session tokens
 * for native extensions (HDoujin/SchaleNetwork, etc.).
 *
 * CRITICAL SETTINGS — extensions depend on these:
 *
 * - setDomStorageEnabled(true): HDoujin's "open webview to refresh token"
 *   flow works by the SITE writing a `clearance` value into localStorage;
 *   the extension later reads it back via its own hidden WebView
 *   (localStorage.getItem('clearance')). localStorage is shared app-wide
 *   per origin, but ONLY persists if DOM storage is enabled here. Without
 *   this flag the visit looks fine to the user yet saves nothing, and the
 *   retry keeps failing forever.
 *
 * - CookieManager + flush(): cf_clearance and session cookies must be
 *   persisted so the extensions' OkHttp cookie jar (AndroidCookieJar, backed
 *   by the same CookieManager) sees them.
 *
 * - User-Agent: the default WebView UA with the "; wv)" marker stripped —
 *   byte-for-byte identical to defaultUserAgentProvider() in NetworkHelper,
 *   so tokens minted here are honored on the extensions' OkHttp requests.
 */
public class WebViewActivity extends Activity {

    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean tokenFound = false;
    private String currentUrl;
    // cf_clearance value present when we opened (usually a STALE token left
    // over from a previous visit — the very token whose expiry caused the
    // 403 that sent us here). We must NOT close on this one; only on a fresh
    // value minted by actually passing the challenge now.
    private String initialClearance;
    private int pollTicks = 0;
    private boolean hintShown = false;

    // Two different "we're cleared now" signals, checked once a second:
    //  1. localStorage `clearance` token — HDoujin-style extensions.
    //  2. cf_clearance cookie — plain Cloudflare "Just a moment" challenges
    //     (VyvyManga / The Blank detail pages). This is a COOKIE, not
    //     localStorage, so it's read from CookieManager.
    // The moment either fresh signal appears, tell the user and auto-close so
    // they never have to guess when to go back.
    private final Runnable tokenPoll = new Runnable() {
        @Override
        public void run() {
            if (webView == null || tokenFound) return;

            // Cheap synchronous check first: a FRESHLY-minted Cloudflare
            // clearance cookie. Closing on the stale one (present at open)
            // was the bug — it handed the retry the same expired token.
            String cookies = currentUrl != null
                    ? CookieManager.getInstance().getCookie(currentUrl) : null;
            String clearance = extractClearance(cookies);
            if (clearance != null && !clearance.equals(initialClearance)) {
                onCleared();
                return;
            }

            webView.evaluateJavascript(
                    "(function(){try{return localStorage.getItem('clearance')||''}catch(e){return ''}})()",
                    value -> {
                        boolean got = value != null && value.length() > 4 && !"null".equals(value.replace("\"", ""));
                        if (got) {
                            onCleared();
                        } else {
                            // After ~15s with no fresh token, nudge the user:
                            // some sites won't auto-resolve (or aren't actually
                            // challenged), so let them close manually.
                            if (++pollTicks >= 15 && !hintShown) {
                                hintShown = true;
                                Toast.makeText(WebViewActivity.this,
                                        "If the page has loaded, press back to continue.",
                                        Toast.LENGTH_LONG).show();
                            }
                            handler.postDelayed(tokenPoll, 1000);
                        }
                    });
        }
    };

    /** Pulls the cf_clearance value out of a "a=b; c=d" cookie header, or null. */
    private static String extractClearance(String cookieHeader) {
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String p = part.trim();
            if (p.startsWith("cf_clearance=")) {
                return p.substring("cf_clearance=".length());
            }
        }
        return null;
    }

    private void onCleared() {
        if (tokenFound) return;
        tokenFound = true;
        Toast.makeText(WebViewActivity.this,
                "Verified — returning to the app…", Toast.LENGTH_SHORT).show();
        CookieManager.getInstance().flush();
        handler.postDelayed(WebViewActivity.this::finishWithResult, 900);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String url = getIntent().getStringExtra("url");
        if (url == null || url.isEmpty()) {
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // localStorage — REQUIRED (see header)
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        // Strip the "; wv)" WebView marker so this browser's identity is
        // byte-for-byte identical to what the extensions' OkHttp client
        // sends (NetworkHelper.defaultUserAgentProvider does the same
        // strip). Sites like HDoujin bind the minted token to the UA — any
        // mismatch and the API rejects it with 403.
        s.setUserAgentString(s.getUserAgentString().replace("; wv)", ")"));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        // Baseline the stale token BEFORE loading, so the poll only closes
        // once a genuinely new cf_clearance is minted by passing the check.
        initialClearance = extractClearance(cm.getCookie(url));

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String pageUrl) {
                // Track the URL so the poll can read cf_clearance for it.
                currentUrl = pageUrl;
                // Persist cookies to disk immediately so the extensions'
                // cookie jar sees them even if the app process is killed.
                CookieManager.getInstance().flush();
                // Start watching for the token once the page settles.
                handler.removeCallbacks(tokenPoll);
                handler.postDelayed(tokenPoll, 500);
            }
        });

        Toast.makeText(this,
                "Wait for the page to load — the app will return automatically.",
                Toast.LENGTH_LONG).show();
        webView.loadUrl(url);
    }

    /** Back = navigate the page history first; on the last page, close and
     *  report success (cookies/localStorage are already persisted). */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null) {
            if (webView.canGoBack()) {
                webView.goBack();
                return true;
            }
            finishWithResult();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void finishWithResult() {
        Intent data = new Intent();
        String finalUrl = webView.getUrl();
        data.putExtra("finalUrl", finalUrl != null ? finalUrl : "");
        data.putExtra("cookies", CookieManager.getInstance().getCookie(finalUrl));
        data.putExtra("userAgent", webView.getSettings().getUserAgentString());
        CookieManager.getInstance().flush();
        setResult(Activity.RESULT_OK, data);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tokenPoll);
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
