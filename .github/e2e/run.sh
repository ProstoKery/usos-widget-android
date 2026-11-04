#!/usr/bin/env bash
# End-to-end test on an Android emulator: the real APK, a demo timetable served from the runner,
# both widgets pinned to the home screen, a change notification and all four languages.
# Screenshots of every step go to ./e2e-shots.
set -uo pipefail

PKG=pl.kera.usoswidget
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=e2e-shots
SERVE=$(mktemp -d)
LINK=http://10.0.2.2:8000/plan.ics   # 10.0.2.2 = the runner, as seen from the emulator
mkdir -p "$OUT"

shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "📸 $1"; }
ui() { python3 "$HERE/ui.py" "$@"; }
finish() {
  adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
  adb shell dumpsys appwidget > "$OUT/dumpsys-appwidget.txt" 2>/dev/null
  adb shell dumpsys jobscheduler | grep -A12 "$PKG" > "$OUT/dumpsys-jobs.txt" 2>/dev/null
}
fail() { echo "❌ $*"; ui dump > "$OUT/zz-failure-screen.txt" 2>&1; adb exec-out screencap -p > "$OUT/zz-failure.png"; finish; exit 1; }
share_link() {
  adb shell "am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '$1' -n $PKG/.MainActivity" >/dev/null
}
saved_at() { adb shell run-as $PKG cat shared_prefs/store.xml 2>/dev/null | sed -n 's/.*name="schedule_at" value="\([0-9]*\)".*/\1/p'; }

adb wait-for-device
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
TZID=$(adb shell getprop persist.sys.timezone | tr -d '\r')
[ -n "$TZID" ] || TZID=UTC
echo "Emulator time zone: $TZID"

python3 "$HERE/make_ics.py" "$TZID" "$SERVE/plan.ics"
(cd "$SERVE" && python3 -m http.server 8000 >/dev/null 2>&1 &)
sleep 1
curl -sf http://127.0.0.1:8000/plan.ics >/dev/null || fail "demo timetable server did not start"

echo "== Install"
adb install -r -g "$APK" || fail "install failed"
adb shell cmd locale set-app-locales $PKG --locales en
adb shell am start -W -n $PKG/.MainActivity >/dev/null || fail "app did not start"
shot 01-first-start 3

echo "== Give the link the way users can: Share → USOS Widget"
share_link "My USOS plan: $LINK"
for _ in $(seq 1 40); do [ -n "$(saved_at)" ] && break; sleep 1; done
[ -n "$(saved_at)" ] || fail "timetable was not downloaded"
shot 02-app-with-timetable 2

echo "== Pin both widgets with the in-app buttons"
ui tap '^Add .Timetable.$' --scroll --timeout 20 || fail "'Add Timetable' button not found"
shot 03-pin-dialog 3
ui tap '^(add to home screen|add automatically|add)$' --timeout 15 || fail "pin dialog did not appear"
sleep 2
adb shell am start -W -n $PKG/.MainActivity >/dev/null
ui tap '^Add .Next class.$' --scroll --timeout 20 || fail "'Add Next class' button not found"
ui tap '^(add to home screen|add automatically|add)$' --timeout 15 || fail "second pin dialog did not appear"
sleep 2
adb shell input keyevent KEYCODE_HOME
shot 04-home-widgets 5
WIDGETS=$(adb shell dumpsys appwidget | grep -c "ComponentInfo{$PKG/")
echo "widget entries in dumpsys: $WIDGETS"

echo "== Timetable change → notification"
python3 "$HERE/make_ics.py" "$TZID" "$SERVE/plan.ics" --changed
BEFORE=$(saved_at)
share_link "$LINK"
for _ in $(seq 1 40); do [ "$(saved_at)" != "$BEFORE" ] && break; sleep 1; done
sleep 2
adb shell dumpsys notification --noredact | grep -q "Timetable changes" || fail "no change notification"
adb shell input keyevent KEYCODE_HOME
adb shell cmd statusbar expand-notifications
shot 05-change-notification 3
adb shell cmd statusbar collapse

echo "== Background job runs without crashing"
adb shell cmd jobscheduler run -f $PKG 1
sleep 5

echo "== Languages"
for L in pl uk ru en; do
  adb shell cmd locale set-app-locales $PKG --locales $L
  sleep 3
  adb shell am start -W -n $PKG/.MainActivity >/dev/null
  shot "06-app-$L" 3
  adb shell input keyevent KEYCODE_HOME
  shot "07-home-$L" 4
done

finish
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A3 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG"; then
  fail "the app crashed (see logcat.txt)"
fi
echo "✅ all steps passed — screenshots in $OUT"
