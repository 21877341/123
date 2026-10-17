package dev.hyperosfix.desktop;

import android.content.Context;
import android.os.Build;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * system_server side.
 *
 * CachedAppOptimizer is what turns "a cached process" into "pages that were
 * paged out": it compacts (pages out anonymous pages) and freezes background
 * processes.  When the desktop process is wrongly classified as cached, its
 * pages are pushed out and every touch afterwards is a page fault.  That is
 * exactly the "gets worse over time" shape.
 *
 * Hooks are installed immediately, but target resolution is deferred: when
 * LSPosed loads the module, ActivityThread.systemMain() is still building the
 * "android" package and PackageManagerService is not ready yet.  Targets are
 * resolved on AMS#systemReady, or on the first compaction/freezing call.
 *
 * The module is in DRY-RUN mode unless /data/system/hyperos_desktop_fix.sys
 * exists, so the first run only reports whether the mechanism hits the
 * desktop at all.
 */
final class SystemHooks {

    private static volatile boolean sInitStarted;
    private static volatile boolean sInitialized;

    private SystemHooks() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lp) {
        L.i("system_server module loading: Android " + Build.VERSION.RELEASE
                + " / SDK " + Build.VERSION.SDK_INT + " / " + Build.DISPLAY);

        hookSystemReady(lp.classLoader);
        hookCachedAppOptimizer(lp.classLoader);
        hookAmsTrim(lp.classLoader);
    }

    /**
     * Resolve package/UID targets off the AMS thread.  PackageManager Binder
     * calls must not run while OomAdjuster holds mProcLock.
     */
    private static void ensureInitializedAsync() {
        if (sInitialized || sInitStarted) {
            return;
        }
        synchronized (SystemHooks.class) {
            if (sInitialized || sInitStarted) {
                return;
            }
            sInitStarted = true;
        }

        Thread thread = new Thread(() -> {
            try {
                Context ctx = systemContext();
                if (ctx == null) {
                    sInitStarted = false;
                    L.w("target init deferred: no system context yet");
                    return;
                }
                Targets.init(ctx);
                sInitialized = true;
                L.i("initialized; mode=" + (Targets.isEnforce()
                        ? "ENFORCE"
                        : "DRY-RUN (no " + Targets.ENFORCE_FILE + ")"));
            } catch (Throwable t) {
                sInitStarted = false;
                L.e("target init failed", t);
            }
        }, "hyperosfix-init");
        thread.setDaemon(true);
        thread.start();
    }

    private static void hookSystemReady(ClassLoader cl) {
        try {
            Class<?> ams = XposedHelpers.findClass(
                    "com.android.server.am.ActivityManagerService", cl);
            Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                    ams, "systemReady", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            ensureInitializedAsync();
                        }
                    });
            L.i("AMS#systemReady hooks: " + (unhooks == null ? 0 : unhooks.size()));
        } catch (Throwable t) {
            L.w("AMS#systemReady hook unavailable: " + t);
        }
    }

    private static Context systemContext() {
        try {
            Class<?> activityThread = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object thread = XposedHelpers.callStaticMethod(activityThread, "currentActivityThread");
            if (thread != null) {
                Object ctx = XposedHelpers.callMethod(thread, "getSystemContext");
                if (ctx instanceof Context) {
                    return (Context) ctx;
                }
            }
        } catch (Throwable t) {
            L.e("systemContext via ActivityThread", t);
        }

        // Fallback for early system_server loading: AppGlobals holds the
        // Application created by ActivityThread.systemMain().
        try {
            Class<?> appGlobals = XposedHelpers.findClass("android.app.AppGlobals", null);
            Object app = XposedHelpers.callStaticMethod(appGlobals, "getInitialApplication");
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable t) {
            L.e("systemContext via AppGlobals", t);
        }
        return null;
    }

    private static void hookCachedAppOptimizer(ClassLoader cl) {
        final Class<?> optimizer;
        try {
            optimizer = XposedHelpers.findClass("com.android.server.am.CachedAppOptimizer", cl);
        } catch (Throwable t) {
            L.e("CachedAppOptimizer not found; system side inactive", t);
            return;
        }

        StringBuilder declared = new StringBuilder();
        int installed = 0;

        for (Method m : optimizer.getDeclaredMethods()) {
            if (m.isSynthetic()
                    || Modifier.isAbstract(m.getModifiers())
                    || Modifier.isNative(m.getModifiers())) {
                continue;
            }
            declared.append(m.getName()).append(' ');

            String lower = m.getName().toLowerCase(Locale.ROOT);
            boolean compaction = lower.contains("compact");
            // Do not touch freezeProcessNoLock: the outer freezeProcess owns the
            // bookkeeping, and skipping only the inner call would desync AMS state.
            boolean freezing = lower.startsWith("freeze")
                    && !lower.contains("unfreeze")
                    && !lower.contains("nolock")
                    && !lower.contains("nofreeze");
            if (!compaction && !freezing) {
                continue;
            }

            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            ensureInitializedAsync();
                            String target = Targets.findTargetInArgs(param.args);
                            if (target == null) {
                                return;
                            }
                            String what = param.method.getName() + "(" + target + ")";
                            if (Targets.isEnforce()) {
                                param.setResult(defaultResult(
                                        ((Method) param.method).getReturnType()));
                                L.throttled("BLOCKED " + what);
                            } else {
                                L.throttled("would block " + what);
                            }
                        } catch (Throwable t) {
                            L.e("hook body " + param.method.getName(), t);
                        }
                    }
                });
                installed++;
                L.i("hooked CachedAppOptimizer#" + m.getName() + signature(m));
            } catch (Throwable t) {
                L.e("hook " + m.getName(), t);
            }
        }

        L.i("CachedAppOptimizer declared methods: " + declared);
        L.i("CachedAppOptimizer hooks installed: " + installed);
        if (installed == 0) {
            L.w("no compaction/freezing methods matched; class layout may differ on this ROM");
        }
    }

    private static void hookAmsTrim(ClassLoader cl) {
        final Class<?> ams;
        try {
            ams = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", cl);
        } catch (Throwable t) {
            L.e("ActivityManagerService not found", t);
            return;
        }

        try {
            Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                    ams, "setProcessMemoryTrimLevel", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                ensureInitializedAsync();
                                if (param.args == null
                                        || param.args.length == 0
                                        || !(param.args[0] instanceof String)) {
                                    return;
                                }
                                String processName = (String) param.args[0];
                                if (!Targets.isTargetProcess(processName)) {
                                    return;
                                }
                                if (Targets.isEnforce()) {
                                    param.setResult(Boolean.FALSE);
                                    L.throttled("BLOCKED setProcessMemoryTrimLevel(" + processName + ")");
                                } else {
                                    L.throttled("would block setProcessMemoryTrimLevel(" + processName + ")");
                                }
                            } catch (Throwable t) {
                                L.e("ams trim body", t);
                            }
                        }
                    });
            L.i("AMS#setProcessMemoryTrimLevel hooks: "
                    + (unhooks == null ? 0 : unhooks.size()));
        } catch (Throwable t) {
            L.e("AMS#setProcessMemoryTrimLevel hook", t);
        }
    }

    private static Object defaultResult(Class<?> type) {
        if (type == void.class || type == Void.class) {
            return null;
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class || type == Integer.class) {
            return 0;
        }
        if (type == long.class || type == Long.class) {
            return 0L;
        }
        return null;
    }

    private static String signature(Method m) {
        StringBuilder sb = new StringBuilder("(");
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(params[i].getSimpleName());
        }
        return sb.append(')').toString();
    }
}
