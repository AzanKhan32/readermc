package com.azan.readermc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import android.graphics.Color;
import android.util.TypedValue;

public class WebViewActivity extends Activity {

    private WebView webView;
    private String targetUrl;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        targetUrl = getIntent().getStringExtra("url");
        if (targetUrl == null || targetUrl.isEmpty()) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        // Build layout programmatically — no XML needed
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#1a1a2e"));

        // Top bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(Color.parseColor("#16213e"));
        topBar.setPadding(dp(12), dp(10), dp(12), dp(10));
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText("Solve verification then tap Done");
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button doneBtn = new Button(this);
        doneBtn.setText("Done");
        doneBtn.setTextColor(Color.WHITE);
        doneBtn.setBackgroundColor(Color.parseColor("#e94560"));
        doneBtn.setPadding(dp(16), dp(6), dp(16), dp(6));
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        doneBtn.setLayoutParams(btnParams);

        topBar.addView(label);
        topBar.addView(doneBtn);

        // WebView
        webView = new WebView(this);
        LinearLayout.LayoutParams wvParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        webView.setLayoutParams(wvParams);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/125.0.0.0 Mobile Safari/537.36"
        );
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Stay inside the WebView for all navigation
                return false;
            }
        });

        webView.loadUrl(targetUrl);

        root.addView(topBar);
        root.addView(webView);
        setContentView(root);

        // Done button — harvest cookies and return to app
        doneBtn.setOnClickListener(v -> returnCookies());
    }

    private void returnCookies() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.flush();

        // Get cookies for the base domain
        String cookies = cookieManager.getCookie(targetUrl);
        String currentUrl = webView.getUrl();

        Intent result = new Intent();
        result.putExtra("cookies", cookies != null ? cookies : "");
        result.putExtra("finalUrl", currentUrl != null ? currentUrl : targetUrl);
        result.putExtra("userAgent",
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/125.0.0.0 Mobile Safari/537.36"
        );
        setResult(RESULT_OK, result);
        finish();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            setResult(RESULT_CANCELED);
            super.onBackPressed();
        }
    }
}
