#!/system/bin/sh
# KeyPilot optional Magisk companion service script
#
# IMPORTANT:
# KeyPilot's primary mode is the LSPosed/Vector Xposed module (system_server hook).
# The native fallback daemon (keypilotd) uses EVIOCGRAB on /dev/input/event0 and is
# ONLY started when explicitly opted in by creating /data/system/keypilot_use_daemon
# (e.g., when running without Xposed/LSPosed).

MODDIR="${0%/*}"
DAEMON="$MODDIR/system/bin/keypilotd"
OPTIN_FLAG="/data/system/keypilot_use_daemon"
LOG_FILE="/data/local/tmp/keypilotd.log"

# Wait for boot completion
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 2
done

# Ensure config directory exists with system ownership
if [ -f /data/system/keypilot.json ]; then
  chown 1000:1000 /data/system/keypilot.json 2>/dev/null || true
  chmod 0600 /data/system/keypilot.json 2>/dev/null || true
fi

# Start optional native fallback daemon ONLY if opt-in flag exists and binary is present
if [ -f "$OPTIN_FLAG" ] && [ -x "$DAEMON" ]; then
  echo "[KeyPilot] opt-in flag detected; launching optional keypilotd fallback daemon" >> "$LOG_FILE"
  nohup "$DAEMON" /dev/input/event0 >> "$LOG_FILE" 2>&1 &
else
  echo "[KeyPilot] primary Xposed mode active (keypilotd opt-in flag not set)" >> "$LOG_FILE"
fi
