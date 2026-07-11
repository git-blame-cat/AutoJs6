package org.autojs.autojs.leakcanary

import android.app.Application
import android.util.Log
import leakcanary.LeakCanary
import org.autojs.autojs6.R

object LeakCanarySetup {

    fun setup(application: Application) {
        val isEnabled = application.resources.getBoolean(R.bool.leak_canary_enabled) &&
            application.resources.getBoolean(R.bool.leak_canary_watcher_auto_install)
        if (isEnabled) {
            LeakCanary.config = LeakCanary.config.copy(dumpHeap = true)
            LeakCanary.showLeakDisplayActivityLauncherIcon(true)
        }
        Log.d(
            LeakCanarySetup::class.java.simpleName,
            "LeakCanary has been set up as ${if (isEnabled) "enabled" else "disabled"}",
        )
    }

}
