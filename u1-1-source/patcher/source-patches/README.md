# Source patch exporter

The exporter compares two Java trees and writes a deterministic bundle. It
stores new files in full, modifications as byte-span deltas, and the prior
identity for deleted files. Manifests and hashes bind each operation to both
trees. The verifier rejects unsafe paths, unexpected archive members, and a
changed base tree.

The script uses only the Python standard library. To export a bundle, supply
two Java source trees you are authorized to use:

```sh
python3 patcher/source-patches/export_source_patches.py export \
  --base /path/to/base-java \
  --final /path/to/changed-java \
  --output /tmp/source-patch \
  --archive /tmp/source-patch.zip
```

Verify the archive against the base tree:

```sh
python3 patcher/source-patches/export_source_patches.py verify \
  --bundle /tmp/source-patch.zip \
  --base /path/to/base-java \
  --expected-bundle-sha256 <sha256>
```

Apply the archive to a new output directory:

```sh
python3 patcher/source-patches/export_source_patches.py apply \
  --bundle /tmp/source-patch.zip \
  --base /path/to/base-java \
  --output /tmp/reconstructed-java \
  --expected-bundle-sha256 <sha256>
```

`test_export_source_patches.py` checks deterministic export, reconstruction,
tamper rejection, and path validation. It needs the same base and final trees,
which are not included. Use `--require-frozen` only with the accepted frozen
trees; the test also checks their manifest hashes and operation counts.

The generated bundle is not included. The accepted bundle contains
game-derived Java changes that are not cleared for redistribution. The
exporter documents the bundle format without including those changes.
