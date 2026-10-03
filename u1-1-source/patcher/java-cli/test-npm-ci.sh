#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
node_bin=$(command -v node)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/eagler-patcher-npm.XXXXXX")
cleanup() { rm -rf "$test_dir"; }
trap cleanup EXIT

workspace="$test_dir/workspace"
mkdir -p "$workspace"
printf '{"name":"fixture","private":true}\n' > "$workspace/package.json"
printf '{"name":"fixture","lockfileVersion":3,"requires":true,"packages":{"":{}}}\n' \
  > "$workspace/package-lock.json"

fake_npm="$test_dir/npm-cli.js"
printf '%s\n' \
  '"use strict";' \
  'const fs = require("node:fs");' \
  'const path = require("node:path");' \
  'if (process.argv[2] === "--version") { console.log("10.9.4"); process.exit(0); }' \
  'const expected = ["ci", "--ignore-scripts", "--no-audit", "--no-fund"];' \
  'if (JSON.stringify(process.argv.slice(2)) !== JSON.stringify(expected)) process.exit(23);' \
  'const out = path.join(process.cwd(), "node_modules/brotli-dec-wasm/pkg");' \
  'fs.mkdirSync(out, { recursive: true });' \
  'fs.writeFileSync(path.join(out, "brotli_dec_wasm.js"), "fixture\n");' \
  'fs.writeFileSync(path.join(out, "brotli_dec_wasm_bg.wasm"), "fixture\n");' \
  'fs.writeFileSync(path.join(process.cwd(), "observed-args.json"), JSON.stringify(process.argv.slice(2)));' \
  'console.log("fake npm ci completed");' \
  > "$fake_npm"

"$root_dir/build.sh" >/dev/null
/usr/lib/jvm/java-17-openjdk-amd64/bin/jshell -q \
  --class-path "$root_dir/build/eaglercraft-26.2-java-cli.jar" <<EOF >/dev/null
import java.lang.reflect.*; import java.nio.file.*;
var type = Class.forName("com.eaglercraft.patcher.Main");
var method = type.getDeclaredMethod("runPinnedNpmCi", Path.class, Path.class, Path.class, int.class, long.class);
method.setAccessible(true);
method.invoke(null, Path.of("$node_bin"), Path.of("$fake_npm"), Path.of("$workspace"), 30, System.nanoTime());
EOF

grep -Fqx '["ci","--ignore-scripts","--no-audit","--no-fund"]' "$workspace/observed-args.json"
test -s "$workspace/npm-ci.log"
test -f "$workspace/node_modules/brotli-dec-wasm/pkg/brotli_dec_wasm.js"
test -f "$workspace/node_modules/brotli-dec-wasm/pkg/brotli_dec_wasm_bg.wasm"

timeout_workspace="$test_dir/timeout-workspace"
mkdir -p "$timeout_workspace"
cp "$workspace/package.json" "$workspace/package-lock.json" "$timeout_workspace/"
timeout_npm="$test_dir/timeout-npm-cli.js"
printf '%s\n' \
  '"use strict";' \
  'const fs = require("node:fs");' \
  'if (process.argv[2] === "--version") { console.log("10.9.4"); process.exit(0); }' \
  'fs.mkdirSync("node_modules/partial", { recursive: true });' \
  'setTimeout(() => {}, 60000);' \
  > "$timeout_npm"

/usr/lib/jvm/java-17-openjdk-amd64/bin/jshell -q \
  --class-path "$root_dir/build/eaglercraft-26.2-java-cli.jar" <<EOF >/dev/null
import java.lang.reflect.*; import java.nio.file.*;
var type = Class.forName("com.eaglercraft.patcher.Main");
var method = type.getDeclaredMethod("runPinnedNpmCi", Path.class, Path.class, Path.class, int.class, long.class);
method.setAccessible(true);
try {
  method.invoke(null, Path.of("$node_bin"), Path.of("$timeout_npm"), Path.of("$timeout_workspace"), 1, System.nanoTime());
  throw new RuntimeException("timeout fixture unexpectedly succeeded");
} catch (InvocationTargetException expected) {
  if (!expected.getCause().getMessage().contains("timed out after 1 seconds")) throw expected;
}
EOF
test ! -e "$timeout_workspace/node_modules"
printf 'npm ci integration: PASS (explicit Node/npm, exact safe args, pinned lock preserved, decoder outputs required, timeout cleans partial install)\n'
