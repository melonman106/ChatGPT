# Eaglercraft visual setup in Codespaces

This gives you a temporary XFCE desktop in your browser, without installing desktop software on your Chromebook.

## Start it

1. Open [this repository](https://github.com/melonman106/ChatGPT).
2. Select **Code → Codespaces → Create codespace on main**. If Codespaces is unavailable for your account, GitHub will show that before creation.
3. Wait for setup to finish. The desktop starts automatically.
4. In the Codespaces **Ports** tab, open port **6080** in your browser. Keep the port's visibility set to **Private**.
5. Enter the VNC password printed in the Codespaces terminal.
6. Follow the instructions in the workspace's README-FIRST.txt file.

The setup JAR is downloaded into your home folder at eagler-visual-session/. The repository's U1-Patcher folder is available as the patch source. A Minecraft client JAR may still need to be provided in the GUI.

## Save your work

Save all output under ~/eagler-visual-session/workspace/. Before stopping the Codespace, create a ZIP from the terminal:

    cd ~/eagler-visual-session
    zip -qr workspace.zip workspace

Then right-click workspace.zip in the VS Code Explorer and choose Download.

## Security and lifecycle

- The forwarded desktop is configured as **private** and the VNC session has a randomly generated password. Do not change the port visibility to Public.
- Do not enter GitHub credentials or other secrets into the remote desktop.
- Codespaces has usage limits and may require available quota. The environment is temporary; download your output before deleting or stopping the Codespace.
