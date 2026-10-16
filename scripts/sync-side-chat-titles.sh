#!/bin/bash
# Pull real side-chat titles from the official Muse app UI and update Muse r1's cache.
set -e
PKG=com.zzyan.r1.muse
ADB="adb"

# 1. DB-ordered session ids (title blobs are encrypted in Aura, ids are plaintext)
$ADB shell 'su -c "cp /data/data/com.facebook.aura/databases/hatch_offline_conversation.db /sdcard/hatch.db; chmod 666 /sdcard/hatch.db"' >/dev/null
TMP=$(mktemp -d)
$ADB pull /sdcard/hatch.db "$TMP/hatch.db" >/dev/null
$ADB shell 'rm /sdcard/hatch.db'
IDS=$(sqlite3 "$TMP/hatch.db" "SELECT session_id FROM offline_threads WHERE is_thread=1 ORDER BY updated_at DESC" | tr '\n' ' ')
echo "sessions: $IDS"

# 2. Navigate the official app to the SIDE CHATS list and harvest visible titles
$ADB shell am start -n com.facebook.aura/.main.AuraMainActivity >/dev/null 2>&1
sleep 4
$ADB shell input tap 50 83   # Open side panel
sleep 2
$ADB shell uiautomator dump /data/local/tmp/tt.xml >/dev/null 2>&1
STATE=$($ADB shell cat /data/local/tmp/tt.xml 2>/dev/null | grep -c "SIDE CHATS")
[ "$STATE" = "0" ] && $ADB shell input tap 190 156 && sleep 2   # toggle to SIDE CHATS
$ADB shell uiautomator dump /data/local/tmp/tt.xml >/dev/null 2>&1
$ADB pull /data/local/tmp/tt.xml "$TMP/tt.xml" >/dev/null

python3 - "$IDS" "$TMP/tt.xml" <<'PY'
import re, sys, json, subprocess
ids = sys.argv[1].split()
xml = open(sys.argv[2]).read()
texts = re.findall(r'text="([^"]{1,60})"', xml)
# rows appear between the "SIDE CHATS" header and the Settings/New-side-chat footer
try:
    start = next(i for i, t in enumerate(texts) if t == "SIDE CHATS") + 1
except StopIteration:
    print("SIDE CHATS header not found"); raise SystemExit(1)
stop_candidates = [i for i, t in enumerate(texts) if t in ("Settings", "New side chat")]
titles = [t for t in texts[start:min(stop_candidates) if stop_candidates else len(texts)]]
pairs = list(zip(ids, titles))
print(json.dumps(pairs, ensure_ascii=False))
# 3. Rewrite Muse r1's side-chats.json names
out = subprocess.run(["adb", "shell", f"run-as {PKG} cat no_backup/side-chats.json"], capture_output=True, text=True)
data = json.loads(out.stdout) if out.stdout.strip() else {"main_unread": 0, "sessions": {}}
for sid, title in pairs:
    if sid in data["sessions"]:
        data["sessions"][sid]["name"] = title
payload = json.dumps(data, ensure_ascii=False)
subprocess.run(["adb", "shell", f"run-as {PKG} sh -c 'umask 077; cat > no_backup/side-chats.json'"], input=payload, text=True)
subprocess.run(["adb", "shell", f"am force-stop {PKG}"])
subprocess.run(["adb", "shell", f"am start -n {PKG}/.core.MainActivity"])
print("updated:", [t for _, t in pairs])
PY
rm -rf "$TMP"
