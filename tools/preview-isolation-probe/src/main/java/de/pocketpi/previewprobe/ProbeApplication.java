package de.pocketpi.previewprobe;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.webkit.WebView;

public final class ProbeApplication extends Application {
    static String bootstrapError;
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                String name = Application.getProcessName();
                if (name.endsWith(":control")) WebView.setDataDirectorySuffix("control");
                if (name.endsWith(":isolated")) WebView.setDataDirectorySuffix("isolated");
            } catch (Throwable t) { bootstrapError = ProbeService.describe(t); }
        }
    }
}
