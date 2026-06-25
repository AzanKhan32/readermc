package com.azan.readermc;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.BridgeActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Base64;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(NativePlugin.class);
        super.onCreate(savedInstanceState);
    }

    // ─── Capacitor Plugin ────────────────────────────────────────────────────

    @CapacitorPlugin(name = "NativePlugin")
    public static class NativePlugin extends Plugin {

        private static final int WEBVIEW_REQUEST_CODE = 1001;

        // Called from JS: NativePlugin.openWebView({ url: "https://..." })
        @PluginMethod
        public void openWebView(PluginCall call) {
            String url = call.getString("url");
            if (url == null || url.isEmpty()) {
                call.reject("URL is required");
                return;
            }
            saveCall(call);
            Intent intent = new Intent(getActivity(), WebViewActivity.class);
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

        // Called from JS: NativePlugin.saveCbz({ filename: "001.cbz", data: "<base64>" })
        @PluginMethod
        public void saveCbz(PluginCall call) {
            String filename = call.getString("filename");
            String base64Data = call.getString("data");

            if (filename == null || base64Data == null) {
                call.reject("filename and data are required");
                return;
            }

            try {
                byte[] bytes = Base64.getDecoder().decode(base64Data);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ — use MediaStore, no permissions needed
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                    values.put(MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/MangaReader");

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
                } else {
                    // Android 9 and below — write directly to Downloads folder
                    File downloadsDir = new File(
                            Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS), "MangaReader");
                    if (!downloadsDir.exists()) downloadsDir.mkdirs();

                    File outFile = new File(downloadsDir, filename);
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(bytes);
                    }
                }

                JSObject ret = new JSObject();
                ret.put("success", true);
                ret.put("filename", filename);
                call.resolve(ret);

            } catch (Exception e) {
                call.reject("Failed to save CBZ: " + e.getMessage());
            }
        }

        // Called from JS: NativePlugin.checkCbzExists({ filename: "001.cbz" })
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
    }
}
