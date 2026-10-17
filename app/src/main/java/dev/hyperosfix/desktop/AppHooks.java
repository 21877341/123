package dev.hyperosfix.desktop;

import android.app.Application;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Desktop-app side.
 *
 * The framework tells a backgrounded process how much memory it should give
 * back through ComponentCallbacks2.onTrimMemory().  A launcher that listens to
 * TRIM_MEMORY_UI_HIDDEN / BACKGROUND / COMPLETE typically drops glyph atlases,
 * icon caches and possibly Rust-side caches; the next visit then rebuilds them
 * and faults pages back in.  We cap the level at UI_HIDDEN so the desktop is
 * never asked to throw away its working set.
 *
 * This does not touch the Rust renderer itself; it only changes the pressure
 * signal the framework delivers to the process.
 */
final class AppHooks {

    /** ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN */
    private static final int TRIM_MEMORY_UI_HIDDEN = 20;

    private AppHooks() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || lp.classLoader == null) {
            return;
        }
        if ("dev.hyperosfix.desktop".equals(lp.packageName)) {
            return;
        }

        final String process = lp.processName;
        L.i("app process: " + process);

        XC_MethodHook clamp = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args == null || param.args.length == 0) {
                        return;
                    }
                    if (!(param.args[0] instanceof Integer)) {
                        return;
                    }
                    int level = (Integer) param.args[0];
                    if (level > TRIM_MEMORY_UI_HIDDEN) {
                        param.args[0] = TRIM_MEMORY_UI_HIDDEN;
                        L.throttled("clamped trim " + level + " -> "
                                + TRIM_MEMORY_UI_HIDDEN + " in " + process);
                    } else if (level == TRIM_MEMORY_UI_HIDDEN) {
                        L.throttled("trim UI_HIDDEN in " + process);
                    }
                } catch (Throwable t) {
                    L.e("trim clamp", t);
                }
            }
        };

        // Primary: the Binder entry point AMS uses to deliver trim levels.
        // Clamping here works even if the app overrides onTrimMemory() without
        // calling super.
        try {
            Class<?> appThread = XposedHelpers.findClass(
                    "android.app.ActivityThread$ApplicationThread", lp.classLoader);
            Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                    appThread, "scheduleTrimMemory", clamp);
            L.i("ApplicationThread#scheduleTrimMemory hooks: "
                    + (unhooks == null ? 0 : unhooks.size()));
        } catch (Throwable t) {
            L.w("ApplicationThread#scheduleTrimMemory unavailable: " + t);
        }

        try {
            Class<?> activityThread = XposedHelpers.findClass(
                    "android.app.ActivityThread", lp.classLoader);
            Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                    activityThread, "handleTrimMemory", clamp);
            L.i("ActivityThread#handleTrimMemory hooks: "
                    + (unhooks == null ? 0 : unhooks.size()));
        } catch (Throwable t) {
            L.w("ActivityThread#handleTrimMemory unavailable: " + t);
        }

        try {
            Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                    Application.class, "onTrimMemory", clamp);
            L.i("Application#onTrimMemory hooks: "
                    + (unhooks == null ? 0 : unhooks.size()));
        } catch (Throwable t) {
            L.w("Application#onTrimMemory unavailable: " + t);
        }
    }
}
