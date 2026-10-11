package il.m5s.launcher

import android.app.admin.DeviceAdminReceiver

/** Registered as a device admin only to protect the launcher from being uninstalled; it requests no policies. */
class AdminReceiver : DeviceAdminReceiver()
