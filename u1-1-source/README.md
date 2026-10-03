# Eaglercraft 26.2 u1 patcher

This repository contains the Java patcher, its desktop interface, and the tools
used to prepare source patches and the build project. The patcher decompiles a
user-supplied official Minecraft 26.2 client JAR, applies the Eaglercraft changes,
and builds an editable project or a standalone HTML client.

Vanilla mobs, blocks, biomes, structures, and world generation come from the
Minecraft JAR. The patches adapt that code for the browser. This repository
does not contain the full game source or a catalogue of Minecraft 26.2 content.

## Start here

Download [Eaglercraft 26.2 u1 Setup](https://eaglercraft-26-2-u1-cdn.u2471966200.workers.dev/Eaglercraft-26.2-u1-Setup.jar).
Java 17 or newer is needed to open the JAR. Setup downloads the remaining build
tools when needed, so keep an internet connection available.

The complete Setup JAR asks where to install the patcher, unpacks its bundled
files, and sets up the build tools. In the patcher:

1. Select your official Minecraft 26.2 client JAR.
2. Choose a project folder and an output mode.
3. Click Create project or Build HTML. After it finishes, Open folder shows the result.

Internal patch ZIPs, the project skeleton, resource overlay and bundled audio
are found automatically. You do not need to unzip them or select them in
Advanced. Before building, the patcher repairs missing internal paths from its
installation folder while keeping readable custom paths.

The Setup JAR includes the build inputs. This Git repository contains the
patcher source, tests and resource/audio recipes; the large input archives are
distributed inside Setup. Building just the GUI from this checkout does not
include those archives.

See [GUIDE.md](GUIDE.md) for instructions to build the Normal client and host
the output, including the local Isolated Web App workflow.

## Build and open the window

Build with JDK 17:

```sh
cd patcher/java-cli
./build.sh
```

The GUI JAR is written to
`patcher/java-cli/build/eaglercraft-26.2-u1-patcher-gui.jar`. Open it with
Java, or double-click it if your desktop associates JAR files with Java. From
a terminal, run:

```sh
java -jar build/eaglercraft-26.2-u1-patcher-gui.jar
```

`make-single-file.sh` wraps a complete portable kit in one JAR. On first launch,
Setup extracts that kit into a folder you choose. The source-patch bundle,
skeleton and resource overlay are in the released Setup JAR rather than Git.

Run `patcher/java-cli/eagler-patcher --help` to see the command-line options.
After building, `./test-gui.sh` checks the GUI wiring without opening a window.

## Source project or HTML?

Choose **Standalone HTML** for a playable file. With a new project folder, this
creates the patched source and then compiles it; you do not need to run
**Source project** first. The source stays in the project folder.

Choose **Source project** when you only want the editable project. Generated
projects include `GUIDE.md` with build and output instructions. For an existing
compatible project, choose its folder in Standalone HTML mode. The patcher
detects it and reuses its source automatically, without extracting or patching
it again. Unrelated folders and incompatible projects are rejected. Keep backups
of your edits and keep the project's receipt file.

HTML builds need substantially more memory than source extraction. The patcher
measures available RAM, keeps a system reserve, and passes the remaining budget
to the Java linker and the Gradle heap.
The current linker needs a build budget of at least 10 GiB, in addition to that
reserve; 16 GiB total RAM or more is recommended. Other running apps may leave
too little available memory even on a 16 GiB machine. A smaller allowed heap
may build more slowly or run out of heap; the budget is not a completion guarantee.
The generated project uses an 8 GiB Gradle heap for direct tasks; the patcher’s
HTML build selects a larger heap only when the measured budget allows it.

## Build time

Allow about 30 minutes for a full HTML build on a reasonably capable PC.
Downloads, slower hardware and memory pressure can add time.

For reference, a completed local Normal build on 29 September 2026 recorded
41 minutes 46 seconds from start to HTML output. Allow extra time rather than
treating the estimate as a fixed countdown.

Source extraction alone is much shorter. Reusing a project skips extraction
and patching, but the WebAssembly compilation can still take most of the time.
The patcher shows elapsed time. Leave it
running while the compiler is working; do not start another build in the same
project folder.

## App-local tool setup

The Linux launcher can install Java 17, Java 25, Node.js 24, and npm inside the
patcher folder. Build the GUI and copy its JAR beside the launcher:

```sh
cd patcher/java-cli
cp build/eaglercraft-26.2-u1-patcher-gui.jar .
./bootstrap/launch-gui-linux-x86_64.sh
```

It supports glibc Linux x86_64 and requires an internet connection. The
launcher checks vendor SHA-256 values and stores the tools in `.toolchain`
inside the application folder. Run it as a normal user, without `sudo`.

The Windows launcher is in `patcher/java-cli/bootstrap/windows/`. Build the
GUI JAR with Bash (for example, Git Bash or WSL), copy it to
`patcher/java-cli/eaglercraft-26.2-u1-patcher-gui.jar`, and run
`patcher/java-cli/bootstrap/windows/eagler-patcher-windows-x86_64.cmd`.
The script looks for the JAR at the package root, not beside the `.cmd` file.
It supports Windows 10 22H2 or Windows 11 x64, 64-bit PowerShell 5.1 or newer,
a non-administrator account, and a writable local folder. It downloads the
vendor tool archives and stores them beside the patcher. Native Windows
execution has not been verified. This source checkout has no prebuilt JAR; the
separate local package does.

## Making and applying a source patch

`patcher/source-patches/export_source_patches.py` compares two Java source
trees and writes a deterministic bundle of add, modify, and delete operations.
File hashes identify each operation. The Java CLI checks those hashes before
applying a bundle.

For example, with two source trees you are authorized to use:

```sh
python3 patcher/source-patches/export_source_patches.py export \
  --base /path/to/base-java \
  --final /path/to/changed-java \
  --output /tmp/source-patch \
  --archive /tmp/source-patch.zip
```

The exporter can also verify and apply a bundle. See
`patcher/source-patches/README.md` for the commands and required source trees.
The Java CLI accepts only its pinned source bundle. To use another bundle,
change that pin in the source first.

`patcher/project-skeleton/export_skeleton.py` shows how the non-game project
files are collected into a pinned skeleton ZIP. The portable-package script
is also included for reference, but needs the omitted local inputs and full
workspace layout to run. The two `wasm-toolchain` scripts show the standalone
HTML and music-pack output paths; this source-only repo does not contain the
rest of the game build tree.

## Required build inputs

The complete kit manages the following inputs. Only the official Minecraft JAR
is supplied by the user during the normal setup flow:

- Your official Minecraft 26.2 client JAR.
- Vineflower 1.12.0 to decompile it.
- The pinned source-patch bundle, which changes the decompiled Java files.
- The project skeleton, which supplies the browser platform code and build files.
- The resource overlay, six external resource files, and sounds/music EPKs.

The source-patch bundle, skeleton, overlay, and finished media archives are
absent from this source checkout. The included resource recipe can fetch four
official asset objects and derive the six files used by the overlay. The audio
tools can fetch official sound objects and rebuild the sounds/music EPKs; they
need Python 3, Node 20+, and FFmpeg 6.1.1. The desktop kit has **Get official
resources** and **Get game audio** actions; FFmpeg is not installed
automatically. See
[PORTABLE.md](patcher/java-cli/PORTABLE.md) for the exact commands and hashes.
These recipes rebuild resources and audio; the source-patch bundle, skeleton
and overlay come with Setup. Copyright notices and third-party licenses are
retained in the packaged files. Historical build-status fields in an input
manifest are not licenses or proof of gameplay testing.

If the GUI reports an incomplete installation, reinstall the complete Setup
JAR into a new folder. Do not copy random ZIPs into Advanced or replace the
expected hashes to get past validation. Existing source projects should be
kept separately; reinstalling the patcher does not require deleting them.

`create-dev` uses Java 17 and produces editable source without compiling the
game. `build-standalone` also needs Java 25 and Node/npm. Input hashes must match
the versions pinned in the patcher.

Only the Normal client is supported. By default, HTML builds put music in a
resource-pack ZIP beside the client; import that ZIP in-game. Embedding music
produces a larger HTML file. Wispcraft is optional and uses a local script.

Decompiled game source, generated binaries, caches, and third-party mod
binaries are also excluded from this repository.

Use only input files you are entitled to use. This source folder has no license
file and does not grant permission to reuse the code or any external inputs.
