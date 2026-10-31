package de.pocketpi.previewprobe;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import android.os.Process;
import android.webkit.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public abstract class ProbeService extends Service {
    private static final String URL = "https://probe.invalid/index.html";
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView view;
    private Messenger reply;
    private boolean done;
    private final Messenger incoming = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if (msg.what != 1 || msg.replyTo == null || reply != null) return true;
        reply = msg.replyTo;
        emit("uid=" + Process.myUid() + " internet=" + (checkPermission("android.permission.INTERNET", Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED) + " bootstrap=" + ProbeApplication.bootstrapError);
        if (ProbeApplication.bootstrapError != null) { finish("bootstrap failed"); return true; }
        main.postDelayed(() -> finish("timeout"), 10000);
        try {
            view = new WebView(this);
            emit("created");
            view.getSettings().setBlockNetworkLoads(true);
            view.getSettings().setJavaScriptEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                    if (!URL.equals(req.getUrl().toString())) return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
                    byte[] html = "<!doctype html><html><body>probe-ok</body></html>".getBytes(StandardCharsets.UTF_8);
                    return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(html));
                }
                @Override public void onPageFinished(WebView v, String url) {
                    if (!URL.equals(url)) return;
                    try { v.evaluateJavascript("document.body && document.body.textContent", value -> finish("loaded=" + value)); }
                    catch (Throwable t) { finish("evaluate error=" + describe(t)); }
                }
                @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                    if (r.isForMainFrame()) finish("load error=" + e.getErrorCode() + ":" + e.getDescription());
                }
                @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                    finish("renderer gone crashed=" + d.didCrash()); return true;
                }
            });
            view.loadUrl(URL);
        } catch (Throwable t) { finish("create/load error=" + describe(t)); }
        return true;
    }));
    static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getName() + ": " + (message == null ? "" : message.substring(0, Math.min(message.length(), 300)).replace('\n', ' '));
    }
    private void emit(String text) {
        if (reply == null) return;
        Message m = Message.obtain(null, 2);
        Bundle data = new Bundle(); data.putString("result", text); m.setData(data);
        try { reply.send(m); } catch (RemoteException ignored) { }
    }
    private void finish(String text) { if (!done) { done = true; emit("FINAL " + text); if (view != null) { view.destroy(); view = null; } } }
    @Override public IBinder onBind(Intent intent) { return incoming.getBinder(); }
}
