# Start here: LOCAL-ONLY patcher kit

> LOCAL-ONLY: NOT CLEARED FOR REDISTRIBUTION.

This kit includes the build inputs used for local testing. Redistribution of
the complete bundle has not been cleared. The official Minecraft client JAR
is not included.

1. On Linux, verify the package before adding the client JAR:
   `./local-media-verify.sh .`. This checks package integrity only. It
   intentionally rejects a package containing the official client JAR.
2. Launch the GUI with the platform launcher:
   - Linux x86_64: `./bootstrap/launch-gui-linux-x86_64.sh`
   - macOS: `./bootstrap/macos/launch-gui-macos.command`
   - Windows x64: `bootstrap/windows/eagler-patcher-windows-x86_64.cmd`
3. Browse to your official Minecraft 26.2 client JAR. It can stay in its current
   folder; you do not need to copy it into this kit.
4. Select Standalone HTML, then choose a project folder and HTML filename.
   Click Build HTML. A new project folder gets patched source before compilation.
   An existing compatible patcher project is detected and reused automatically.

The complete Setup JAR extracts the kit itself. The GUI finds the included patch
bundle, decompiler, skeleton, resource overlay and audio automatically. Leave
Advanced alone unless you intentionally want to override an input. You do not
need to extract ZIPs or enter their paths. Keep the installed files together.

Choose Source project if you only want editable source. Each generated project
has a GUIDE.md with build commands and hosting instructions. Open folder shows
the result after a successful run.

Standalone uses the Normal profile and defaults to a smaller HTML with a music
resource-pack ZIP beside it. Import that ZIP in the game's resource-pack screen.
Choose embedded music for a larger single HTML file. Wispcraft is optional and
requires a local `dist/index.js` that you supply.

The Windows and macOS launchers have not been tested on native hosts. The
integrity checker verifies the local kit; it does not build or run the game.
