# Clash Meta VPN Watchdog for ZTE W200DS

This APatch module restores Clash Meta only after an unexpected service death.
On the tested firmware it is the last fallback in a four-layer solution; the
normal recents-swipe path is prevented from killing the VPN in the first place.

## Scope

- Watches only `com.github.metacubex.clash.meta` for Android user 0.
- Uses Clash Meta's own zero-byte `files/service_running.lock` as the desired-state signal.
- Restarts through Clash Meta's exported `ExternalControlActivity` and `START_CLASH` action.
- Does not modify the Clash APK, profiles, subscriptions, databases, preferences, system properties, or SELinux policy.
- Treats PackageManager `stopped=true` as an explicit Settings-level Force stop and does not override it.
- Runs the detached watcher explicitly under APatch's bundled BusyBox standalone shell.

The ZTE recents lock and an exact `used_module=6` package entry in the vendor
`special_class_list` are prerequisites. The lock prevents the first Force-stop
cleanup chain. The vendor entry makes the launcher's second cleanup chain only
remove the task instead of sending SIGKILL to both Clash processes. Android's
Always-on VPN setting for Clash should also be enabled, with lockdown ("Block
connections without VPN") left off. The watchdog then remains available for
unexpected deaths outside those protected paths. See
`docs/CLASH-META-WATCHDOG.md` in the repository for exact installation,
verification, and rollback steps.

## Expected behavior

With the vendor entry active, swiping the locked recents card should remove only
the card: both Clash PIDs and the foreground `TunService` should remain unchanged,
so no reconnect is needed. When the VPN is active, the watchdog checks the
background process every five seconds and validates `TunService` every 30 seconds.
After another kind of unexpected process death it waits three seconds to avoid
racing Clash's normal stop path, then invokes the exported start entry point. A
normal stop in Clash removes the marker, so it remains off. A Settings-level
Force stop sets the package stopped flag and is also respected.

At most three starts are attempted within ten minutes. Continued failure causes
a 15-minute cooldown. On the first use after installation, the APatch Action
button starts the watcher immediately without a reboot. After that it toggles an
immediate pause without stopping a currently running VPN.

Fallback recovery cannot make an actual process death seamless. Existing
connections can reset during the several-second recovery window. The protected
recents path itself should be uninterrupted because it no longer kills the
processes; automatic recovery is reserved for other unexpected failures.

## Module layout

```text
clash_meta_watchdog.zip
├── module.prop
├── skip_mount
├── customize.sh
├── boot-completed.sh
├── watchdog.sh
├── action.sh
└── uninstall.sh
```

## Verification plan

1. Confirm the recents card is locked, the exact vendor row exists, and the device
   has rebooted since that row was inserted.
2. Confirm the module process, mode `0700` state directory, and capped event log.
3. Start Clash normally. Confirm its desired-state marker, main/background PIDs,
   foreground `TunService`, and Android VPN ownership.
4. Swipe the Clash card away once. Confirm the task disappears while both PIDs
   remain exactly unchanged, `TunService` stays foreground, and traffic works.
   No watchdog recovery event should be needed.
5. If separately testing fallback recovery after a controlled unexpected process
   death, re-resolve the exact background PID first. A new PID should appear with
   one `restart_attempt`/`restart_succeeded` pair and no repeating activity launch.
6. Stop Clash with its own Stop button. Wait at least 30 seconds and confirm it
   remains stopped.
7. Use Android App info to Force stop Clash and confirm the watchdog records
   `explicit_force_stop` without restarting it.
8. Pause via APatch's Action button, test an unexpected death, then resume and
   start Clash normally.
9. Reboot once with Clash active and once with Clash inactive. It should preserve
   those two desired states.

For a wired-ADB-only immediate start instead of the Action button, resolve whether
APatch placed the module under `modules` or `modules_update`, then invoke that
exact `boot-completed.sh` with `/data/adb/ap/bin/busybox sh -o standalone`. Do not
guess the module path and do not use wireless ADB.

## Removal

Use APatch Manager to remove the module and reboot. `uninstall.sh` signals only
the watchdog process and deletes only `/data/adb/clash_meta_watchdog`. It does not
stop Clash, alter its profiles, undo the ZTE recents lock, delete the vendor
whitelist row, or disable Android Always-on VPN. Roll those settings back
separately using the exact row ID recorded during installation. Never delete a
matching row that already existed before this solution was configured.
