#!/bin/bash
# エミュレーターでの動作確認。結果は out/ に入る（失敗しても最後まで進める）
set -u
OUT=out
mkdir -p $OUT
APP=app/build/outputs/apk/debug/app-debug.apk
TGA=testgame/build/outputs/apk/debug/testgame-debug.apk
PKG=com.example.autoclicker
TGP=com.example.testgame
SVC=$PKG/.ClickerService

log() { echo "[$(date +%T)] $*" | tee -a $OUT/steps.txt; }
dsys() { adb shell dumpsys activity service $SVC "$@" 2>&1 | grep -E "state=|stats" | tee -a $OUT/steps.txt; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; }
mark() { adb shell log -t TG "MARK $1"; }

# 画面共有の確認が出ていたら「開始」を押す
press_start() {
  for k in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml $OUT/ui_$k.xml >/dev/null 2>&1 || true
    xy=$(python3 ci/ui_find.py $OUT/ui_$k.xml 2>/dev/null)
    if [ -n "$xy" ]; then
      log "dialog button at $xy"
      shot dialog_$k
      adb shell input tap $xy
      sleep 2
    else
      break
    fi
  done
}

log "waiting for boot"
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
adb shell settings put global hide_error_dialogs 1
sleep 20
adb shell getprop ro.build.version.release | tee $OUT/android_version.txt
adb shell getprop ro.build.version.sdk | tee -a $OUT/android_version.txt
adb logcat -G 16M || true
adb shell wm size 1080x2400
adb shell wm density 440
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent 82
adb shell wm dismiss-keyguard
adb shell settings put system font_scale 1.0
adb shell settings put secure immersive_mode_confirmations confirmed
sleep 2
adb shell wm size | tee -a $OUT/steps.txt
adb shell wm density | tee -a $OUT/steps.txt
SH=$(adb shell wm size | tr -d '\r' | tail -1 | sed 's/.*x//')
DENS=$(adb shell wm density | tr -d '\r' | tail -1 | grep -o '[0-9]*$')
log "screen height $SH density $DENS"

log "install test game"
adb install -r -g $TGA 2>&1 | tail -1 | tee -a $OUT/steps.txt

# ---------- 登録画像を作る ----------
log "make template"
adb shell am start -S -W -n $TGP/.GameActivity --es mode template >/dev/null
sleep 4
shot tpl_screen
TPL=$(adb logcat -d -s TG:I | grep "TPL" | tail -1)
log "template line: $TPL"
python3 ci/crop.py $OUT/tpl_screen.png "$TPL" $OUT/template.png | tee -a $OUT/steps.txt

# ---------- オートクリッカーの準備 ----------
cp ci/ac.xml /tmp/ac.xml
adb push $OUT/template.png /data/local/tmp/t1.png >/dev/null
adb push /tmp/ac.xml /data/local/tmp/ac.xml >/dev/null
adb shell "chmod 644 /data/local/tmp/t1.png /data/local/tmp/ac.xml"

prepare_app() {
  local apk=$1
  log "prepare clicker $apk"
  adb shell settings put secure enabled_accessibility_services '""'
  adb shell settings put secure accessibility_enabled 0
  sleep 1
  adb uninstall $PKG >/dev/null 2>&1
  r=$(adb install -r -g -i com.android.vending $apk 2>&1 | tail -1)
  echo "install(-i vending): $r" | tee -a $OUT/steps.txt
  case "$r" in *Success*) ;; *) adb install -r -g $apk 2>&1 | tail -1 | tee -a $OUT/steps.txt ;; esac
  adb shell appops set $PKG ACCESS_RESTRICTED_SETTINGS allow 2>&1 | tee -a $OUT/steps.txt
  adb shell am force-stop $PKG
  adb shell "run-as $PKG sh -c 'mkdir -p files/templates shared_prefs && cp /data/local/tmp/t1.png files/templates/t1.png && cp /data/local/tmp/ac.xml shared_prefs/ac.xml && ls files/templates shared_prefs'" 2>&1 | tee -a $OUT/steps.txt
  adb shell appops set $PKG PROJECT_MEDIA allow
  # インストール直後はシステムが設定を書き戻すことがあるので、つながるまで繰り返す
  sleep 6
  for k in 1 2 3 4 5 6; do
    adb shell "settings put secure enabled_accessibility_services $PKG/.ClickerService"
    adb shell settings put secure accessibility_enabled 1
    sleep 4
    if adb shell dumpsys accessibility | grep -A1 "Bound services" | grep -q "オートクリッカー\|autoclicker"; then
      log "accessibility bound (try $k)"
      break
    fi
    log "accessibility not bound yet (try $k)"
  done
  adb shell settings get secure enabled_accessibility_services | tee -a $OUT/steps.txt
  adb shell dumpsys accessibility > $OUT/dumpsys_accessibility_$(basename $apk).txt 2>&1
  adb shell dumpsys package $PKG | grep -i -E "stopped|installer|restrict" | tee -a $OUT/steps.txt
  adb shell appops get $PKG | tee -a $OUT/steps.txt
}

