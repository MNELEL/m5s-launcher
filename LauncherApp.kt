package il.m5s.launcher

class LauncherApp : android.app.Application() {
    override fun onCreate() { super.onCreate(); CrashLog.install(this); Speech.init(this); Root.restoreIfNeeded(this) }
}
