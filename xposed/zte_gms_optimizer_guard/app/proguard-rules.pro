# assets/xposed_init is not visible to R8. Preserve the legacy Xposed entrypoint and hook methods.
-keep class io.github.dynamicfire.zte.gmsoptimizerguard.GmsOptimizerGuardHook { *; }

# The module intentionally references Xposed classes supplied by Vector at runtime.
-dontwarn de.robv.android.xposed.**