# ---------- 比較用：前のバージョン ----------
# 前のバージョンは adb から操作できないので、パネルの「🔍 探す」を座標で押す
# （パネル左上 12dp,120dp → ボタンの中心は 約 47.75dp, 199dp）
OLDBTN="$((DENS * 4775 / 16000)) $((DENS * 199 / 160))"
log "old button at $OLDBTN (density $DENS)"
run_old() {
  local name=$1 speed=$2 alive=$3 dur=$4
  log "old scan $name speed=$speed alive=$alive"
  adb shell am start -S -W -n $TGP/.GameActivity --es mode scan --ei speed $speed --ei alive $alive --ei life 3000 --ei seed 11 >/dev/null
  sleep 2
  mark "start $name"
  adb shell input tap $OLDBTN
  sleep $((dur / 2))
  shot "scan_$name"
  sleep $((dur / 2))
  adb shell input tap $OLDBTN
  sleep 1
  mark "end $name"
}
if [ -f old-app.apk ]; then
  prepare_app old-app.apk
  shot old_panel
  run_old old_static 0 1 30
  run_old old_move 600 2 30
fi

prepare_app $APP
dsys

# ---------- 設定画面の見た目（文字切れ・スクロール） ----------
ui_shots() {
  local tag=$1
  adb shell am start -S -W -n $PKG/.MainActivity >/dev/null
  sleep 3
  for i in 1 2 3 4 5 6; do
    shot "ui_${tag}_$i"
    adb shell input swipe 540 $((SH * 70 / 100)) 540 $((SH * 30 / 100)) 500
    sleep 1
  done
  adb shell input keyevent 4
  sleep 1
}
log "settings screenshots"
ui_shots f100
adb shell settings put system font_scale 1.3
sleep 2
ui_shots f130
adb shell settings put system font_scale 1.0
sleep 2
dsys panel

# ---------- 画像を探してタップ ----------
run_scan() {
  local name=$1 mode=$2 speed=$3 predict=$4 alive=$5 dur=$6
  log "scan $name mode=$mode speed=$speed predict=$predict alive=$alive"
  dsys set predict $predict >/dev/null
  adb shell am start -S -W -n $TGP/.GameActivity --es mode scan --ei speed $speed --ei alive $alive --ei life 3000 --ei seed 11 >/dev/null
  sleep 2
  mark "start $name"
  dsys scan $mode
  sleep 3
  press_start
  sleep $((dur / 2))
  shot "scan_$name"
  dsys
  sleep $((dur / 2))
  dsys stop
  sleep 1
  mark "end $name"
  dsys
}

run_scan fast_static fast 0 true 1 30
run_scan fast_move fast 600 true 2 30
run_scan fast_move_nopred fast 600 false 2 30
run_scan fast_fast fast 1200 true 1 30
dsys endcapture
sleep 2
run_scan slow_static slow 0 true 1 30
run_scan slow_move slow 600 true 2 30

# ---------- 記録と再生 ----------
log "record"
adb shell am start -S -W -n $TGP/.GameActivity --es mode touch >/dev/null
sleep 2
dsys set count 1 >/dev/null
dsys set speed 10 >/dev/null
dsys rec
sleep 1
mark "rec"
for p in "300 900" "700 1100" "500 1500" "800 1800" "300 2000" "600 1300" "450 1700" "900 900"; do
  adb shell input swipe $p $p 80
  sleep 0.25
done
adb shell input swipe 200 1500 850 1500 300
sleep 1
shot rec_screen
dsys stoprec
sleep 1
mark "play"
dsys play
sleep 12
mark "playend"
dsys
shot play_end

# ---------- 記録中の連続タップ（取りこぼし） ----------
log "fast taps while recording"
dsys rec
sleep 1
mark "rec2"
adb shell "for i in 1 2 3 4 5 6 7 8 9 10; do input tap 540 1200; done"
sleep 1
dsys stoprec
mark "rec2end"

log "collect logs"
adb logcat -d -v threadtime -s AC:V TG:V AndroidRuntime:E System.err:W > $OUT/logcat_ac_tg.txt
adb logcat -d -v threadtime "*:W" | tail -3000 > $OUT/logcat_warn.txt
adb logcat -d -v threadtime | grep -i -E "accessib|autoclicker|MediaProjection|ClickerService|CaptureService" | tail -800 > $OUT/logcat_a11y.txt
python3 ci/parse.py $OUT/logcat_ac_tg.txt | tee $OUT/summary.txt
log "done"
exit 0
