# ICCOA-tool (debug)

Android 10 car head-unit window observer + launcher.

Goals of this debug build:
1. Verify that pm grant DUMP works and survives cold boot.
2. Verify that dumpsys window works inside the app and measure cost.
3. Verify that launching com.ucarhu.demo still works.

## Usage

1. Install APK.
2. Grant DUMP:
       adb shell pm grant com.iccoa.tool android.permission.DUMP
3. Open app, check "1 Permission".
4. Tap "Start", then trigger 360, then let it exit.
5. Check "3 Counters" and "5 Event Log".

## Build

GitHub Actions -> Build Android APK -> download ICCOA-tool-debug.apk.