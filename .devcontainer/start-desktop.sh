#!/usr/bin/env bash
set -euo pipefail

SESSION_DIR="$HOME/eagler-visual-session"
mkdir -p "$SESSION_DIR" "$HOME/.vnc"
LOG="$SESSION_DIR/desktop.log"
PIDFILE="$SESSION_DIR/desktop.pids"
if [ -f "$PIDFILE" ]; then
  while read -r pid; do
    if kill -0 "$pid" 2>/dev/null; then
      exit 0
    fi
  done < "$PIDFILE"
fi
rm -f "$PIDFILE"

VNC_PASS="$(od -An -N9 -tx1 /dev/urandom | tr -d ' \n' | cut -c1-12)"
printf '%s\n' "$VNC_PASS" > "$SESSION_DIR/vnc-password.txt"
chmod 600 "$SESSION_DIR/vnc-password.txt"
x11vnc -storepasswd "$VNC_PASS" "$HOME/.vnc/passwd" >/dev/null

export DISPLAY=:1
Xvfb "$DISPLAY" -screen 0 1440x900x24 -ac +extension GLX >"$LOG" 2>&1 &
echo $! >> "$PIDFILE"
sleep 2
dbus-launch --exit-with-session startxfce4 >>"$LOG" 2>&1 &
echo $! >> "$PIDFILE"
sleep 3
x11vnc -display "$DISPLAY" -rfbauth "$HOME/.vnc/passwd" -rfbport 5901 -localhost -forever -shared >>"$LOG" 2>&1 &
echo $! >> "$PIDFILE"
sleep 2
websockify --web=/usr/share/novnc/ 6080 localhost:5901 >>"$LOG" 2>&1 &
echo $! >> "$PIDFILE"

echo "Eaglercraft desktop started."
echo "VNC password: $VNC_PASS"
echo "Open the PRIVATE forwarded port 6080 in the Codespaces Ports tab."
echo "Password is also saved in $SESSION_DIR/vnc-password.txt"
