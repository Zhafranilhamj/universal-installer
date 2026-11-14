// AIDL spoken across the libsu RootService binder. The service runs as UID 0; the app
// process holds the proxy. Both sides see the same Stub generated from this file.
package app.pwhs.universalinstaller.privileged;

import android.content.ComponentName;

interface IPrivilegedService {
    /**
     * Make `component` the preferred handler for APK install intents.
     * lock=true → clear competing preferred activities and install ours (also persistent in root mode).
     * lock=false → clear only our own preferred activities, letting Android's chooser re-engage.
     */
    void setDefaultInstaller(in ComponentName component, boolean lock);

    /**
     * Re-enable the stock system uninstaller components that earlier releases may have disabled
     * when the "set as default uninstaller" switch was used (up to 1.23.3). Best effort and
     * idempotent: safe to call at any time, including when nothing was ever disabled.
     */
    void restoreStockUninstaller();
}
