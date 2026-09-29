package com.zyvo.valagent;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("SetJavaScriptEnabled")
public class MainActivity extends Activity {

    private WebView webView;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Map<String, HttpURLConnection> active = new ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        webView.setBackgroundColor(Color.parseColor("#F6F7F9"));
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    private void eval(final String js) {
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void emitDelta(String callId, String chunk) {
        eval("window.__delta(" + JSONObject.quote(callId) + "," + JSONObject.quote(chunk) + ")");
    }

    private void emitDone(String callId, String resultJson) {
        eval("window.__done(" + JSONObject.quote(callId) + "," + JSONObject.quote(resultJson) + ")");
    }

    private String headersJson(HttpURLConnection conn) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
            String name = e.getKey();
            if (name == null || e.getValue() == null || e.getValue().isEmpty()) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append(JSONObject.quote(name.toLowerCase()))
              .append(":")
              .append(JSONObject.quote(String.join(", ", e.getValue())));
        }
        return sb.append("}").toString();
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
        br.close();
        return sb.toString();
    }

    private void finish(String callId, int status, String headers, String body) {
        try {
            JSONObject r = new JSONObject();
            r.put("status", status);
            r.put("headers", new JSONObject(headers));
            r.put("body", body == null ? "" : body);
            emitDone(callId, r.toString());
        } catch (Exception ignored) {}
    }

    private void fail(String callId, String msg) {
        try {
            JSONObject r = new JSONObject();
            r.put("status", 0);
            r.put("error", msg == null ? "unknown error" : msg);
            r.put("headers", new JSONObject());
            r.put("body", "");
            emitDone(callId, r.toString());
        } catch (Exception ignored) {}
    }

    private class Bridge {

        @JavascriptInterface
        public void post(final String callId, final String urlStr, final String headersJson,
                         final String body, final int streamFlag) {
            pool.execute(() -> {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(300000);
                    conn.setDoOutput(true);
                    conn.setUseCaches(false);

                    JSONObject headers = new JSONObject(headersJson);
                    Iterator<String> keys = headers.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        String v = headers.optString(k, "");
                        if (!v.isEmpty()) conn.setRequestProperty(k, v);
                    }
                    if (streamFlag == 1) conn.setRequestProperty("Accept", "text/event-stream");

                    byte[] out = body.getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(out.length);
                    OutputStream os = conn.getOutputStream();
                    os.write(out);
                    os.flush();
                    os.close();

                    active.put(callId, conn);
                    int status = conn.getResponseCode();
                    String hdr = headersJson(conn);
                    String contentType = conn.getHeaderField("Content-Type");
                    boolean sse = contentType != null && contentType.contains("text/event-stream");
                    InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();

                    if (streamFlag == 1 && sse && status < 400) {
                        handleSse(callId, status, hdr, in);
                    } else {
                        String full = readAll(in);
                        if (streamFlag == 1 && status < 400) {
                            String snippet = null;
                            try {
                                JSONObject j = new JSONObject(full);
                                JSONArray choices = j.optJSONArray("choices");
                                if (choices != null && choices.length() > 0) {
                                    JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
                                    if (msg != null) snippet = msg.optString("content", "");
                                }
                            } catch (Exception ignored) {}
                            if (snippet != null && !snippet.isEmpty()) emitDelta(callId, snippet);
                        }
                        active.remove(callId);
                        finish(callId, status, hdr, full);
                    }
                } catch (Exception e) {
                    active.remove(callId);
                    String m = e.getMessage();
                    fail(callId, m == null ? e.getClass().getSimpleName() : m);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            });
        }

        private void handleSse(String callId, int status, String hdr, InputStream in) {
            StringBuilder content = new StringBuilder();
            StringBuilder finishReason = new StringBuilder();
            String errorBody = null;
            JSONObject usage = null;
            HashMap<Integer, JSONObject> toolCalls = new HashMap<>();
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) continue;
                    if ("[DONE]".equals(payload)) break;
                    JSONObject obj;
                    try { obj = new JSONObject(payload); } catch (Exception e) { continue; }

                    if (obj.has("error")) { errorBody = obj.toString(); break; }
                    if (obj.has("usage") && !obj.isNull("usage")) usage = obj.optJSONObject("usage");

                    JSONArray choices = obj.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject choice = choices.getJSONObject(0);
                    String fr = choice.optString("finish_reason", null);
                    if (fr != null && !fr.isEmpty()) finishReason.append(fr);
                    JSONObject delta = choice.optJSONObject("delta");
                    if (delta == null) continue;

                    String dContent = delta.isNull("content") ? null : delta.optString("content", null);
                    if (dContent != null && !dContent.isEmpty()) {
                        content.append(dContent);
                        emitDelta(callId, dContent);
                    }
                    JSONArray dTools = delta.optJSONArray("tool_calls");
                    if (dTools != null) {
                        for (int i = 0; i < dTools.length(); i++) {
                            JSONObject tc = dTools.getJSONObject(i);
                            int idx = tc.optInt("index", 0);
                            JSONObject acc = toolCalls.get(idx);
                            if (acc == null) {
                                acc = new JSONObject();
                                acc.put("id", "");
                                acc.put("name", "");
                                acc.put("arguments", new StringBuilder());
                                toolCalls.put(idx, acc);
                            }
                            if (tc.has("id") && !tc.isNull("id")) acc.put("id", tc.optString("id"));
                            JSONObject fn = tc.optJSONObject("function");
                            if (fn != null) {
                                if (fn.has("name") && !fn.isNull("name") && fn.optString("name").isEmpty() == false)
                                    acc.put("name", acc.optString("name") + fn.optString("name"));
                                String args = fn.isNull("arguments") ? null : fn.optString("arguments", null);
                                if (args != null) ((StringBuilder) acc.get("arguments")).append(args);
                            }
                        }
                    }
                }
                br.close();
            } catch (Exception e) {
                if (content.length() == 0 && toolCalls.isEmpty()) {
                    active.remove(callId);
                    fail(callId, "stream error: " + e.getMessage());
                    return;
                }
            }

            active.remove(callId);
            try {
                JSONObject message = new JSONObject();
                message.put("role", "assistant");
                if (content.length() > 0) message.put("content", content.toString());
                else message.put("content", JSONObject.NULL);
                if (!toolCalls.isEmpty()) {
                    JSONArray arr = new JSONArray();
                    for (Integer idx : new java.util.TreeMap<>(toolCalls).keySet()) {
                        JSONObject acc = toolCalls.get(idx);
                        JSONObject fn = new JSONObject();
                        fn.put("name", acc.optString("name"));
                        fn.put("arguments", ((StringBuilder) acc.get("arguments")).toString());
                        JSONObject tc = new JSONObject();
                        tc.put("id", acc.optString("id").isEmpty() ? ("call_" + idx) : acc.optString("id"));
                        tc.put("type", "function");
                        tc.put("function", fn);
                        arr.put(tc);
                    }
                    message.put("tool_calls", arr);
                }
                JSONObject choice = new JSONObject();
                choice.put("message", message);
                choice.put("finish_reason",
                        finishReason.length() > 0 ? finishReason.toString() : "stop");
                JSONObject resp = new JSONObject();
                JSONArray choicesArr = new JSONArray();
                choicesArr.put(choice);
                resp.put("choices", choicesArr);
                if (usage != null) resp.put("usage", usage);
                if (errorBody != null) resp.put("_error", errorBody);
                finish(callId, status, hdr, resp.toString());
            } catch (Exception e) {
                fail(callId, "assemble error: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void cancel(final String callId) {
            HttpURLConnection conn = active.remove(callId);
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void openUrl(final String url) {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void theme(final int dark) {
            runOnUiThread(() -> {
                int bar = Color.parseColor(dark == 1 ? "#17191C" : "#FFFFFF");
                getWindow().setStatusBarColor(bar);
                getWindow().setNavigationBarColor(bar);
                View decor = getWindow().getDecorView();
                if (dark == 1) {
                    decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                            & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                } else {
                    decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                }
            });
        }
    }
}
