# R8 keep rules for the phone app's release and benchmark builds.
#
# Most of what R8 must not remove or rename is covered elsewhere:
# - AAPT keeps every class the merged manifest names: the activities (MainActivity also as the
#   target of the InternalLaunch alias), ShareActivity, LiveRunService, RemoteMessagingService,
#   the receivers, the FileProvider and, in the benchmark build, the fixture activities.
# - The libraries ship their own rules: kotlinx-serialization keeps the generated serializers of
#   @Serializable classes, WorkManager keeps worker names and constructors, Firebase, Glance,
#   Play services (the watch data layer), OkHttp and CameraX keep what they look up reflectively.
# This file adds what neither sees and repeats the rules a crash would only show at runtime, so
# they still hold if a library changes its own.

# Readable stack traces in Play's crash reports: release.yml uploads mapping.txt for each build.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Components the system starts by class name. The manifest already keeps them; listed here so a
# moved component or a manifest edit cannot silently drop a notification action, the widget, a
# share, a live run or a push.
-keep class de.joinnoah.pi.remote.RemoteApplication { <init>(); }
-keep class de.joinnoah.pi.remote.MainActivity { <init>(); }
-keep class de.joinnoah.pi.remote.ShareActivity { <init>(); }
-keep class de.joinnoah.pi.remote.WatchPairingActivity { <init>(); }
-keep class de.joinnoah.pi.remote.LiveRunService { <init>(); }
-keep class de.joinnoah.pi.remote.RemoteMessagingService { <init>(); }
-keep class de.joinnoah.pi.remote.NotificationActionReceiver { <init>(); }
-keep class de.joinnoah.pi.remote.PackageReplacedReceiver { <init>(); }
-keep class de.joinnoah.pi.remote.PocketPiWidgetReceiver { <init>(); }

# Glance stores the GlanceAppWidget's class name per widget in its DataStore, and updateAll() and
# getGlanceIds() look the widgets up by that name. Merged or renamed, PocketPiWidget would get a
# new name in each release, and the redraw PackageReplacedReceiver asks for after an update would
# find no widget.
-keep class de.joinnoah.pi.remote.PocketPiWidget { <init>(); }

# WorkManager stores the worker's class name in its database and creates it reflectively, so a
# job enqueued by one version must find the same name in the next.
-keep class de.joinnoah.pi.remote.** extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# rememberNavBackStack saves each RemoteNavKey by class name and reads it back with
# Class.forName and serializer(KClass); the kotlinx-serialization rules keep the serializers.
-keepnames class * implements de.joinnoah.pi.remote.RemoteNavKey

# Diagnostics records a failure by its exception's simple name; keep those names readable.
-keepnames class * extends java.lang.Throwable

# ArtifactSandbox's WebView has no JavaScript bridge today. Should one be added, R8 must keep the
# methods the page calls by name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
