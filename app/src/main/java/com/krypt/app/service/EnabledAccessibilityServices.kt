package com.krypt.app.service

/**
 * Reads `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`: the services the
 * user has switched on, whether or not Android has bound them right now.
 * Entries are `package/class` separated by `:`, and a class starting with
 * `.` is relative to its package, as `ComponentName.unflattenFromString`
 * reads them.
 */
object EnabledAccessibilityServices {

    /** Whether [setting] lists the service [className] of [packageName]. */
    fun includes(setting: String?, packageName: String, className: String): Boolean =
        setting.orEmpty().split(':').any { entry ->
            val slash = entry.indexOf('/')
            if (slash <= 0 || slash == entry.lastIndex) return@any false
            val pkg = entry.substring(0, slash)
            val cls = entry.substring(slash + 1).let { if (it.startsWith('.')) pkg + it else it }
            pkg == packageName && cls == className
        }
}
