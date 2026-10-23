#!/bin/bash
# ユーザー補助をオンにする方法の確認（短時間で終わる診断）
set -u
OUT=out
mkdir -p $OUT
APP=app/build/outputs/apk/debug/app-debug.apk
PKG=com.example.autoclicker
log() { echo "[$(date +%T)] $*" | tee -a $OUT/diag.txt; }
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
sleep 5
log "install"
adb install -r -g $APP 2>&1 | tail -1 | tee -a $OUT/diag.txt
log "get before"; adb shell settings get secure enabled_accessibility_services 2>&1 | tee -a $OUT/diag.txt
log "put 1"; adb shell settings put secure enabled_accessibility_services $PKG/$PKG.ClickerService 2>&1 | tee -a $OUT/diag.txt; echo "rc=$?" | tee -a $OUT/diag.txt
log "get 1"; adb shell settings get secure enabled_accessibility_services 2>&1 | tee -a $OUT/diag.txt
adb shell settings put secure accessibility_enabled 1 2>&1 | tee -a $OUT/diag.txt
sleep 3
log "get 1b"; adb shell settings get secure enabled_accessibility_services 2>&1 | tee -a $OUT/diag.txt
adb shell dumpsys accessibility | grep -E "Enabled services|Bound services|Crashed" | tee -a $OUT/diag.txt
log "put 2 (quoted)"; adb shell "settings put secure enabled_accessibility_services com.example.autoclicker/.ClickerService" 2>&1 | tee -a $OUT/diag.txt
sleep 3
log "get 2"; adb shell settings get secure enabled_accessibility_services 2>&1 | tee -a $OUT/diag.txt
adb shell dumpsys accessibility | grep -E "Enabled services|Bound services|Crashed" | tee -a $OUT/diag.txt
log "content insert"; adb shell content insert --uri content://settings/secure --bind name:s:enabled_accessibility_services --bind value:s:com.example.autoclicker/com.example.autoclicker.ClickerService 2>&1 | tee -a $OUT/diag.txt
sleep 3
log "get 3"; adb shell settings get secure enabled_accessibility_services 2>&1 | tee -a $OUT/diag.txt
adb shell dumpsys accessibility | grep -E "Enabled services|Bound services|Crashed" | tee -a $OUT/diag.txt
adb shell dumpsys activity service $PKG/.ClickerService 2>&1 | tail -5 | tee -a $OUT/diag.txt
adb logcat -d -v threadtime | grep -i -E "accessib|enabled_accessibility|SettingsProvider|autoclicker" | grep -v -E "ApkAssets|AppsFilter|PrefCtrlList" | tail -300 > $OUT/diag_logcat.txt
adb shell getprop ro.build.fingerprint | tee -a $OUT/diag.txt
exit 0
