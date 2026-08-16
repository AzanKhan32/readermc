package com.azan.readermc;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.BridgeActivity;

import androidx.activity.result.ActivityResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.zip.GZIPInputStream;

import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NativePlugin.class);
        super.onCreate(savedInstanceState);
    }

    @CapacitorPlugin(name = "NativePlugin")
    public static class NativePlugin extends Plugin {

        // Hard ceiling on how much space the local "resolve to a real seekable
        // file" cache (used by getZipEntryNames/getPageImage/getThumbnail
        // below) is allowed to use. Without this, every chapter you so much
        // as generate a thumbnail for — or open to read — ends up with a
        // full duplicate copy sitting in app storage forever, since
        // java.util.zip.ZipFile needs a real seekable File and content://
        // URIs aren't seekable. This caps it and evicts the
        // least-recently-used copies once the limit is hit, instead of
        // letting it grow in lockstep with your whole library.
        private static final long MAX_MANGA_CACHE_BYTES = 300L * 1024 * 1024; // 300 MB

        // Deletes oldest-by-lastModified files in manga_cache until the
        // directory's total size is back under the cap. Called after any
        // write into that directory so it self-limits continuously instead
        // of needing a separate "clear cache" pass to stay bounded.
        private static void pruneMangaCacheIfNeeded(File cacheDir) {
            try {
                File[] files = cacheDir.listFiles();
                if (files == null || files.length == 0) return;

                long total = 0;
                for (File f : files) total += f.length();
                if (total <= MAX_MANGA_CACHE_BYTES) return;

                java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (File f : files) {
                    if (total <= MAX_MANGA_CACHE_BYTES) break;
                    long size = f.length();
                    if (f.delete()) total -= size;
                }
            } catch (Exception ignored) {
                // Best-effort — never let cache housekeeping crash a real request.
            }
        }

        // ── Hidden WebView-backed fetch bridge ─────────────────────────────
        // MangaDex (and similar) now front their API with bot-detection that
        // fingerprints the TLS handshake, not just headers. java.net.HttpURLConnection
        // has a distinct fingerprint from a real Chrome/WebView client and gets
        // blocked (served an HTML challenge/shell page) no matter what
        // User-Agent header we set. A real Android WebView uses the device's
        // actual Chromium networking stack, so requests made via its own
        // fetch() look like a normal browser and get through. This section
        // keeps one hidden, reusable WebView around purely to run fetch()
        // calls on the plugin's behalf and ferry the result back over a
        // JavascriptInterface.
        // One hidden WebView PER ORIGIN (small LRU pool) instead of a single
        // shared one. The old single-WebView design broke "search all
        // sources": the global search fires every source in parallel, each
        // anchored to a different origin, and every origin switch reloaded
        // the one shared shell page — aborting other sources' in-flight
        // fetches AND draining the shared ready-queue against the wrong
        // origin (so their fetches ran with a wrong Origin header and got
        // CORS-rejected). With a per-origin pool, concurrent fetches to
        // different sites never interfere with each other.
        private static final int MD_BRIDGE_MAX_WEBVIEWS = 6;
        private class MdBridgeEntry {
            WebView webView;
            boolean ready = false;
            final java.util.List<Runnable> readyQueue = new java.util.ArrayList<>();
            long lastUsed = System.currentTimeMillis();
        }
        // Only ever touched on the UI thread, so no synchronization needed.
        private final java.util.LinkedHashMap<String, MdBridgeEntry> mdBridgeEntries = new java.util.LinkedHashMap<>();
        private final Map<String, PluginCall> mdPendingCalls = new ConcurrentHashMap<>();
        private final AtomicInteger mdCallCounter = new AtomicInteger(0);

        // ── Chunked CBZ write sessions ──────────────────────────────────────
        // saveCbz() used to take the *entire* zip as one giant base64 string
        // in a single plugin call. For a high-quality/long chapter that
        // string can run into the hundreds of MB — building it in the JS
        // heap, JSON-encoding it across the Capacitor bridge, and then
        // Base64-decoding the whole thing into one byte[] here could each
        // independently exhaust memory. Because Base64.decode() can throw
        // OutOfMemoryError (an Error, not an Exception), it was slipping
        // past the surrounding `catch (Exception e)` entirely and taking
        // the whole app process down with it instead of failing gracefully
        // — this is what "chapter fails after a complete download" was.
        // The fix: write the file in small chunks to a temp file (bounded,
        // constant memory per chunk), then move that temp file into its
        // final destination with a streamed copy at the end.
        private final Map<String, File> cbzWriteSessions = new ConcurrentHashMap<>();

        private class MdJsBridge {
            @JavascriptInterface
            public void deliverResult(String callId, String status, String data, boolean isError) {
                PluginCall call = mdPendingCalls.remove(callId);
                if (call == null) return;
                if (isError) {
                    call.reject("webFetch failed: " + data);
                    return;
                }
                int statusCode;
                try { statusCode = Integer.parseInt(status); } catch (Exception e) { statusCode = 0; }
                JSObject ret = new JSObject();
                ret.put("status", statusCode);
                ret.put("body", data);
                call.resolve(ret);
            }
        }

        // Tiny callback interface (avoids java.util.function.Consumer, which
        // needs API 24+ / desugaring).
        private interface MdWebViewReady { void accept(WebView wv); }

        // Resolves (on the UI thread) with the ready WebView anchored at
        // `origin`. Creates it on first use; evicts the least-recently-used
        // idle WebView when the pool is full.
        private void ensureMdBridgeWebView(String origin, MdWebViewReady onReady) {
            final String targetOrigin = (origin != null && !origin.isEmpty()) ? origin : "https://mangadex.org/";

            getActivity().runOnUiThread(() -> {
                MdBridgeEntry existing = mdBridgeEntries.get(targetOrigin);
                if (existing != null) {
                    existing.lastUsed = System.currentTimeMillis();
                    if (existing.ready) {
                        onReady.accept(existing.webView);
                    } else {
                        // Shell page for this origin is still loading — queue.
                        existing.readyQueue.add(() -> onReady.accept(existing.webView));
                    }
                    return;
                }

                // Pool full: evict the least-recently-used READY entry (never
                // one that's still loading — it has queued callers waiting).
                if (mdBridgeEntries.size() >= MD_BRIDGE_MAX_WEBVIEWS) {
                    String lruKey = null;
                    long oldest = Long.MAX_VALUE;
                    for (Map.Entry<String, MdBridgeEntry> e : mdBridgeEntries.entrySet()) {
                        MdBridgeEntry en = e.getValue();
                        if (en.ready && en.lastUsed < oldest) { oldest = en.lastUsed; lruKey = e.getKey(); }
                    }
                    if (lruKey != null) {
                        MdBridgeEntry evicted = mdBridgeEntries.remove(lruKey);
                        if (evicted.webView != null) evicted.webView.destroy();
                    }
                }

                final MdBridgeEntry entry = new MdBridgeEntry();
                entry.readyQueue.add(() -> onReady.accept(entry.webView));
                mdBridgeEntries.put(targetOrigin, entry);

                WebView wv = new WebView(getActivity());
                android.webkit.WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                wv.addJavascriptInterface(new MdJsBridge(), "AndroidBridge");
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        entry.ready = true;
                        java.util.List<Runnable> queued = new java.util.ArrayList<>(entry.readyQueue);
                        entry.readyQueue.clear();
                        for (Runnable r : queued) r.run();
                    }
                });
                entry.webView = wv;
                // A bare about:blank page has an opaque/"null" origin, and
                // some CORS configurations reject fetch() calls that arrive
                // with `Origin: null` even when Access-Control-Allow-Origin
                // is "*" — that surfaces to JS as a generic "Failed to
                // fetch" with no further detail. Giving the WebView a real
                // https origin via loadDataWithBaseURL (page content is
                // still just an empty shell — nothing is actually fetched
                // from that URL) sidesteps that without the overhead of
                // loading the real site.
                wv.loadDataWithBaseURL(targetOrigin, "<!DOCTYPE html><html><body></body></html>",
                        "text/html", "UTF-8", null);
            });
        }

        // Builds the self-contained JS snippet that performs the fetch and
        // reports back through AndroidBridge.deliverResult(...).
        private String buildFetchScript(String callId, String url, String method,
                                        JSONObject headers, String body,
                                        String responseType) throws Exception {
            JSONObject opts = new JSONObject();
            opts.put("method", method);
            opts.put("headers", headers != null ? headers : new JSONObject());
            if (body != null) opts.put("body", body);

            StringBuilder js = new StringBuilder();
            js.append("(function(){ try { fetch(")
                    .append(JSONObject.quote(url)).append(", ").append(opts.toString())
                    .append(").then(function(resp){ var status = resp.status;");
            if ("base64".equals(responseType)) {
                js.append("return resp.arrayBuffer().then(function(buf){")
                        .append("var bytes = new Uint8Array(buf); var chunks = []; var CH = 8192;")
                        .append("for (var i = 0; i < bytes.length; i += CH) {")
                        .append("chunks.push(String.fromCharCode.apply(null, bytes.subarray(i, i + CH)));")
                        .append("}")
                        .append("var b64 = btoa(chunks.join(''));")
                        .append("var mime = resp.headers.get('content-type') || '';")
                        .append("AndroidBridge.deliverResult(").append(JSONObject.quote(callId))
                        .append(", String(status), mime + '|' + b64, false); });");
            } else {
                js.append("return resp.text().then(function(txt){")
                        .append("AndroidBridge.deliverResult(").append(JSONObject.quote(callId))
                        .append(", String(status), txt, false); });");
            }
            js.append("}).catch(function(err){ AndroidBridge.deliverResult(")
                    .append(JSONObject.quote(callId))
                    .append(", '0', String((err && err.message) || err), true); });")
                    .append("} catch (e) { AndroidBridge.deliverResult(")
                    .append(JSONObject.quote(callId))
                    .append(", '0', String((e && e.message) || e), true); } })();");
            return js.toString();
        }

        // General-purpose fetch through the hidden WebView. Used for MangaDex
        // traffic by default, but also reusable for any other site that
        // fingerprints/blocks HttpURLConnection-based requests — pass
        // "origin" (e.g. a source site's base URL) to anchor the WebView's
        // fake shell page there instead, so the resulting fetch() carries
        // the right Origin header for that site's CORS setup.
        // responseType: "text" (default, for JSON/API calls) or "base64"
        // (for binary image downloads).
        @PluginMethod
        public void webFetch(PluginCall call) {
            String url = call.getString("url");
            String method = call.getString("method", "GET");
            JSObject headers = call.getObject("headers");
            String body = call.getString("body");
            String responseType = call.getString("responseType", "text");
            String origin = call.getString("origin");
            if (url == null) { call.reject("url is required"); return; }

            String callId = "c" + mdCallCounter.incrementAndGet();
            mdPendingCalls.put(callId, call);

            // ensureMdBridgeWebView already delivers the origin's own WebView
            // on the UI thread, so we can evaluate directly on it.
            ensureMdBridgeWebView(origin, (wv) -> {
                try {
                    String js = buildFetchScript(callId, url, method,
                            headers != null ? headers : new JSONObject(), body, responseType);
                    wv.evaluateJavascript(js, null);
                } catch (Exception e) {
                    PluginCall pending = mdPendingCalls.remove(callId);
                    if (pending != null) pending.reject("webFetch setup failed: " + e.getMessage());
                }
            });
        }

        @PluginMethod
        public void openWebView(PluginCall call) {
            String url = call.getString("url");
            if (url == null || url.isEmpty()) {
                call.reject("URL is required");
                return;
            }
            saveCall(call);
            Intent intent = new Intent(getActivity(), com.azan.readermc.WebViewActivity.class);
            intent.putExtra("url", url);
            startActivityForResult(call, intent, "handleWebViewResult");
        }

        @ActivityCallback
        private void handleWebViewResult(PluginCall call, ActivityResult result) {
            if (call == null) return;
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                Intent data = result.getData();
                JSObject ret = new JSObject();
                ret.put("cookies", data.getStringExtra("cookies"));
                ret.put("finalUrl", data.getStringExtra("finalUrl"));
                ret.put("userAgent", data.getStringExtra("userAgent"));
                call.resolve(ret);
            } else {
                call.reject("WebView cancelled");
            }
        }

        @PluginMethod
        public void saveCbz(PluginCall call) {
            String filename = call.getString("filename");
            String base64Data = call.getString("data");
            // Optional: a SAF tree URI (from pickDownloadFolder / the
            // "Download Location" menu item) telling us to save into a
            // user-chosen folder instead of the default Downloads/MangaReader.
            String destUriString = call.getString("destUri");
            // Optional: a per-manga subfolder name so chapters from
            // different titles don't end up mixed together in one folder.
            String subfolder = call.getString("subfolder");
            if (subfolder != null) {
                subfolder = subfolder.replaceAll("[\\\\/:*?\"<>|]", "").trim();
                if (subfolder.isEmpty()) subfolder = null;
            }

            if (filename == null || base64Data == null) {
                call.reject("filename and data are required");
                return;
            }

            try {
                byte[] bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);

                if (destUriString != null && !destUriString.isEmpty()) {
                    Uri treeUri = Uri.parse(destUriString);
                    String treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
                    Uri treeDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId);
                    Uri parentDocUri = treeDocUri;

                    if (subfolder != null) {
                        parentDocUri = findOrCreateChildDirectory(treeUri, treeDocUri, subfolder);
                        if (parentDocUri == null) {
                            call.reject("Failed to create manga folder in the chosen download folder");
                            return;
                        }
                    }

                    Uri newFileUri = android.provider.DocumentsContract.createDocument(
                            getActivity().getContentResolver(), parentDocUri, "application/x-cbz", filename);

                    if (newFileUri == null) {
                        call.reject("Failed to create file in the chosen download folder");
                        return;
                    }

                    try (OutputStream os = getActivity().getContentResolver().openOutputStream(newFileUri)) {
                        if (os == null) {
                            call.reject("Failed to open output stream");
                            return;
                        }
                        os.write(bytes);
                    }

                    JSObject ret = new JSObject();
                    ret.put("success", true);
                    ret.put("filename", filename);
                    ret.put("uri", newFileUri.toString());
                    call.resolve(ret);
                    return;
                }

                String relativePath = Environment.DIRECTORY_DOWNLOADS + "/MangaReader"
                        + (subfolder != null ? ("/" + subfolder) : "");

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/x-cbz");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);

                    Uri uri = getActivity().getContentResolver().insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

                    if (uri == null) {
                        call.reject("Failed to create file in Downloads");
                        return;
                    }

                    try (OutputStream os = getActivity().getContentResolver().openOutputStream(uri)) {
                        if (os == null) {
                            call.reject("Failed to open output stream");
                            return;
                        }
                        os.write(bytes);
                    }

                    JSObject ret = new JSObject();
                    ret.put("success", true);
                    ret.put("filename", filename);
                    ret.put("uri", uri.toString());
                    call.resolve(ret);
                } else {
                    File downloadsDir = new File(
                            Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS),
                            "MangaReader" + (subfolder != null ? ("/" + subfolder) : ""));
                    if (!downloadsDir.exists()) downloadsDir.mkdirs();
                    File outFile = new File(downloadsDir, filename);
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(bytes);
                    }

                    JSObject ret = new JSObject();
                    ret.put("success", true);
                    ret.put("filename", filename);
                    ret.put("uri", Uri.fromFile(outFile).toString());
                    call.resolve(ret);
                }

            } catch (Throwable e) {
                // Throwable (not just Exception) on purpose: a very large
                // base64 payload decoding here can throw OutOfMemoryError,
                // which is an Error and would otherwise skip right past a
                // narrower `catch (Exception e)` and kill the app process.
                call.reject("Failed to save CBZ: " + e.getMessage());
            }
        }

        // ── Generic text/JSON file save (favorites + full app backups) ─────
        // This mirrors saveCbz but takes a `mimeType` (default
        // application/json). The MIME type matters: on Android Q+ the
        // MediaStore appends a file extension to MATCH the declared MIME, so
        // saveCbz's hardcoded application/x-cbz turned a "foo.json" name into
        // "foo.json.cbz" on disk. Declaring application/json keeps the .json
        // name clean. Used by window.saveTextFile in the web layer.
        @PluginMethod
        public void saveTextFile(PluginCall call) {
            String filename = call.getString("filename");
            String base64Data = call.getString("data");
            String mimeType = call.getString("mimeType", "application/json");
            String destUriString = call.getString("destUri");
            String subfolder = call.getString("subfolder");
            if (subfolder != null) {
                subfolder = subfolder.replaceAll("[\\\\/:*?\"<>|]", "").trim();
                if (subfolder.isEmpty()) subfolder = null;
            }
            if (filename == null || base64Data == null) {
                call.reject("filename and data are required");
                return;
            }
            try {
                byte[] bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);

                // 1) User-picked SAF destination (a content:// tree Uri).
                if (destUriString != null && !destUriString.isEmpty()) {
                    Uri treeUri = Uri.parse(destUriString);
                    String treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
                    Uri treeDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId);
                    Uri parentDocUri = treeDocUri;
                    if (subfolder != null) {
                        parentDocUri = findOrCreateChildDirectory(treeUri, treeDocUri, subfolder);
                        if (parentDocUri == null) { call.reject("Failed to create folder"); return; }
                    }
                    Uri newFileUri = android.provider.DocumentsContract.createDocument(
                            getActivity().getContentResolver(), parentDocUri, mimeType, filename);
                    if (newFileUri == null) { call.reject("Failed to create file"); return; }
                    try (OutputStream os = getActivity().getContentResolver().openOutputStream(newFileUri)) {
                        if (os == null) { call.reject("Failed to open output stream"); return; }
                        os.write(bytes);
                    }
                    JSObject ret = new JSObject();
                    ret.put("success", true);
                    ret.put("filename", filename);
                    ret.put("uri", newFileUri.toString());
                    call.resolve(ret);
                    return;
                }

                String relativePath = Environment.DIRECTORY_DOWNLOADS + "/MangaReader"
                        + (subfolder != null ? ("/" + subfolder) : "");

                // 2) Android Q+ : MediaStore Downloads collection.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                    values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
                    Uri uri = getActivity().getContentResolver().insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) { call.reject("Failed to create file in Downloads"); return; }
                    try (OutputStream os = getActivity().getContentResolver().openOutputStream(uri)) {
                        if (os == null) { call.reject("Failed to open output stream"); return; }
                        os.write(bytes);
                    }
                    JSObject ret = new JSObject();
                    ret.put("success", true);
                    ret.put("filename", filename);
                    ret.put("uri", uri.toString());
                    call.resolve(ret);
                } else {
                    // 3) Legacy (pre-Q) : write straight to the public dir.
                    File downloadsDir = new File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            "MangaReader" + (subfolder != null ? ("/" + subfolder) : ""));
                    if (!downloadsDir.exists()) downloadsDir.mkdirs();
                    File outFile = new File(downloadsDir, filename);
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(bytes);
                    }
                    JSObject ret = new JSObject();
                    ret.put("success", true);
                    ret.put("filename", filename);
                    ret.put("uri", Uri.fromFile(outFile).toString());
                    call.resolve(ret);
                }
            } catch (Throwable e) {
                call.reject("Save failed: " + e.getMessage());
            }
        }

        // ── Chunked save (preferred path for anything but tiny chapters) ───
        // saveCbzStart() opens a temp file; saveCbzChunk() appends one
        // small base64 chunk at a time (bounded memory per call, no matter
        // how large the overall chapter is); saveCbzFinish() streams the
        // finished temp file into its real destination (SAF tree / Downloads
        // / legacy public dir) without ever holding the whole file in a
        // single byte[] or base64 String. saveCbzAbort() cleans up if the
        // JS side cancels or errors out partway through.
        @PluginMethod
        public void saveCbzStart(PluginCall call) {
            try {
                File tmp = File.createTempFile("cbzwrite_", ".tmp", getActivity().getCacheDir());
                String sessionId = java.util.UUID.randomUUID().toString();
                cbzWriteSessions.put(sessionId, tmp);
                JSObject ret = new JSObject();
                ret.put("sessionId", sessionId);
                call.resolve(ret);
            } catch (Throwable e) {
                call.reject("Failed to start save: " + e.getMessage());
            }
        }

        @PluginMethod
        public void saveCbzChunk(PluginCall call) {
            String sessionId = call.getString("sessionId");
            String chunkB64 = call.getString("chunk");
            if (sessionId == null || chunkB64 == null) {
                call.reject("sessionId and chunk are required");
                return;
            }
            File tmp = cbzWriteSessions.get(sessionId);
            if (tmp == null) { call.reject("Unknown or expired save session"); return; }

            new Thread(() -> {
                try {
                    // Only ever holds one chunk's worth of bytes in memory
                    // at a time (JS keeps chunks small — a few MB), instead
                    // of the whole chapter.
                    byte[] bytes = android.util.Base64.decode(chunkB64, android.util.Base64.DEFAULT);
                    try (FileOutputStream fos = new FileOutputStream(tmp, /*append=*/true)) {
                        fos.write(bytes);
                    }
                    call.resolve();
                } catch (Throwable e) {
                    call.reject("Failed to write chunk: " + e.getMessage());
                }
            }).start();
        }

        @PluginMethod
        public void saveCbzAbort(PluginCall call) {
            String sessionId = call.getString("sessionId");
            if (sessionId != null) {
                File tmp = cbzWriteSessions.remove(sessionId);
                if (tmp != null) { try { tmp.delete(); } catch (Exception ignored) { } }
            }
            call.resolve();
        }

        @PluginMethod
        public void saveCbzFinish(PluginCall call) {
            String sessionId = call.getString("sessionId");
            String filename = call.getString("filename");
            String destUriString = call.getString("destUri");
            String subfolder = call.getString("subfolder");
            if (subfolder != null) {
                subfolder = subfolder.replaceAll("[\\\\/:*?\"<>|]", "").trim();
                if (subfolder.isEmpty()) subfolder = null;
            }
            final String finalSubfolder = subfolder;

            if (sessionId == null || filename == null) {
                call.reject("sessionId and filename are required");
                return;
            }
            File tmp = cbzWriteSessions.remove(sessionId);
            if (tmp == null) { call.reject("Unknown or expired save session"); return; }
            final File finalTmp = tmp;

            new Thread(() -> {
                try {
                    if (destUriString != null && !destUriString.isEmpty()) {
                        Uri treeUri = Uri.parse(destUriString);
                        String treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
                        Uri treeDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId);
                        Uri parentDocUri = treeDocUri;

                        if (finalSubfolder != null) {
                            parentDocUri = findOrCreateChildDirectory(treeUri, treeDocUri, finalSubfolder);
                            if (parentDocUri == null) {
                                call.reject("Failed to create manga folder in the chosen download folder");
                                return;
                            }
                        }

                        Uri newFileUri = android.provider.DocumentsContract.createDocument(
                                getActivity().getContentResolver(), parentDocUri, "application/x-cbz", filename);
                        if (newFileUri == null) {
                            call.reject("Failed to create file in the chosen download folder");
                            return;
                        }

                        try (OutputStream os = getActivity().getContentResolver().openOutputStream(newFileUri);
                             java.io.InputStream is = new java.io.FileInputStream(finalTmp)) {
                            if (os == null) { call.reject("Failed to open output stream"); return; }
                            streamCopy(is, os);
                        }

                        JSObject ret = new JSObject();
                        ret.put("success", true);
                        ret.put("filename", filename);
                        ret.put("uri", newFileUri.toString());
                        call.resolve(ret);
                        return;
                    }

                    String relativePath = Environment.DIRECTORY_DOWNLOADS + "/MangaReader"
                            + (finalSubfolder != null ? ("/" + finalSubfolder) : "");

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                        values.put(MediaStore.Downloads.MIME_TYPE, "application/x-cbz");
                        values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);

                        Uri uri = getActivity().getContentResolver().insert(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                        if (uri == null) { call.reject("Failed to create file in Downloads"); return; }

                        try (OutputStream os = getActivity().getContentResolver().openOutputStream(uri);
                             java.io.InputStream is = new java.io.FileInputStream(finalTmp)) {
                            if (os == null) { call.reject("Failed to open output stream"); return; }
                            streamCopy(is, os);
                        }

                        JSObject ret = new JSObject();
                        ret.put("success", true);
                        ret.put("filename", filename);
                        ret.put("uri", uri.toString());
                        call.resolve(ret);
                    } else {
                        File downloadsDir = new File(
                                Environment.getExternalStoragePublicDirectory(
                                        Environment.DIRECTORY_DOWNLOADS),
                                "MangaReader" + (finalSubfolder != null ? ("/" + finalSubfolder) : ""));
                        if (!downloadsDir.exists()) downloadsDir.mkdirs();
                        File outFile = new File(downloadsDir, filename);
                        try (FileOutputStream fos = new FileOutputStream(outFile);
                             java.io.InputStream is = new java.io.FileInputStream(finalTmp)) {
                            streamCopy(is, fos);
                        }

                        JSObject ret = new JSObject();
                        ret.put("success", true);
                        ret.put("filename", filename);
                        ret.put("uri", Uri.fromFile(outFile).toString());
                        call.resolve(ret);
                    }
                } catch (Throwable e) {
                    call.reject("Failed to save CBZ: " + e.getMessage());
                } finally {
                    try { finalTmp.delete(); } catch (Exception ignored) { }
                }
            }).start();
        }

        // Fixed-size buffer, streamed — memory use stays constant (64KB)
        // no matter how large the source file is.
        private void streamCopy(java.io.InputStream is, OutputStream os) throws java.io.IOException {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
        }

        // Looks for a child directory named `name` directly under `parentDocUri`
        // within `treeUri`'s tree, creating it if it doesn't exist yet. Used to
        // give each manga its own subfolder inside a user-chosen SAF download
        // location. Returns null on failure.
        private Uri findOrCreateChildDirectory(Uri treeUri, Uri parentDocUri, String name) {
            android.content.ContentResolver resolver = getActivity().getContentResolver();

            // IMPORTANT: querying parentDocUri directly returns metadata about
            // that one document (the folder itself), not its contents — with
            // at most a single row back, so this could never actually find a
            // previously-created manga folder. That meant every chapter
            // download fell through to createDocument() below, and Android's
            // SAF auto-renames on a name collision instead of erroring
            // ("Manga Title", "Manga Title (1)", "Manga Title (2)", …) — which
            // is why every chapter was landing in its own folder. Listing a
            // directory's children requires the dedicated "child documents"
            // URI, built from the parent's own document id.
            String parentDocId;
            try {
                parentDocId = android.provider.DocumentsContract.getDocumentId(parentDocUri);
            } catch (Exception e) {
                parentDocId = null;
            }
            if (parentDocId == null) return null;

            Uri childDocumentsUri = android.provider.DocumentsContract
                    .buildChildDocumentsUriUsingTree(treeUri, parentDocId);

            android.database.Cursor cursor = null;
            try {
                cursor = resolver.query(
                        childDocumentsUri,
                        new String[]{
                                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                        },
                        null, null, null);
                if (cursor != null) {
                    int nameIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                    int mimeIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE);
                    int idIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                    while (cursor.moveToNext()) {
                        String childName = cursor.getString(nameIdx);
                        String childMime = cursor.getString(mimeIdx);
                        if (name.equals(childName)
                                && android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(childMime)) {
                            String childId = cursor.getString(idIdx);
                            return android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                        }
                    }
                }
            } catch (Exception ignored) {
                // fall through to creation attempt below
            } finally {
                if (cursor != null) cursor.close();
            }

            try {
                return android.provider.DocumentsContract.createDocument(
                        resolver, parentDocUri,
                        android.provider.DocumentsContract.Document.MIME_TYPE_DIR, name);
            } catch (Exception e) {
                return null;
            }
        }

        @PluginMethod
        public void checkCbzExists(PluginCall call) {
            String filename = call.getString("filename");
            if (filename == null) {
                call.reject("filename is required");
                return;
            }

            boolean exists = false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.database.Cursor cursor = getActivity().getContentResolver().query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Downloads.DISPLAY_NAME},
                        MediaStore.Downloads.DISPLAY_NAME + "=? AND " +
                                MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
                        new String[]{filename, "%" + "MangaReader" + "%"},
                        null
                );
                if (cursor != null) {
                    exists = cursor.getCount() > 0;
                    cursor.close();
                }
            } else {
                File f = new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS),
                        "MangaReader/" + filename);
                exists = f.exists();
            }

            JSObject ret = new JSObject();
            ret.put("exists", exists);
            call.resolve(ret);
        }

        @PluginMethod
        public void deleteCbz(PluginCall call) {
            String filename = call.getString("filename");
            if (filename == null) {
                call.reject("filename is required");
                return;
            }

            try {
                boolean deleted = false;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.database.Cursor cursor = getActivity().getContentResolver().query(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            new String[]{MediaStore.Downloads._ID},
                            MediaStore.Downloads.DISPLAY_NAME + "=? AND " +
                                    MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
                            new String[]{filename, "%" + "MangaReader" + "%"},
                            null
                    );
                    if (cursor != null && cursor.moveToFirst()) {
                        long id = cursor.getLong(
                                cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID));
                        Uri deleteUri = Uri.withAppendedPath(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(id));
                        int rows = getActivity().getContentResolver().delete(
                                deleteUri, null, null);
                        deleted = rows > 0;
                        cursor.close();
                    }
                } else {
                    File f = new File(
                            Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS),
                            "MangaReader/" + filename);
                    deleted = f.exists() && f.delete();
                }

                JSObject ret = new JSObject();
                ret.put("success", deleted);
                call.resolve(ret);

            } catch (Exception e) {
                call.reject("Failed to delete CBZ: " + e.getMessage());
            }
        }

        // ── Download Location picker ────────────────────────────────────────
        // Lets the user choose a custom folder (via the Storage Access
        // Framework) where chapters downloaded from the in-app browser get
        // saved, instead of the fixed Downloads/MangaReader folder used by
        // saveCbz() above. Only picks and persists permission for the
        // folder — unlike pickFolder() below, it does not scan for existing
        // .cbz files, since it's choosing a destination, not an import source.
        @PluginMethod
        public void pickDownloadFolder(PluginCall call) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            );
            startActivityForResult(call, intent, "handleDownloadFolderPickerResult");
        }

        @ActivityCallback
        private void handleDownloadFolderPickerResult(PluginCall call, ActivityResult result) {
            if (call == null) return;
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                call.reject("CANCELLED");
                return;
            }

            Uri treeUri = result.getData().getData();
            if (treeUri == null) {
                call.reject("No folder selected");
                return;
            }

            try {
                getActivity().getContentResolver().takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );
            } catch (Exception e) {
                android.util.Log.w("NativePlugin", "Could not persist download folder uri: " + e.getMessage());
            }

            JSObject ret = new JSObject();
            ret.put("uri", treeUri.toString());
            ret.put("name", queryDisplayName(treeUri));
            call.resolve(ret);
        }

        // ── Persistent file picker ────────────────────────────────────────────
        // Lets the user pick CBZ files via Android's native picker. Unlike the
        // HTML <input type="file">, this gives us real content:// URIs that we
        // can keep permission to and re-read after the app restarts.

        @PluginMethod
        public void pickFiles(PluginCall call) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            );
            startActivityForResult(call, intent, "handleFilesPickerResult");
        }

        @ActivityCallback
        private void handleFilesPickerResult(PluginCall call, ActivityResult result) {
            if (call == null) return;
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                call.reject("CANCELLED");
                return;
            }

            Intent data = result.getData();
            JSArray filesArray = new JSArray();

            java.util.List<Uri> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }

            for (Uri uri : uris) {
                try {
                    getActivity().getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                } catch (Exception e) {
                    android.util.Log.w("NativePlugin", "Could not persist uri: " + e.getMessage());
                }

                String name = "chapter.cbz";
                android.database.Cursor cursor = null;
                try {
                    cursor = getActivity().getContentResolver().query(uri, null, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) name = cursor.getString(idx);
                    }
                } catch (Exception e) {
                    android.util.Log.w("NativePlugin", "Could not read name: " + e.getMessage());
                } finally {
                    if (cursor != null) cursor.close();
                }

                if (!name.toLowerCase().endsWith(".cbz")) continue;

                JSObject f = new JSObject();
                f.put("name", name);
                f.put("uri", uri.toString());
                filesArray.put(f);
            }

            JSObject ret = new JSObject();
            ret.put("files", filesArray);
            call.resolve(ret);
        }

        // ── Folder picker ──────────────────────────────────────────────────
        // Lets the user pick a whole folder (optionally containing sub-folders,
        // each treated as its own manga/collection) instead of selecting CBZ
        // files one at a time. Returns groups keyed by sub-folder name so the
        // JS side can create one collection per sub-folder, or a single
        // collection if the picked folder has no sub-folders of its own.

        @PluginMethod
        public void pickFolder(PluginCall call) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            );
            startActivityForResult(call, intent, "handleFolderPickerResult");
        }

        @ActivityCallback
        private void handleFolderPickerResult(PluginCall call, ActivityResult result) {
            if (call == null) return;
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                call.reject("CANCELLED");
                return;
            }

            Uri treeUri = result.getData().getData();
            if (treeUri == null) {
                call.reject("No folder selected");
                return;
            }

            try {
                getActivity().getContentResolver().takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception e) {
                android.util.Log.w("NativePlugin", "Could not persist tree uri: " + e.getMessage());
            }

            // Do the (potentially slow, for large folder trees) traversal off
            // the main thread so the UI/bridge doesn't stall while scanning.
            new Thread(() -> {
                try {
                    String rootDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
                    String rootName = queryDisplayName(treeUri);

                    // Map of "group name" -> list of {name, uri} for CBZ files
                    // found directly inside that group. The root folder itself
                    // is one group (key ""); each immediate sub-folder that
                    // contains CBZ files (directly or in its own descendants)
                    // becomes its own group, keyed by its folder name.
                    java.util.LinkedHashMap<String, JSArray> groups = new java.util.LinkedHashMap<>();
                    groups.put("", new JSArray());

                    scanFolderRecursive(treeUri, rootDocId, "", groups, true);

                    JSArray groupsArray = new JSArray();
                    for (java.util.Map.Entry<String, JSArray> entry : groups.entrySet()) {
                        JSArray filesInGroup = entry.getValue();
                        if (filesInGroup.length() == 0) continue;
                        JSObject group = new JSObject();
                        // Empty key means "directly in the picked root folder"
                        group.put("name", entry.getKey().isEmpty() ? rootName : entry.getKey());
                        group.put("files", filesInGroup);
                        groupsArray.put(group);
                    }

                    JSObject ret = new JSObject();
                    ret.put("rootName", rootName);
                    ret.put("groups", groupsArray);
                    call.resolve(ret);
                } catch (Exception e) {
                    android.util.Log.e("NativePlugin", "Folder scan failed: " + e.getMessage());
                    call.reject("Folder scan failed: " + e.getMessage());
                }
            }).start();
        }

        // Recursively walks a tree-document folder looking for .cbz files.
        // `groupName` is the name of the top-level sub-folder this file
        // belongs to ("" if directly in the picked root). Only one level of
        // grouping is used — files inside deeper nested sub-sub-folders still
        // get attributed to their nearest ancestor group, so a complicated
        // folder structure still collapses sensibly into per-manga groups.
        private void scanFolderRecursive(
                Uri treeUri,
                String parentDocId,
                String groupName,
                java.util.LinkedHashMap<String, JSArray> groups,
                boolean isRootLevel
        ) {
            Uri childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, parentDocId
            );

            android.database.Cursor cursor = null;
            try {
                cursor = getActivity().getContentResolver().query(
                        childrenUri,
                        new String[]{
                                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                        },
                        null, null, null
                );
                if (cursor == null) return;

                while (cursor.moveToNext()) {
                    String docId = cursor.getString(0);
                    String displayName = cursor.getString(1);
                    String mimeType = cursor.getString(2);
                    boolean isDir = android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);

                    if (isDir) {
                        // A sub-folder directly under the root becomes its own
                        // group; sub-folders nested deeper than that keep
                        // bubbling files up to whichever group they're under.
                        String nextGroupName = isRootLevel ? displayName : groupName;
                        if (!groups.containsKey(nextGroupName)) {
                            groups.put(nextGroupName, new JSArray());
                        }
                        scanFolderRecursive(treeUri, docId, nextGroupName, groups, false);
                    } else if (displayName != null && displayName.toLowerCase().endsWith(".cbz")) {
                        Uri fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                                treeUri, docId
                        );
                        JSObject f = new JSObject();
                        f.put("name", displayName);
                        f.put("uri", fileUri.toString());
                        groups.get(groupName).put(f);
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("NativePlugin", "scanFolderRecursive failed for " + parentDocId + ": " + e.getMessage());
            } finally {
                if (cursor != null) cursor.close();
            }
        }

        private String queryDisplayName(Uri treeUri) {
            String name = "My Manhwa";
            try {
                String docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
                Uri docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                android.database.Cursor cursor = getActivity().getContentResolver().query(
                        docUri,
                        new String[]{android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                        null, null, null
                );
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        String n = cursor.getString(0);
                        if (n != null && !n.isEmpty()) name = n;
                    }
                    cursor.close();
                }
            } catch (Exception e) {
                android.util.Log.w("NativePlugin", "queryDisplayName failed: " + e.getMessage());
            }
            return name;
        }

        // Re-read a previously persisted content:// URI — used both right after
        // picking and again on every app launch to restore saved chapters.
        // ── Local file cache ──────────────────────────────────────────────────
        // Copies content:// URI bytes into the app's private cache dir on first
        // load. Subsequent reads come from fast local storage instead of going
        // through the (sometimes slow) document provider every single launch.

        // Cheap existence check — lets the JS side know whether a chapter
        // is already cached without doing any actual file I/O on its data,
        // used by the background prefetcher to avoid redundant work.
        @PluginMethod
        public void isCached(PluginCall call) {
            String cacheKey = call.getString("cacheKey");
            if (cacheKey == null) { call.reject("cacheKey is required"); return; }
            File cacheDir = new File(getActivity().getCacheDir(), "manga_cache");
            File cachedFile = new File(cacheDir, cacheKey + ".cbz");
            JSObject ret = new JSObject();
            ret.put("cached", cachedFile.exists());
            call.resolve(ret);
        }

        @PluginMethod
        public void cacheFileFromUri(PluginCall call) {
            String uriString = call.getString("uri");
            String cacheKey = call.getString("cacheKey");
            if (uriString == null || cacheKey == null) {
                call.reject("uri and cacheKey are required");
                return;
            }

            new Thread(() -> {
                try {
                    File cacheDir = new File(getActivity().getCacheDir(), "manga_cache");
                    if (!cacheDir.exists()) cacheDir.mkdirs();
                    File outFile = new File(cacheDir, cacheKey + ".cbz");

                    Uri uri = Uri.parse(uriString);
                    java.io.InputStream is = getActivity().getContentResolver().openInputStream(uri);
                    if (is == null) { call.reject("Could not open source file"); return; }

                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                        }
                    }
                    is.close();

                    pruneMangaCacheIfNeeded(cacheDir);

                    JSObject ret = new JSObject();
                    ret.put("success", true);
                    ret.put("path", outFile.getAbsolutePath());
                    call.resolve(ret);
                } catch (Exception e) {
                    call.reject("Cache failed: " + e.getMessage());
                }
            }).start();
        }

        // Read a file already cached locally — much faster than re-fetching
        // from the original content:// URI through the document provider.
        @PluginMethod
        public void readCachedFile(PluginCall call) {
            String cacheKey = call.getString("cacheKey");
            if (cacheKey == null) { call.reject("cacheKey is required"); return; }

            new Thread(() -> {
                try {
                    File cacheDir = new File(getActivity().getCacheDir(), "manga_cache");
                    File cachedFile = new File(cacheDir, cacheKey + ".cbz");
                    if (!cachedFile.exists()) {
                        call.reject("NOT_CACHED");
                        return;
                    }
                    java.io.InputStream is = new java.io.FileInputStream(cachedFile);
                    byte[] bytes = readAllBytesPersist(is);
                    is.close();
                    String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                    JSObject ret = new JSObject();
                    ret.put("data", b64);
                    call.resolve(ret);
                } catch (Throwable e) {
                    // Throwable, not Exception: encoding a very large cached
                    // file to base64 in one shot can throw OutOfMemoryError,
                    // which used to escape this catch and crash the app.
                    // This whole-file path should only ever be hit as a
                    // fallback anyway — see getZipEntryNames/getPageImage,
                    // which read one page at a time instead.
                    call.reject("Read failed: " + e.getMessage());
                }
            }).start();
        }

        // Remove a cached file (used when a chapter/collection is deleted)
        @PluginMethod
        public void deleteCachedFile(PluginCall call) {
            String cacheKey = call.getString("cacheKey");
            if (cacheKey == null) { call.reject("cacheKey is required"); return; }
            try {
                File cacheDir = new File(getActivity().getCacheDir(), "manga_cache");
                File cachedFile = new File(cacheDir, cacheKey + ".cbz");
                boolean deleted = !cachedFile.exists() || cachedFile.delete();
                JSObject ret = new JSObject();
                ret.put("success", deleted);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Delete failed: " + e.getMessage());
            }
        }

        // Delete the actual underlying document a content:// URI points to.
        // Used when removing a chapter that was added via the persistent file
        // picker (pickFiles) — unlike deleteCbz (which only handles files we
        // wrote ourselves into Downloads/MangaReader), this deletes whatever
        // file the user originally picked, wherever it lives on disk.
        @PluginMethod
        public void deleteUri(PluginCall call) {
            String uriString = call.getString("uri");
            if (uriString == null) { call.reject("uri is required"); return; }

            try {
                Uri uri = Uri.parse(uriString);
                boolean deleted = android.provider.DocumentsContract.deleteDocument(
                        getActivity().getContentResolver(), uri);

                // Whether or not deletion succeeded, release our permission
                // grant for this URI since we no longer need it.
                try {
                    getActivity().getContentResolver().releasePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                } catch (Exception ignored) { }

                JSObject ret = new JSObject();
                ret.put("success", deleted);
                call.resolve(ret);
            } catch (SecurityException e) {
                // We only had read permission (some providers refuse write
                // grants), so we can't delete the source file — caller should
                // fall back to just removing it from the app's own list.
                call.reject("NO_WRITE_PERMISSION: " + e.getMessage());
            } catch (Exception e) {
                call.reject("Delete failed: " + e.getMessage());
            }
        }

        // Extract just the first page image from a CBZ as a small downscaled
        // JPEG thumbnail, plus the total page count — without ever sending
        // the full archive across the JS bridge. This is what makes the
        // chapter grid populate near-instantly instead of waiting for every
        // file to be fully read and base64-encoded.
        @PluginMethod
        public void getThumbnail(PluginCall call) {
            String uriString = call.getString("uri");
            Integer maxDim = call.getInt("maxDim");
            final int thumbMaxDim = (maxDim != null) ? maxDim : 400;

            new Thread(() -> {
                // Deliberately pass a null cacheKey here — a thumbnail only
                // ever reads a single entry once, so there's no benefit to
                // keeping the whole archive duplicated in app storage
                // afterwards (that's exactly what used to make adding a
                // manhwa balloon the cache by the size of the whole thing:
                // every chapter got thumbnailed, and every one of those
                // permanently cached a full copy). resolveToLocalFile(uri,
                // null) still gives us the real seekable File ZipFile needs
                // — it's just a throwaway temp file we clean up below.
                File tempCopy = null;
                try {
                    // We need a real, seekable File for java.util.zip.ZipFile
                    // (it reads the central directory at the end of the
                    // archive, not a sequential stream — this is what makes
                    // it work correctly for STORED/uncompressed entries with
                    // data descriptors, which java.util.zip.ZipInputStream
                    // cannot reliably read and silently produces no entries
                    // for. Mihon-exported CBZs use exactly this format.)
                    tempCopy = resolveToLocalFile(uriString, null);
                    if (tempCopy == null) {
                        call.reject("Could not open source file");
                        return;
                    }

                    java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(tempCopy);
                    try {
                        java.util.List<java.util.zip.ZipEntry> imageEntries = new java.util.ArrayList<>();
                        java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
                        while (entries.hasMoreElements()) {
                            java.util.zip.ZipEntry entry = entries.nextElement();
                            if (!entry.isDirectory() && isImageName(entry.getName())) {
                                imageEntries.add(entry);
                            }
                        }

                        if (imageEntries.isEmpty()) {
                            JSObject ret = new JSObject();
                            ret.put("totalPages", 0);
                            ret.put("thumbnail", (String) null);
                            call.resolve(ret);
                            return;
                        }

                        imageEntries.sort((a, b) -> NATURAL_ORDER.compare(a.getName(), b.getName()));
                        java.util.zip.ZipEntry firstEntry = imageEntries.get(0);
                        int totalPages = imageEntries.size();

                        byte[] imgBytes;
                        try (java.io.InputStream entryStream = zipFile.getInputStream(firstEntry)) {
                            imgBytes = readAllBytesPersist(entryStream);
                        }
                        String thumbBase64 = downscaleToBase64Jpeg(imgBytes, thumbMaxDim);

                        JSObject ret = new JSObject();
                        ret.put("totalPages", totalPages);
                        ret.put("thumbnail", thumbBase64);
                        call.resolve(ret);
                    } finally {
                        try { zipFile.close(); } catch (Exception ignored) { }
                    }
                } catch (Throwable e) {
                    call.reject("Thumbnail extraction failed: " + e.getMessage());
                } finally {
                    if (tempCopy != null) {
                        try { tempCopy.delete(); } catch (Exception ignored) { }
                    }
                }
            }).start();
        }

        // Ensures we have a real, seekable File on disk for the given source
        // (java.util.zip.ZipFile needs random access, not a stream). Prefers
        // the existing local cache; if not cached yet, copies the source
        // into the cache now (not a disposable temp file) so subsequent
        // calls for the same chapter — e.g. reading page 2, 3, 4... right
        // after page 1 — don't repeatedly re-copy the whole source.
        private File resolveToLocalFile(String uriString, String cacheKey) throws java.io.IOException {
            File cacheDir = new File(getActivity().getCacheDir(), "manga_cache");
            if (cacheKey != null) {
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File cachedFile = new File(cacheDir, cacheKey + ".cbz");
                if (cachedFile.exists()) return cachedFile;

                if (uriString != null) {
                    Uri uri = Uri.parse(uriString);
                    try (java.io.InputStream in = getActivity().getContentResolver().openInputStream(uri)) {
                        if (in == null) return null;
                        File tmp = new File(cacheDir, cacheKey + ".cbz.tmp");
                        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        }
                        // Atomic-ish rename so a half-written file is never
                        // mistaken for a complete cache entry by another call.
                        File resolved = tmp.renameTo(cachedFile) ? cachedFile : tmp;
                        pruneMangaCacheIfNeeded(cacheDir);
                        return resolved;
                    }
                }
            }
            if (uriString != null) {
                Uri uri = Uri.parse(uriString);
                try (java.io.InputStream in = getActivity().getContentResolver().openInputStream(uri)) {
                    if (in == null) return null;
                    File tmp = File.createTempFile("src_", ".cbz", getActivity().getCacheDir());
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    return tmp;
                }
            }
            return null;
        }

        // Returns the sorted list of image page names inside the CBZ, for
        // the JS reader to know the total page count and request pages by
        // index without ever needing the full archive client-side.
        @PluginMethod
        public void getZipEntryNames(PluginCall call) {
            String uriString = call.getString("uri");
            String cacheKey = call.getString("cacheKey");

            new Thread(() -> {
                try {
                    File zipFileOnDisk = resolveToLocalFile(uriString, cacheKey);
                    if (zipFileOnDisk == null) {
                        call.reject("Could not open source file");
                        return;
                    }

                    java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(zipFileOnDisk);
                    try {
                        java.util.List<String> imageNames = new java.util.ArrayList<>();
                        java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
                        while (entries.hasMoreElements()) {
                            java.util.zip.ZipEntry entry = entries.nextElement();
                            if (!entry.isDirectory() && isImageName(entry.getName())) {
                                imageNames.add(entry.getName());
                            }
                        }
                        imageNames.sort(NATURAL_ORDER);

                        JSObject ret = new JSObject();
                        com.getcapacitor.JSArray namesArray = new com.getcapacitor.JSArray();
                        for (String n : imageNames) namesArray.put(n);
                        ret.put("names", namesArray);
                        call.resolve(ret);
                    } finally {
                        try { zipFile.close(); } catch (Exception ignored) { }
                    }
                } catch (Exception e) {
                    call.reject("Failed to read archive: " + e.getMessage());
                }
            }).start();
        }

        // Extracts a single page image (by its entry name, as returned by
        // getZipEntryNames) at full resolution — no downscaling, unlike
        // getThumbnail, since this is for actually reading the chapter.
        @PluginMethod
        public void getPageImage(PluginCall call) {
            String uriString = call.getString("uri");
            String cacheKey = call.getString("cacheKey");
            String entryName = call.getString("entryName");
            if (entryName == null) { call.reject("entryName is required"); return; }

            new Thread(() -> {
                try {
                    File zipFileOnDisk = resolveToLocalFile(uriString, cacheKey);
                    if (zipFileOnDisk == null) {
                        call.reject("Could not open source file");
                        return;
                    }

                    java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(zipFileOnDisk);
                    try {
                        java.util.zip.ZipEntry entry = zipFile.getEntry(entryName);
                        if (entry == null) {
                            call.reject("Page not found: " + entryName);
                            return;
                        }
                        byte[] imgBytes;
                        try (java.io.InputStream entryStream = zipFile.getInputStream(entry)) {
                            imgBytes = readAllBytesPersist(entryStream);
                        }
                        String b64 = android.util.Base64.encodeToString(imgBytes, android.util.Base64.NO_WRAP);

                        // Help the JS side pick the right data: URL prefix
                        String lower = entryName.toLowerCase(java.util.Locale.ROOT);
                        String mime = lower.endsWith(".png") ? "image/png"
                                : lower.endsWith(".webp") ? "image/webp"
                                  : lower.endsWith(".gif") ? "image/gif"
                                    : lower.endsWith(".avif") ? "image/avif"
                                      : (lower.endsWith(".heic") || lower.endsWith(".heif")) ? "image/heic"
                                        : "image/jpeg";

                        JSObject ret = new JSObject();
                        ret.put("data", b64);
                        ret.put("mime", mime);
                        call.resolve(ret);
                    } finally {
                        try { zipFile.close(); } catch (Exception ignored) { }
                    }
                } catch (Throwable e) {
                    call.reject("Failed to extract page: " + e.getMessage());
                }
            }).start();
        }


        private static boolean isImageName(String name) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(".avif") || lower.endsWith(".gif") || lower.endsWith(".heic")
                    || lower.endsWith(".heif") || lower.endsWith(".jpeg") || lower.endsWith(".jpg")
                    || lower.endsWith(".png") || lower.endsWith(".webp");
        }

        // Mirrors the JS localeCompare(numeric:true) sort so the "first page"
        // we pick natively matches what the reader would show as page 1.
        private static final java.util.Comparator<String> NATURAL_ORDER = (a, b) -> {
            int i = 0, j = 0;
            while (i < a.length() && j < b.length()) {
                char ca = a.charAt(i), cb = b.charAt(j);
                if (Character.isDigit(ca) && Character.isDigit(cb)) {
                    int si = i, sj = j;
                    while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                    while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                    String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                    String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                    if (na.length() != nb.length()) return na.length() - nb.length();
                    int cmp = na.compareTo(nb);
                    if (cmp != 0) return cmp;
                } else {
                    int cmp = Character.toLowerCase(ca) - Character.toLowerCase(cb);
                    if (cmp != 0) return cmp;
                    i++; j++;
                }
            }
            return (a.length() - i) - (b.length() - j);
        };

        // Portrait aspect (width/height) the thumbnails are DISPLAYED at
        // (chapter-thumb is 92x124 ≈ 0.74). We produce the thumbnail already
        // cropped to this shape so the displayed <img object-fit:cover> shows
        // it 1:1 with no upscaling.
        private static final float THUMB_ASPECT = 0.72f;

        private static String downscaleToBase64Jpeg(byte[] imgBytes, int maxDim) {
            android.graphics.BitmapFactory.Options boundsOpts = new android.graphics.BitmapFactory.Options();
            boundsOpts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length, boundsOpts);
            int w = boundsOpts.outWidth, h = boundsOpts.outHeight;
            if (w <= 0 || h <= 0) return null;

            // Old behaviour scaled the LONGEST side to maxDim. For tall
            // webtoon/manhwa first pages (e.g. 720x10000) that collapsed the
            // WIDTH to ~58px, which then had to be upscaled into the portrait
            // card → blurry. Instead, crop a TOP-ANCHORED region at the
            // display aspect ratio, so the width stays large and sharp.
            int cropW, cropH;
            if ((float) w / h > THUMB_ASPECT) {
                // Wider than target (landscape-ish cover): clamp width.
                cropH = h;
                cropW = Math.round(h * THUMB_ASPECT);
            } else {
                // Taller than target (normal portrait cover or webtoon strip):
                // clamp height, keep full width.
                cropW = w;
                cropH = Math.round(w / THUMB_ASPECT);
                if (cropH > h) cropH = h; // near-square sources: don't overreach
            }
            int cropX = (w - cropW) / 2; // horizontally centered
            int cropY = 0;               // anchored to the TOP of the page

            // Target output: THUMB_ASPECT portrait, long side (height) = maxDim.
            int targetH = Math.min(maxDim, cropH);
            int targetW = Math.round(targetH * ((float) cropW / cropH));

            android.graphics.Bitmap bitmap = null;
            try {
                // Region-decode just the crop (memory-safe for huge strips)
                // with inSampleSize so we don't decode more than we need.
                android.graphics.BitmapRegionDecoder rd =
                        android.graphics.BitmapRegionDecoder.newInstance(imgBytes, 0, imgBytes.length, false);
                int sampleSize = 1;
                while ((cropW / sampleSize) > targetW * 2 && (cropH / sampleSize) > targetH * 2) {
                    sampleSize *= 2;
                }
                android.graphics.BitmapFactory.Options regionOpts = new android.graphics.BitmapFactory.Options();
                regionOpts.inSampleSize = sampleSize;
                bitmap = rd.decodeRegion(
                        new android.graphics.Rect(cropX, cropY, cropX + cropW, cropY + cropH), regionOpts);
                rd.recycle();
            } catch (Throwable regionErr) {
                // Some encoders (progressive/CMYK JPEGs, certain PNGs) aren't
                // region-decodable — fall back to a full decode + crop.
                android.graphics.BitmapFactory.Options decodeOpts = new android.graphics.BitmapFactory.Options();
                int sampleSize = 1;
                while ((cropW / sampleSize) > targetW * 2 && (cropH / sampleSize) > targetH * 2) {
                    sampleSize *= 2;
                }
                decodeOpts.inSampleSize = sampleSize;
                android.graphics.Bitmap full = android.graphics.BitmapFactory.decodeByteArray(
                        imgBytes, 0, imgBytes.length, decodeOpts);
                if (full == null) return null;
                int sx = cropX / sampleSize, sy = cropY / sampleSize;
                int sw = Math.min(cropW / sampleSize, full.getWidth() - sx);
                int sh = Math.min(cropH / sampleSize, full.getHeight() - sy);
                try {
                    bitmap = android.graphics.Bitmap.createBitmap(full, sx, sy, Math.max(1, sw), Math.max(1, sh));
                } catch (Throwable cropErr) {
                    bitmap = full;
                }
                if (bitmap != full) full.recycle();
            }
            if (bitmap == null) return null;

            // Final exact scale down to the target thumbnail size.
            if (bitmap.getWidth() > targetW || bitmap.getHeight() > targetH) {
                android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                        bitmap, Math.max(1, targetW), Math.max(1, targetH), true);
                if (scaled != bitmap) bitmap.recycle();
                bitmap = scaled;
            }

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, baos);
            bitmap.recycle();
            return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP);
        }

        @PluginMethod
        public void readUriAsBase64(PluginCall call) {
            String uriString = call.getString("uri");
            if (uriString == null) { call.reject("uri is required"); return; }

            new Thread(() -> {
                try {
                    Uri uri = Uri.parse(uriString);
                    java.io.InputStream is = getActivity().getContentResolver().openInputStream(uri);
                    if (is == null) { call.reject("Could not open file — it may have been moved or deleted"); return; }
                    byte[] bytes = readAllBytesPersist(is);
                    is.close();
                    String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                    JSObject ret = new JSObject();
                    ret.put("data", b64);
                    call.resolve(ret);
                } catch (Throwable e) {
                    // Throwable, not Exception — see readCachedFile above for
                    // why: a large file here can OOM during base64 encoding,
                    // and that must not be allowed to crash the whole app.
                    call.reject("Read failed: " + e.getMessage());
                }
            }).start();
        }

        // Check whether we still have permission to read a previously saved URI
        @PluginMethod
        public void checkUriValid(PluginCall call) {
            String uriString = call.getString("uri");
            if (uriString == null) { call.reject("uri is required"); return; }
            boolean valid = false;
            try {
                Uri uri = Uri.parse(uriString);
                java.io.InputStream is = getActivity().getContentResolver().openInputStream(uri);
                if (is != null) { is.close(); valid = true; }
            } catch (Exception e) {
                valid = false;
            }
            JSObject ret = new JSObject();
            ret.put("valid", valid);
            call.resolve(ret);
        }

        private byte[] readAllBytesPersist(java.io.InputStream is) throws java.io.IOException {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[65536];
            int n;
            while ((n = is.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        }

        // ── Manga source HTTP helpers ────────────────────────────────────────
        // JS source modules can't fetch() external sites from inside the
        // WebView (CORS), so these two methods do the network call natively
        // and hand the result back across the bridge.

        @PluginMethod
        public void httpGet(PluginCall call) {
            String urlString = call.getString("url");
            String userAgent = call.getString("userAgent");
            JSObject extraHeaders = call.getObject("headers");
            if (urlString == null) { call.reject("url is required"); return; }

            new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(urlString);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("User-Agent", userAgent != null ? userAgent :
                            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36");
                    conn.setRequestProperty("Accept",
                            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                    conn.setRequestProperty("Accept-Encoding", "gzip");
                    conn.setInstanceFollowRedirects(true);

                    // Optional caller-supplied headers (e.g. "Authorization":
                    // "Bearer <token>" for authenticated MangaDex requests).
                    // Applied after the defaults above so callers can override
                    // them if needed.
                    if (extraHeaders != null) {
                        java.util.Iterator<String> hKeys = extraHeaders.keys();
                        while (hKeys.hasNext()) {
                            String hKey = hKeys.next();
                            String hVal = extraHeaders.getString(hKey);
                            if (hVal != null) conn.setRequestProperty(hKey, hVal);
                        }
                    }

                    int status = conn.getResponseCode();
                    java.io.InputStream rawStream = (status >= 200 && status < 400)
                            ? conn.getInputStream() : conn.getErrorStream();
                    if (rawStream == null) {
                        call.reject("HTTP " + status + " with no response body");
                        return;
                    }

                    String encoding = conn.getContentEncoding();
                    java.io.InputStream stream = ("gzip".equalsIgnoreCase(encoding))
                            ? new GZIPInputStream(rawStream) : rawStream;

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, "UTF-8"));
                    StringBuilder body = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line).append('\n');
                    }
                    reader.close();

                    JSObject ret = new JSObject();
                    ret.put("status", status);
                    ret.put("body", body.toString());
                    ret.put("finalUrl", conn.getURL().toString());
                    call.resolve(ret);
                } catch (Exception e) {
                    call.reject("httpGet failed: " + e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }).start();
        }

        // POSTs a form-urlencoded body — used for the MangaDex OAuth token
        // endpoint (login + refresh), which is the first place this app
        // needs anything other than a plain GET.
        @PluginMethod
        public void httpPostForm(PluginCall call) {
            String urlString = call.getString("url");
            JSObject form = call.getObject("form");
            if (urlString == null || form == null) { call.reject("url and form are required"); return; }

            new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(urlString);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setInstanceFollowRedirects(true);

                    StringBuilder formBody = new StringBuilder();
                    java.util.Iterator<String> keys = form.keys();
                    boolean first = true;
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = form.getString(key);
                        if (!first) formBody.append('&');
                        first = false;
                        formBody.append(java.net.URLEncoder.encode(key, "UTF-8"))
                                .append('=')
                                .append(java.net.URLEncoder.encode(value != null ? value : "", "UTF-8"));
                    }

                    byte[] formBytes = formBody.toString().getBytes("UTF-8");
                    conn.setRequestProperty("Content-Length", String.valueOf(formBytes.length));
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(formBytes);
                    }

                    int status = conn.getResponseCode();
                    java.io.InputStream rawStream = (status >= 200 && status < 400)
                            ? conn.getInputStream() : conn.getErrorStream();

                    String responseBody = "";
                    if (rawStream != null) {
                        String encoding = conn.getContentEncoding();
                        java.io.InputStream stream = ("gzip".equalsIgnoreCase(encoding))
                                ? new GZIPInputStream(rawStream) : rawStream;
                        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
                        StringBuilder respBuilder = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            respBuilder.append(line).append('\n');
                        }
                        reader.close();
                        responseBody = respBuilder.toString();
                    }

                    JSObject ret = new JSObject();
                    ret.put("status", status);
                    ret.put("body", responseBody);
                    call.resolve(ret);
                } catch (Exception e) {
                    call.reject("httpPostForm failed: " + e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }).start();
        }

        @PluginMethod
        public void httpGetImage(PluginCall call) {
            String urlString = call.getString("url");
            String userAgent = call.getString("userAgent");
            String referer   = call.getString("referer");
            if (urlString == null) { call.reject("url is required"); return; }

            new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(urlString);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("User-Agent", userAgent != null ? userAgent :
                            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36");
                    if (referer != null) conn.setRequestProperty("Referer", referer);
                    conn.setInstanceFollowRedirects(true);

                    int status = conn.getResponseCode();
                    if (status < 200 || status >= 400) {
                        call.reject("HTTP " + status + " fetching image");
                        return;
                    }

                    String mime = conn.getContentType();
                    byte[] bytes = readAllBytesPersist(conn.getInputStream());
                    String b64 = android.util.Base64.encodeToString(
                            bytes, android.util.Base64.NO_WRAP);

                    JSObject ret = new JSObject();
                    ret.put("data", b64);
                    ret.put("mime", mime != null ? mime : "image/jpeg");
                    call.resolve(ret);
                } catch (Exception e) {
                    call.reject("httpGetImage failed: " + e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }).start();
        }

        // ══════════════════════════════════════════════════════════════════
        //  Mihon-style extension system (ext*)
        //  Kotlin backing classes live in com.azan.readermc.ext.
        //  All heavy work runs on extExecutor — never the main thread.
        // ══════════════════════════════════════════════════════════════════

        private volatile com.azan.readermc.ext.ExtensionManager extManager;
        private final java.util.concurrent.ExecutorService extExecutor =
                java.util.concurrent.Executors.newFixedThreadPool(4);

        private com.azan.readermc.ext.ExtensionManager ext() {
            com.azan.readermc.ext.ExtensionManager m = extManager;
            if (m == null) {
                synchronized (this) {
                    m = extManager;
                    if (m == null) {
                        com.azan.readermc.ext.ExtensionBootstrap.INSTANCE.init(getContext());
                        m = new com.azan.readermc.ext.ExtensionManager(getContext());
                        m.loadInstalled();
                        extManager = m;
                    }
                }
            }
            return m;
        }

        private interface ExtWork { Object run() throws Exception; }

        /** Runs work on the pool; resolves with {"value": <result>} JSON. */
        private void extRun(PluginCall call, String opName, ExtWork work) {
            extExecutor.submit(() -> {
                try {
                    Object result = work.run();
                    JSObject ret = new JSObject();
                    if (result instanceof JSONObject) {
                        ret.put("value", (JSONObject) result);
                    } else if (result instanceof org.json.JSONArray) {
                        ret.put("value", (org.json.JSONArray) result);
                    } else if (result != null) {
                        ret.put("value", String.valueOf(result));
                    }
                    call.resolve(ret);
                } catch (Throwable e) {
                    // Throwable, not Exception: extension code can throw
                    // Errors (NoClassDefFound etc.) and we must reject, not
                    // silently swallow and leak the pending call.
                    call.reject(opName + " failed: " + e.getMessage());
                }
            });
        }

        // ── Repo management ──────────────────────────────────────────────

        @PluginMethod
        public void extRepoList(PluginCall call) {
            extRun(call, "extRepoList", () -> new org.json.JSONArray(ext().repoList()));
        }

        @PluginMethod
        public void extRepoAdd(PluginCall call) {
            String url = call.getString("url");
            if (url == null || url.trim().isEmpty()) { call.reject("url is required"); return; }
            extRun(call, "extRepoAdd", () -> new org.json.JSONArray(ext().repoAdd(url)));
        }

        @PluginMethod
        public void extRepoRemove(PluginCall call) {
            String url = call.getString("url");
            if (url == null) { call.reject("url is required"); return; }
            extRun(call, "extRepoRemove", () -> new org.json.JSONArray(ext().repoRemove(url)));
        }

        @PluginMethod
        public void extRepoIndex(PluginCall call) {
            String repo = call.getString("repo");
            if (repo == null) { call.reject("repo is required"); return; }
            extRun(call, "extRepoIndex", () ->
                    ext().fetchRepoIndex(ext().normalizeRepoUrl(repo)));
        }

        // ── Install / uninstall / list ───────────────────────────────────

        @PluginMethod
        public void extInstall(PluginCall call) {
            String repo = call.getString("repo");
            String pkg = call.getString("pkg");
            String apk = call.getString("apk");
            if (repo == null || pkg == null || apk == null) {
                call.reject("repo, pkg and apk are required"); return;
            }
            extRun(call, "extInstall", () -> {
                ext().install(ext().normalizeRepoUrl(repo), pkg, apk);
                return ext().installedJson();
            });
        }

        @PluginMethod
        public void extUninstall(PluginCall call) {
            String pkg = call.getString("pkg");
            if (pkg == null) { call.reject("pkg is required"); return; }
            extRun(call, "extUninstall", () -> {
                ext().uninstall(pkg);
                return ext().installedJson();
            });
        }

        @PluginMethod
        public void extListInstalled(PluginCall call) {
            extRun(call, "extListInstalled", () -> ext().installedJson());
        }

        // ── Source calls (browse / search / read) ────────────────────────

        @PluginMethod
        public void extPopular(PluginCall call) {
            String sourceId = call.getString("sourceId");
            int page = call.getInt("page", 1);
            if (sourceId == null) { call.reject("sourceId is required"); return; }
            extRun(call, "extPopular", () ->
                    com.azan.readermc.ext.SourceBridge.INSTANCE.popular(ext(), sourceId, page));
        }

        @PluginMethod
        public void extLatest(PluginCall call) {
            String sourceId = call.getString("sourceId");
            int page = call.getInt("page", 1);
            if (sourceId == null) { call.reject("sourceId is required"); return; }
            extRun(call, "extLatest", () ->
                    com.azan.readermc.ext.SourceBridge.INSTANCE.latest(ext(), sourceId, page));
        }

        @PluginMethod
        public void extSearch(PluginCall call) {
            String sourceId = call.getString("sourceId");
            String query = call.getString("query", "");
            String filtersJson = call.getString("filtersJson", null);
            int page = call.getInt("page", 1);
            if (sourceId == null) { call.reject("sourceId is required"); return; }
            extRun(call, "extSearch", () ->
                    com.azan.readermc.ext.SourceBridge.INSTANCE.search(ext(), sourceId, query, page, filtersJson));
        }

        @PluginMethod
        public void extFilters(PluginCall call) {
            String sourceId = call.getString("sourceId");
            if (sourceId == null) { call.reject("sourceId is required"); return; }
            extRun(call, "extFilters", () ->
                    com.azan.readermc.ext.SourceBridge.INSTANCE.filters(ext(), sourceId));
        }

        // Pushes (or clears, when token is null/absent) a bearer token for one
        // host. Every extension OkHttp request to that host then carries
        // Authorization automatically — this is how the app's MangaDex login
        // authenticates the MangaDex extension's native API calls.
        @PluginMethod
        public void extSetHostAuth(PluginCall call) {
            String host = call.getString("host");
            String token = call.getString("token", null);
            if (host == null) { call.reject("host is required"); return; }
            eu.kanade.tachiyomi.network.HostAuthStore.INSTANCE.set(host, token);
            call.resolve();
        }

        @PluginMethod
        public void extMangaDetails(PluginCall call) {
            String sourceId = call.getString("sourceId");
            String mangaUrl = call.getString("mangaUrl");
            if (sourceId == null || mangaUrl == null) { call.reject("sourceId and mangaUrl are required"); return; }
            extRun(call, "extMangaDetails", () ->
                    com.azan.readermc.ext.SourceBridge.INSTANCE.mangaDetails(ext(), sourceId, mangaUrl));
        }

        @PluginMethod
        public void extChapterList(PluginCall call) {
            String sourceId = call.getString("sourceId");
            String mangaUrl = call.getString("mangaUrl");
            if (sourceId == null || mangaUrl == null) { call.reject("sourceId and mangaUrl are required"); return; }
            extRun(call, "extChapterList", () ->
                    com.azan.readermc.ext.SourceBridge.INSTANCE.chapterList(ext(), sourceId, mangaUrl));
        }

        @PluginMethod
        public void extPageList(PluginCall call) {
            String sourceId = call.getString("sourceId");
            String chapterUrl = call.getString("chapterUrl");
            if (sourceId == null || chapterUrl == null) { call.reject("sourceId and chapterUrl are required"); return; }
            extRun(call, "extPageList", () ->
                    com.azan.readermc.ext.SourceBridge.INSTANCE.pageList(ext(), sourceId, chapterUrl));
        }

        @PluginMethod
        public void extGetImage(PluginCall call) {
            String sourceId = call.getString("sourceId");
            JSObject page = call.getObject("page");
            if (sourceId == null || page == null) { call.reject("sourceId and page are required"); return; }
            extRun(call, "extGetImage", () ->
                    com.azan.readermc.ext.SourceBridge.INSTANCE.fetchImage(ext(), sourceId, page));
        }

        @PluginMethod
        public void extGetCover(PluginCall call) {
            String sourceId = call.getString("sourceId");
            String url = call.getString("url");
            if (sourceId == null || url == null) { call.reject("sourceId and url are required"); return; }
            extRun(call, "extGetCover", () ->
                    com.azan.readermc.ext.SourceBridge.INSTANCE.fetchCover(ext(), sourceId, url));
        }
    }
}
