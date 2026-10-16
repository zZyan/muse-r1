#!/bin/bash
# Replay the recorded power-press stream byte-exact via sendevent.
set -e
FILE=${1:-/tmp/power-raw.txt}
adb root >/dev/null 2>&1; sleep 1
# Build a replay script: one sendevent per line with correct device
sed 's|^/dev/input/\([^ ]*\)|sendevent /dev/input/\1|' "$FILE" > /tmp/replay.txt
ADB_BATCH=$(awk '{print $0}' /tmp/replay.txt | sed 's/[[:space:]]\+/ /g' | sed 's/sendevent \([^ ]*\) \([^ ]*\) \([^ ]*\) \([^ ]*\)/sendevent \1 \2 \3 \4/')
adb push /tmp/replay.txt /data/local/tmp/replay.txt >/dev/null
adb shell 'chmod +x /data/local/tmp/replay.txt'
echo "=== replaying ==="
adb shell "su -c 'sh /data/local/tmp/replay.txt'"
echo "=== done ==="
