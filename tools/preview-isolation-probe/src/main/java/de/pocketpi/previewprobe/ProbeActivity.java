package de.pocketpi.previewprobe;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.os.Process;
import android.util.Log;
import android.widget.TextView;

public final class ProbeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView output;
    private void report(String label, String text) {
        String line = label + " " + text;
        Log.i("PreviewProbe", line);
        output.append(line + "\n");
    }
    private void run(String label, Class<?> service) {
        Messenger callback = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
            if (msg.what == 2) report(label, msg.getData().getString("result"));
            return true;
        }));
        ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                report(label, "connected");
                try { Message m = Message.obtain(null, 1); m.replyTo = callback; new Messenger(binder).send(m); }
                catch (RemoteException e) { report(label, "send error=" + ProbeService.describe(e)); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { report(label, "disconnected"); }
            @Override public void onBindingDied(ComponentName name) { report(label, "binding died"); }
            @Override public void onNullBinding(ComponentName name) { report(label, "null binding"); }
        };
        try { report(label, "binding=" + bindService(new Intent(this, service), conn, BIND_AUTO_CREATE)); }
        catch (Throwable t) { report(label, "bind error=" + ProbeService.describe(t)); }
        handler.postDelayed(() -> report(label, "activity watchdog 15s (check FINAL above)"), 15000);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        output = new TextView(this); setContentView(output);
        report("activity", "uid=" + Process.myUid() + " sdk=" + Build.VERSION.SDK_INT);
        run("CONTROL", ControlService.class);
        run("ISOLATED", IsolatedService.class);
    }
}
