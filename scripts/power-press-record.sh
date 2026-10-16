#!/bin/bash
# Record the physical power button as a raw sendevent stream (root needed).
# Usage: ./power-press-record.sh [events_to_capture]
set -e
N=${1:-12}
adb root >/dev/null 2>&1; sleep 1
echo "RECORDING $N events from /dev/input/event0 — PRESS THE POWER BUTTON NOW"
adb shell "getevent -c $N /dev/input/event0" > /tmp/power-raw.txt
grep -c 0001 /tmp/power-raw.txt && echo "captured ok" && cat /tmp/power-raw.txt | head -12
