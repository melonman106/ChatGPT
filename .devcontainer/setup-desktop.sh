#!/usr/bin/env bash
set -euo pipefail

sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
  xfce4 xfce4-terminal thunar xvfb x11vnc novnc websockify dbus-x11 xauth \
  fonts-dejavu-core ca-certificates curl zip
sudo apt-get clean
sudo rm -rf /var/lib/apt/lists/*

mkdir -p "$HOME/eagler-visual-session/workspace" "$HOME/.vnc"
cd "$HOME/eagler-visual-session"
if [ ! -f Eaglercraft-26.2-u1-Setup.jar ]; then
  curl --fail --location --retry 3 \
    "https://github.com/melonman106/ChatGPT/releases/download/Modded/Eaglercraft-26.2-u1-Setup.jar" \
    --output Eaglercraft-26.2-u1-Setup.jar
fi

cat > "$HOME/eagler-visual-session/README-FIRST.txt" <<'EOF'
Eaglercraft visual setup desktop

1. Open the forwarded port 6080 from the Codespaces Ports tab (keep visibility Private).
2. Enter the VNC password printed in the Codespaces terminal when the desktop starts.
3. In the desktop, open the file manager and go to /home/vscode/eagler-visual-session.
4. Double-click Eaglercraft-26.2-u1-Setup.jar. If prompted, choose Open with Java.
5. For Patch Source, browse to the repository's U1-Patcher folder.
6. Choose a workspace/output folder under /home/vscode/eagler-visual-session/workspace.
7. Save your completed workspace there. When finished, use the Codespaces terminal to package it:
   cd ~/eagler-visual-session && zip -qr workspace.zip workspace
8. Download workspace.zip from the VS Code Explorer by right-clicking it and choosing Download.

The Minecraft 26.2 client JAR must be supplied separately if the GUI asks for it. Only use a client JAR you are entitled to access. Do not make port 6080 public.
EOF

mkdir -p "$HOME/Desktop"
cat > "$HOME/Desktop/Launch Eaglercraft Setup.desktop" <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=Launch Eaglercraft Setup
Comment=Open the Eaglercraft visual installer
Exec=java -jar $HOME/eagler-visual-session/Eaglercraft-26.2-u1-Setup.jar
Terminal=false
Categories=Development;
EOF
chmod +x "$HOME/Desktop/Launch Eaglercraft Setup.desktop"

echo "Setup dependencies, downloaded the Setup JAR, and created a desktop launcher."
