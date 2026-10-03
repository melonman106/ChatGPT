# 26.2 patcher command-line tools

For the desktop app, start with [GUI.md](GUI.md). The complete Setup JAR extracts
its build inputs and the GUI selects them automatically. The explicit paths and
hashes below are for command-line use and package development; desktop users
do not need to enter them.

This is the Java source-reconstruction part of the 26.2 patcher. `create-dev`
accepts the official 26.2 client
JAR, verifies its pinned identity and metadata, checks the ZIP entry paths,
launches the pinned Vineflower 1.12.0 with a Java 17 runtime, verifies the
generated Java manifest (`7,055` files), and
applies the externally hash-pinned typed source bundle.

The u1.1 skeleton uses Netty NIO for browser remote traffic and LocalChannel
for integrated traffic, so TeaVM no longer links the desktop-only epoll/kqueue
transport classes. Normal-only scope is unchanged.

`create-dev` reconstructs Java source and can make a local Gradle workspace
when given the authenticated skeleton and resource overlay. `build-standalone`
adds Java 25, Node.js, and npm tool checks. It invokes the selected npm CLI
through the selected Node executable as `npm ci --ignore-scripts --no-audit
--no-fund`, records the tool and lock hashes, and then invokes the standalone
builder with staged output and elapsed-time reporting. The v5 skeleton contains
the bounded Node/linker substrate. Supply `sounds.epk` with its SHA-256; the CLI
rehashes it and stages only those authenticated bytes. It never finds or copies
a pack from this checkout.

The accepted deterministic u1 Netty-fix skeleton has 788 payload files, archive
SHA-256 `a9172bdd903ea65afd4c87cdb29b500c237c823fdd94789c2e90561b1b049469`.
Its exported sidecar manifest is pinned to SHA-256
`be269c8f139cc5faaa45e9c44700e89a54ba602114eee3d981b53e4ab6642ff2`; the ZIP
contains the same manifest in compact canonical form with member SHA-256
`29debc98a2af488464d6ec813531f879f2c3c399ec8fbdba3c18378a4adc3d86`.
After extraction, the source-side notice inventory must be present at
`third_party/skeleton/THIRD_PARTY_NOTICES.md`; this inventory does not by
itself establish release clearance.

`build-standalone` creates the patched project before building its HTML, so a
separate `create-dev` run is not required. To reuse an earlier project, add
`--reuse-project` and keep `--output` pointed at that project. Reuse is limited
to CLI-created projects with a valid receipt and required layout; receipt,
input pins, and build files are checked before any build work. The project
source and receipt are preserved.

If that project already contains a precise standalone client Wasm output and
its source and toolchain inputs are not newer than the output, add
`--reuse-client` as well. The builder checks that freshness before skipping the
client link; other standalone work, including the mesh worker, still runs.

Current Wasm builds require at least 10 GiB of detected build-memory budget,
plus memory reserved for the operating system (a 16 GiB machine is typically
the practical minimum). The CLI checks memory before compiling and refuses an
insufficient budget instead of risking an out-of-memory failure. Less memory
can make the build slower and does not guarantee completion.

Allow about 30 minutes on a reasonably capable PC. A full standalone build can
take longer than an hour; the patcher allows up to two hours before stopping
it. Slower hardware, dependency downloads, and memory pressure can add time.
The GUI shows elapsed time, not a countdown.

The launcher build uses Java release 17 and a fixed JAR entry timestamp.
Repeating `build.sh` with the same source and toolchain produces the same JAR.
`build/receipt.json` records the JAR SHA-256 and canonical source-manifest
SHA-256. Java option environment variables are removed from child processes;
the receipt records that scrub explicitly.

Pass the JAR, Vineflower JAR, and Java executable as input paths. The Java runtime
must report version 17; its executable and available `lib/modules` image hashes
are recorded for provenance, while the exact baseline source manifest remains
the acceptance gate across runtime builds. Nothing is copied into this directory.
New output paths must be absent or empty; an existing project is used only with
the explicit `build-standalone --reuse-project` option. Failed new-output runs
clean their staging directory and never promote a partial result.

## Build and run

