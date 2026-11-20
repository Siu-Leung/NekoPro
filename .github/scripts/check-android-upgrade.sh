#!/usr/bin/env bash
set -euo pipefail

# Never use an attached personal device for this regression test.
serial=emulator-5554
adb_cmd=(adb -s "$serial")
[[ $("${adb_cmd[@]}" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
old_apk=${1:?previous signed APK required}
new_apk=${2:?candidate signed APK required}
expected_version=${3:-Neko-Pro-1.3.0}
pkg=moe.nb4a.siu
activity=$pkg/io.nekohasekai.sagernet.ui.MainActivity
marker=NekoPro-Upgrade-Smoke

export ADB_SERIAL=$serial

ui_dump() {
  "${adb_cmd[@]}" shell uiautomator dump /data/local/tmp/neko-upgrade-window.xml >/dev/null &&
    "${adb_cmd[@]}" exec-out cat /data/local/tmp/neko-upgrade-window.xml
}

wait_for_view() {
  local attr=$1 value=$2 xml result
  for ((attempt=0; attempt<12; attempt++)); do
    if xml=$(ui_dump) && result=$(python3 -c '
import sys, re, xml.etree.ElementTree as ET
for node in ET.fromstring(sys.stdin.read()).iter("node"):
    if node.get(sys.argv[1], "") == sys.argv[2]:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        print((x1+x2)//2, (y1+y2)//2)
        break
else:
    sys.exit(1)
' "$attr" "$value" <<< "$xml"); then
      printf '%s\n' "$result"
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for $attr=$value" >&2
  return 1
}

"${adb_cmd[@]}" install -g "$old_apk"
"${adb_cmd[@]}" shell am start -W -n "$activity" -a android.intent.action.VIEW -d "socks://127.0.0.1:1080#$marker"
wait_for_view text 'Import profile' >/dev/null
read -r x y <<< "$(wait_for_view resource-id android:id/button1)"
"${adb_cmd[@]}" shell input tap "$x" "$y"
wait_for_view text "$marker" >/dev/null

# Keep the same application data directory: never uninstall or use pm clear.
"${adb_cmd[@]}" shell am force-stop "$pkg"
"${adb_cmd[@]}" install -r -g "$new_apk"
package_info=$("${adb_cmd[@]}" shell dumpsys package "$pkg")
if ! grep -Fq "versionName=${expected_version}" <<< "$package_info"; then
  echo "Installed version is not ${expected_version}." >&2
  exit 1
fi
"${adb_cmd[@]}" shell am start -W -n "$activity"
wait_for_view text "$marker" >/dev/null
printf 'Signed v1.2.2 -> %s in-place upgrade preserved the imported test profile.\n' "$expected_version"
