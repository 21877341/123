package dev.hyperosfix.desktop;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Entry point declared in assets/xposed_init.
 *
 * Two very different hook sets:
 *   - package "android" (system_server): stop the framework from compacting /
 *     freezing / trimming the desktop process.
 *   - anything else (the scoped desktop app): cap the trim levels.
 */
public final class HookEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (lpparam == null || lpparam.packageName == null) {
                return;
            }
            if ("android".equals(lpparam.packageName)) {
                SystemHooks.install(lpparam);
            } else {
                AppHooks.install(lpparam);
            }
        } catch (Throwable t) {
            L.e("handleLoadPackage failed for "
                    + (lpparam == null ? "null" : lpparam.packageName), t);
        }
    }
}
