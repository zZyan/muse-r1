#!/bin/bash
# Rabbit R1 camera motor reset: 0->90->180 (rear)
adb root && adb wait-for-device
NODE=/sys/devices/platform/step_motor_ms35774/orientation
adb shell "echo 0 > $NODE; sleep 1; echo 90 > $NODE; sleep 1; echo 180 > $NODE; sleep 1; cat $NODE"