```sh
./build.sh
./run.sh create-dev \
  --jar /path/to/minecraft-26.2-client.jar \
  --output /tmp/eagler-26.2-dev \
  --vineflower /path/to/vineflower-1.12.0.jar \
  --java17 /path/to/java17 \
  --patch-bundle /path/to/source-patch-bundle.zip \
  --expected-bundle-sha256 fd944e9cabbebbf4bddce8a39e1b233b3c7d0cd4f860f9cae37550a3fcfa8b02
```

Create a small launcher directory (it contains no Mojang files):

```sh
./make-portable.sh /path/to/eaglercraft-26.2-u1-patcher
```

The portable directory supports `create-dev` and `build-standalone`.

The Java CLI JAR also contains `com.eaglercraft.patcher.OfficialResources`.
With `zh-cn-delta-26.2.json` beside the kit, it can fetch the pinned 26.2
asset index and reconstruct the six files expected by the resource overlay:

```sh
java -cp eaglercraft-26.2-java-cli.jar \
  com.eaglercraft.patcher.OfficialResources \
  /path/to/new-resource-folder inputs/zh-cn-delta-26.2.json
```

The folder must not exist yet. Kits containing a source bundle include the
pinned delta under `inputs/`. An optional cached index and `objects` directory
may follow the delta path. The downloaded objects, derived catalog, and symbol
ZIP are checked against the overlay's exact hashes. The GUI can fetch these
resources under Advanced. If the resource overlay is set and the resources
folder is blank, Build fetches them before continuing.

Portable kits also include `tools/audio/`. It can fetch the official indexed
sound objects and rebuild both audio archives without the old EPK files. Fetching
needs Python 3; rebuilding needs Node 20+ and FFmpeg 6.1.1. The GUI's **Get
game audio** button uses the bundled Node recipe and downloads the official
objects itself; FFmpeg 6.1.1 must still be supplied separately. The manual
commands from the kit folder are:

```sh
python3 tools/audio/fetch_audio_objects.py \
  --objects inputs/audio-cache/objects \
  --save-index inputs/audio-cache/indexes/32.json \
  --output inputs/audio-cache/fetch-receipt
node tools/audio/node_audio_rebuild.mjs \
  --metadata tools/audio/audio-epk-metadata-26.2.json \
  --index inputs/audio-cache/indexes/32.json \
  --objects inputs/audio-cache/objects \
  --ffmpeg /absolute/path/to/ffmpeg-6.1.1 \
  --output inputs/audio-cache/epk-rebuild \
  --group both
```

The first command verifies the official index and all 4,871 sound objects and
can resume after interruption. The second checks the bundled metadata and
FFmpeg version, then compares the rebuilt EPKs to fixed SHA-256 hashes. Use
`inputs/audio-cache/epk-rebuild/{sounds,music}.epk` as local CLI inputs only
after its final receipt says `exact` for both. A fresh download and conversion
take additional time beyond the approximate 30-minute game build.

`build-standalone` takes these additional inputs:

```sh
  --java25 /path/to/java-25 \
  --node /path/to/node \
  --npm /path/to/npm-cli.js-or-npm-launcher \
  --sounds-epk /path/to/locally-authorized-sounds.epk \
  --expected-sounds-epk-sha256 <sha256> \
  --music-epk /path/to/locally-authorized-music.epk \
  --expected-music-epk-sha256 <sha256> \
  --standalone-output /path/to/eaglercraft-26.2-u1.html \
  --music-pack-output /path/to/eaglercraft-26.2-u1-music-resource-pack.zip
```

The lean HTML keeps music out of the file and builds the named resource-pack
ZIP alongside it. Import that ZIP in-game; it is not fetched automatically.
Use `--with-music` in place of `--music-pack-output` for a larger HTML with
music embedded. Optional `--wispcraft-script /path/to/dist/index.js` injects
the caller's local script into the HTML; the patcher does not download it.

When building from an existing project, include `--reuse-project` in the same
command, for example:

```sh
./run.sh build-standalone \
  --jar /path/to/minecraft-26.2-client.jar \
  --output /path/to/previously-created-project \
  --reuse-project \
  --vineflower /path/to/vineflower-1.12.0.jar \
  --java17 /path/to/java17 \
  --patch-bundle /path/to/source-patch-bundle.zip \
  --expected-bundle-sha256 fd944e9cabbebbf4bddce8a39e1b233b3c7d0cd4f860f9cae37550a3fcfa8b02 \
  --project-skeleton /path/to/project-skeleton-u1-nettyfix.zip \
  --expected-skeleton-sha256 a9172bdd903ea65afd4c87cdb29b500c237c823fdd94789c2e90561b1b049469 \
  --resource-overlay /path/to/resource-overlay-normal.zip \
  --expected-resource-overlay-sha256 2ba7e3376891c64f8bf57f3687e05b8dbe1971a75475b6825449e5e5f96d71f3 \
  --external-resource-root /path/to/external-resource-root
```

