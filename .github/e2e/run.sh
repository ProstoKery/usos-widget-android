#!/usr/bin/env bash
# End-to-end test on an Android emulator: the real app (e2e build), a demo timetable served from the runner,
# both widgets rendered by a widget host, a change notification and all four languages.
# Screenshots of every step go to ./e2e-shots.
set -uo pipefail

PKG=pl.kera.usoswidget
APK=${1:-app/build/outputs/apk/e2e/app-e2e.apk}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=e2e-shots
SERVE=$(mktemp -d)
LINK=http://10.0.2.2:8000/plan.ics   # 10.0.2.2 = the runner, as seen from the emulator
mkdir -p "$OUT"

python3 -m http.server 8000 --directory "$SERVE" >/dev/null 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null' EXIT

shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "📸 $1"; }
finish() {
  adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
  adb shell dumpsys jobscheduler | grep -A12 "$PKG" > "$OUT/dumpsys-jobs.txt" 2>/dev/null
}
fail() { echo "❌ $*"; python3 "$HERE/ui.py" dump > "$OUT/zz-failure-screen.txt" 2>&1; adb exec-out screencap -p > "$OUT/zz-failure.png"; finish; exit 1; }
share_link() {
  adb shell "am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '$1' -n $PKG/.MainActivity" >/dev/null
}
saved_at() { adb shell run-as $PKG cat shared_prefs/store.xml 2>/dev/null | sed -n 's/.*name="schedule_at" value="\([0-9]*\)".*/\1/p'; }
widgets() { adb shell am start -W --activity-clear-task -n $PKG/.WidgetPreviewActivity >/dev/null; }
app() { adb shell am start -W --activity-clear-task -n $PKG/.MainActivity >/dev/null; }

adb wait-for-device
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
TZID=$(adb shell getprop persist.sys.timezone | tr -d '\r')
[ -n "$TZID" ] || TZID=UTC
echo "Emulator time zone: $TZID"

python3 "$HERE/make_ics.py" "$TZID" "$SERVE/plan.ics"
sleep 1
curl -sf http://127.0.0.1:8000/plan.ics >/dev/null || fail "demo timetable server did not start"

echo "== Install"
adb install -r -g "$APK" || fail "install failed"
adb shell cmd locale set-app-locales $PKG --locales en
app || fail "app did not start"
shot 01-first-start 3

echo "== Give the link the way users can: Share → USOS Widget"
share_link "My USOS plan: $LINK"
for _ in $(seq 1 40); do [ -n "$(saved_at)" ] && break; sleep 1; done
[ -n "$(saved_at)" ] || fail "timetable was not downloaded"
shot 02-app-with-timetable 2

echo "== Both widgets, drawn by a widget host"
adb shell appwidget grantbind --package $PKG --user 0 || echo "grantbind command failed"
widgets
shot 03-widgets 4

echo "== Timetable change → notification"
python3 "$HERE/make_ics.py" "$TZID" "$SERVE/plan.ics" --changed
BEFORE=$(saved_at)
share_link "$LINK"
for _ in $(seq 1 40); do [ "$(saved_at)" != "$BEFORE" ] && break; sleep 1; done
sleep 2
adb shell dumpsys notification --noredact | grep -q "Timetable changes" || fail "no change notification"
adb shell input keyevent KEYCODE_HOME
sleep 1
adb shell input swipe 540 5 540 700 250   # pull the notification shade down once
shot 04-change-notification 3
adb shell input keyevent KEYCODE_BACK
widgets
shot 05-widgets-after-change 4

echo "== Background job runs without crashing"
adb shell cmd jobscheduler run -f $PKG 1
sleep 5

echo "== Languages"
for L in pl uk ru en; do
  adb shell cmd locale set-app-locales $PKG --locales $L
  sleep 3
  app
  shot "06-app-$L" 3
  widgets
  shot "07-widgets-$L" 4
done

finish
if grep -A3 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG"; then
  fail "the app crashed (see logcat.txt)"
fi
echo "✅ all steps passed — screenshots in $OUT"
