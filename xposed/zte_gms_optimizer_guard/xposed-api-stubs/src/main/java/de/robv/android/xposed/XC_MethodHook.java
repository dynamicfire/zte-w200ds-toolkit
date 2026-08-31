package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        public Object getResult() {
            throw new UnsupportedOperationException("compile-only stub");
        }

        public void setResult(Object result) {
            throw new UnsupportedOperationException("compile-only stub");
        }

        public Throwable getThrowable() {
            throw new UnsupportedOperationException("compile-only stub");
        }

        public void setThrowable(Throwable throwable) {
            throw new UnsupportedOperationException("compile-only stub");
        }
    }

    public final class Unhook {
        public void unhook() {
            throw new UnsupportedOperationException("compile-only stub");
        }
    }
}