This patcher builds the Normal client. Test the exported HTML in your browser
before sharing it.

To seed the reconstructed source into the separately verified Gradle project
skeleton, add both archive arguments. The skeleton is optional for `create-dev`
and mandatory for `build-standalone`; it is rejected unless the complete
archive hash matches the caller-supplied identity:

```sh
  --project-skeleton /path/to/project-skeleton-u1-nettyfix.zip \
  --expected-skeleton-sha256 a9172bdd903ea65afd4c87cdb29b500c237c823fdd94789c2e90561b1b049469
```

The local-test-only resource overlay is another optional authenticated input.
It reconstructs the 19,497 official-JAR resources to the reviewed 19,515-file
tree (`ced3f0610dbfb18f8ecebfee5501df0da0036b8ad64ba7beb93f592aa9a1a8df`)
and requires the external root containing the six exact-hash local files,
including `assets/minecraft/lang/zh_cn.json` at SHA-256
`47d66d5b25a5ff1c40a4a6a179b44b165517af1863617ecac5cd03e751f49ff2`:

```sh
  --resource-overlay /path/to/resource-overlay-normal.zip \
  --expected-resource-overlay-sha256 2ba7e3376891c64f8bf57f3687e05b8dbe1971a75475b6825449e5e5f96d71f3 \
  --external-resource-root /path/to/external-resource-root
```

With the skeleton and overlay both verified, the receipt reports
`dev-workspace-ready`. The overlay remains `local-test-only`; unresolved
provenance and redistribution questions mean this is not a release or EPK
acceptance claim.

The output contains the decompiled `source/`, patched
`game/src/main/java/`, `baseline-manifest.json`, the Vineflower log, and a JSON
receipt. The expected source manifest was measured from the checked-in 26.2
baseline; the CLI embeds its digest and rejects any different output. The
final manifest is checked at 7,142 Java files (source bundle SHA-256
`fd944e9cabbebbf4bddce8a39e1b233b3c7d0cd4f860f9cae37550a3fcfa8b02`, source
manifest SHA-256 `a5924750314f5de9316e9f67b28ce1decee31e27d67b260760b8176cd269243a`). Non-Java files produced by
Vineflower are retained with the development source, but are not part of the
Java baseline digest.

## Focused acceptance test

The test runs one positive decompile and then checks a byte-tampered JAR. The
tampered case must fail before Vineflower and must not create output.

```sh
./test.sh \
  --jar /path/to/minecraft-26.2-client.jar \
  --vineflower /path/to/vineflower-1.12.0.jar \
  --java17 /path/to/java17 \
  --patch-bundle /path/to/source-patch-bundle.zip \
  --expected-bundle-sha256 fd944e9cabbebbf4bddce8a39e1b233b3c7d0cd4f860f9cae37550a3fcfa8b02
```

The focused version parser regression can be run without decompiling:

```sh
./test-version-line.sh
```

The resource applicator can be exercised without decompiling:

```sh
./test-resource-overlay.sh \
  --jar /path/to/minecraft-26.2-client.jar \
  --overlay /path/to/resource-overlay-normal.zip \
  --expected-overlay-sha256 2ba7e3376891c64f8bf57f3687e05b8dbe1971a75475b6825449e5e5f96d71f3 \
  --external-root /path/to/external-resource-root \
  --patch-bundle /path/to/source-patch-bundle.zip \
  <expected-source-bundle-sha256>
```

The receipt is written to `output/java-cli-source-acceptance-20260924/` by the
test script. The test does not copy the official JAR into the repository.

For a focused patch-engine run without decompiling:

```sh
./run.sh apply-patch \
  --base-source /path/to/decompiled-java \
  --output /tmp/patched-java \
  --patch-bundle /path/to/source-patch-bundle.zip \
  --expected-bundle-sha256 fd944e9cabbebbf4bddce8a39e1b233b3c7d0cd4f860f9cae37550a3fcfa8b02
```
