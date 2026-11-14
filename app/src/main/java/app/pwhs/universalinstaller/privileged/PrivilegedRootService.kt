package app.pwhs.universalinstaller.privileged

import android.content.ComponentName
import android.content.Intent
import android.content.pm.IPackageManager
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ServiceManager
import app.pwhs.universalinstaller.util.DefaultInstallerLogic
import com.topjohnwu.superuser.ipc.RootService

/**
 * libsu-spawned process running as UID 0. We hand back a Binder implementing
 * [IPrivilegedService]. Persistent preferred activities require system UID 1000,
 * not root UID 0, so each role operation temporarily changes effective UID.
 */
class PrivilegedRootService : RootService() {

    override fun onBind(intent: Intent): IBinder {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/content/pm/IPackageManager",
                "Landroid/os/ServiceManager",
                "Landroid/content/pm/ParceledListSlice",
                "Landroid/content/pm/BaseParceledListSlice"
            )
        }
        return ServiceBinder() as IBinder
    }

    private class ServiceBinder : IPrivilegedService.Stub() {
        private inline fun asSystem(block: () -> Unit) {
            val uid = android.system.Os.geteuid()
            try {
                if (uid == 0) android.system.Os.seteuid(1000)
                block()
            } finally {
                if (uid == 0) android.system.Os.seteuid(uid)
            }
        }

        override fun setDefaultInstaller(component: ComponentName, lock: Boolean) {
            // Resolve in the isolated privileged process; role calls use system euid below.
            val pmBinder = ServiceManager.getService("package")
                ?: error("system_service 'package' returned null in root process")
            val iPm = IPackageManager.Stub.asInterface(pmBinder)
            asSystem { DefaultInstallerLogic.setDefaultInstaller(
                iPackageManager = iPm,
                component = component,
                lock = lock,
                hasSystemLevelPermission = true,
            ) }
        }

        override fun restoreStockUninstaller() {
            val pmBinder = ServiceManager.getService("package")
                ?: error("system_service 'package' returned null in root process")
            val iPm = IPackageManager.Stub.asInterface(pmBinder)
            asSystem {
                for (pkg in STOCK_UNINSTALLER_PACKAGES) {
                    for (cls in stockUninstallerClassCandidates(pkg)) {
                        resetComponentEnabledSetting(iPm, ComponentName(pkg, cls), userId = 0)
                    }
                }
            }
        }

        /**
         * Best effort: reset a component's enabled state to DEFAULT. Tries the modern 5-argument
         * `setComponentEnabledSetting` first and falls back to the legacy 4-argument overload.
         * Failures on individual components are expected (most stock components were never
         * disabled) and are ignored.
         */
        private fun resetComponentEnabledSetting(iPm: IPackageManager, component: ComponentName, userId: Int) {
            val newState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            val flags = PackageManager.DONT_KILL_APP
            val ok = runCatching {
                val m = iPm.javaClass.getMethod(
                    "setComponentEnabledSetting",
                    ComponentName::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                )
                m.invoke(iPm, component, newState, flags, userId, null as String?)
            }.isSuccess || runCatching {
                val m = iPm.javaClass.getMethod(
                    "setComponentEnabledSetting",
                    ComponentName::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                )
                @Suppress("DEPRECATION")
                m.invoke(iPm, component, newState, flags, userId)
            }.isSuccess
            if (!ok) {
                android.util.Log.w("PrivilegedRootService", "Could not reset $component to DEFAULT")
            }
        }

        private fun stockUninstallerClassCandidates(pkg: String): List<String> = buildList {
            add("$pkg.UninstallerActivity")
            add("com.android.packageinstaller.UninstallerActivity")
        }.distinct()
    }

    private companion object {
        private val STOCK_UNINSTALLER_PACKAGES = listOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
        )
    }
}
