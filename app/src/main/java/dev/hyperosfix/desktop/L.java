package dev.hyperosfix.desktop;

import android.os.SystemClock;
import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/**
 * Logs to both logcat and the LSPosed log, with a global throttle so a
 * per-frame hook can never flood the log buffer.
 */
final class L {

    private static final String TAG = "HyperOSDesktopFix";
    private static final long THROTTLE_MS = 5_000L;

    private static long sLastThrottled;

    private L() {
    }

    static void i(String msg) {
        Log.i(TAG, msg);
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
        }
    }

    static void w(String msg) {
        Log.w(TAG, msg);
        try {
            XposedBridge.log(TAG + " W: " + msg);
        } catch (Throwable ignored) {
        }
    }

    static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        try {
            XposedBridge.log(TAG + " E: " + msg
                    + (t == null ? "" : "\n" + Log.getStackTraceString(t)));
        } catch (Throwable ignored) {
        }
    }

    static void throttled(String msg) {
        long now = SystemClock.elapsedRealtime();
        if (now - sLastThrottled < THROTTLE_MS) {
            return;
        }
        sLastThrottled = now;
        i(msg);
    }
}
