package com.krypt.app.ui.main

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * OEM-specific Settings deep-links so users can whitelist Krypt from
 * aggressive battery / auto-start policies (Xiaomi, Huawei, Oppo, Vivo,
 * Samsung). If the OEM changes the Settings ComponentName between ROM
 * versions, [tryLaunch] degrades gracefully.
 */
object OemBatteryLinks {

    data class OemGuide(
        val label: String,
        val intent: Intent,
        val helpText: String,
    )

    fun guideForCurrentOem(): OemGuide? = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi" -> OemGuide(
            label = "Xiaomi Autostart",
            intent = Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                )
            },
            helpText = "On MIUI, enable Krypt under Autostart so it survives reboots.",
        )
        "huawei", "honor" -> OemGuide(
            label = "Huawei Startup Manager",
            intent = Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                )
            },
            helpText = "On EMUI, enable Krypt under Startup Manager.",
        )
        "oppo" -> OemGuide(
            label = "Oppo Startup Manager",
            intent = Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                )
            },
            helpText = "On ColorOS, enable Krypt under Startup Manager.",
        )
        "vivo" -> OemGuide(
            label = "Vivo Background Apps",
            intent = Intent().apply {
                component = ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                )
            },
            helpText = "On Funtouch OS, enable Krypt under Background App Launch.",
        )
        "samsung" -> OemGuide(
            label = "Samsung Device Care",
            intent = Intent().apply {
                component = ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                )
            },
            helpText = "On One UI, whitelist Krypt in Device Care → Battery.",
        )
        else -> null
    }

    fun tryLaunch(context: Context, guide: OemGuide): Boolean = try {
        context.startActivity(guide.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
