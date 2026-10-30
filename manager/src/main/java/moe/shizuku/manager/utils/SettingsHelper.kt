package moe.shizuku.manager.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import android.os.PowerManager
import android.provider.Settings
import moe.shizuku.manager.utils.SettingsPage

object SettingsHelper {

    fun isDeveloperOptionsEnabled(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0
        ) != 0
    }

    fun launchOrHighlightWirelessDebugging(context: Context) {
        // Developer options are unreachable while they are disabled, and some ROMs redirect the
        // intent to an unrelated screen. Send the user to device info so they can enable them.
        if (!isDeveloperOptionsEnabled(context)) {
            SettingsPage.DeviceInfo.launch(context)
            return
        }

        val adbEnabled = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
        if (adbEnabled > 0) {
            SettingsPage.Developer.WirelessDebugging.launch(context)
        } else SettingsPage.Developer.HighlightWirelessDebugging.launch(context)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(context: Context, launcher: ActivityResultLauncher<Intent>? = null) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            setData(Uri.parse("package:" + context.packageName))
        }
        if (launcher != null) {
            launcher.launch(intent)
        } else {
            context.startActivity(intent)
        }
    }

}