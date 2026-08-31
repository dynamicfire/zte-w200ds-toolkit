package de.robv.android.xposed;

import java.lang.reflect.Member;

public final class XposedBridge {
    private XposedBridge() {
    }

    public static Object invokeOriginalMethod(
            Member method, Object thisObject, Object[] args) throws Throwable {
        throw new UnsupportedOperationException("compile-only stub");
    }
}
