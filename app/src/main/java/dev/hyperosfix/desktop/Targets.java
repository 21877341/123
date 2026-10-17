package dev.hyperosfix.desktop;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XposedHelpers;

/**
 * Identifies the desktop process(es) the module is allowed to touch.
 *
 * Targets are the union of:
 *   1. the package currently handling the HOME intent,
 *   2. "com.miui.home" (legacy Xiaomi launcher; ignored when not installed),
 *   3. every package listed in /data/system/hyperos_desktop_targets.txt.
 *
 * Matching is per UID as well, so helper processes such as "pkg:render"
 * or "pkg:remote" are covered by one entry.
 */
final class Targets {

    static final String TARGETS_FILE = "/data/system/hyperos_desktop_targets.txt";
    static final String ENFORCE_FILE = "/data/system/hyperos_desktop_fix.sys";

    private static volatile Set<String> sPackages = Collections.emptySet();
    private static volatile Set<Integer> sUids = Collections.emptySet();
    private static volatile String sHome = "?";

    private Targets() {
    }

    static synchronized void init(Context ctx) {
        if (ctx == null) {
            L.w("Targets.init: null context");
            return;
        }

        LinkedHashSet<String> packages = new LinkedHashSet<>();
        String home = resolveHome(ctx);
        sHome = home;
        if (home != null) {
            packages.add(home);
        }
        packages.add("com.miui.home");
        for (String line : readLines(TARGETS_FILE)) {
            String pkg = line.trim();
            if (!pkg.isEmpty() && !pkg.startsWith("#")) {
                packages.add(pkg);
            }
        }

        LinkedHashSet<Integer> uids = new LinkedHashSet<>();
        List<String> notInstalled = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : packages) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                uids.add(ai.uid);
            } catch (Throwable t) {
                notInstalled.add(pkg);
            }
        }

        sPackages = Collections.unmodifiableSet(packages);
        sUids = Collections.unmodifiableSet(uids);
        File targetsFile = new File(TARGETS_FILE);
        File enforceFile = new File(ENFORCE_FILE);
        L.i("targets=" + packages + " uids=" + uids + " home=" + home
                + " notInstalled=" + notInstalled
                + " targetsFile(exists=" + targetsFile.exists()
                + ",readable=" + targetsFile.canRead() + ")"
                + " enforceFile(exists=" + enforceFile.exists() + ")");
    }

    private static String resolveHome(Context ctx) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = ctx.getPackageManager()
                    .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) {
                return ri.activityInfo.packageName;
            }
        } catch (Throwable t) {
            L.e("resolveHome", t);
        }
        return null;
    }

    private static List<String> readLines(String path) {
        List<String> out = new ArrayList<>();
        File file = new File(path);
        if (!file.canRead()) {
            return out;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.add(line);
            }
        } catch (Throwable t) {
            L.w("cannot read " + path + ": " + t);
        }
        return out;
    }

    static boolean isEnforce() {
        try {
            return new File(ENFORCE_FILE).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    static String home() {
        return sHome;
    }

    static boolean isTargetProcess(String name) {
        if (name == null) {
            return false;
        }
        for (String pkg : sPackages) {
            if (name.equals(pkg) || name.startsWith(pkg + ":")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scans hook arguments for a target ProcessRecord / process name / target UID.
     *
     * @return a human readable identifier when the call belongs to a target,
     *         otherwise null.
     */
    static String findTargetInArgs(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            String cls = arg.getClass().getName();
            if (cls.contains("ProcessRecord")) {
                String name = null;
                try {
                    Object v = XposedHelpers.getObjectField(arg, "processName");
                    if (v instanceof String) {
                        name = (String) v;
                    }
                } catch (Throwable ignored) {
                }
                if (name != null && isTargetProcess(name)) {
                    return name;
                }

                Object uid = null;
                try {
                    uid = XposedHelpers.getObjectField(arg, "uid");
                } catch (Throwable ignored) {
                }
                if (uid instanceof Integer && sUids.contains(uid)) {
                    return name != null ? name : ("uid:" + uid);
                }

                if (name == null) {
                    try {
                        Object info = XposedHelpers.getObjectField(arg, "info");
                        if (info != null) {
                            Object pkg = XposedHelpers.getObjectField(info, "packageName");
                            if (pkg instanceof String && isTargetProcess((String) pkg)) {
                                return (String) pkg;
                            }
                            Object infoUid = XposedHelpers.getObjectField(info, "uid");
                            if (infoUid instanceof Integer && sUids.contains(infoUid)) {
                                return "uid:" + infoUid;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } else if (arg instanceof String) {
                String s = (String) arg;
                if (isTargetProcess(s)) {
                    return s;
                }
            }
        }
        return null;
    }
}
