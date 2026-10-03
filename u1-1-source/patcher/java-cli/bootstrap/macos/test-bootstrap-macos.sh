#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd -P)
FIXTURES=$SCRIPT_DIR/test-fixtures
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/eagler-macos-bootstrap-test.XXXXXX")
cleanup() { rm -rf "$TEST_ROOT"; }
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

MOCK_BIN=$TEST_ROOT/mock-bin
mkdir -p "$MOCK_BIN"
ln -s "$FIXTURES/mock-uname.sh" "$MOCK_BIN/uname"
ln -s "$FIXTURES/mock-sw_vers.sh" "$MOCK_BIN/sw_vers"
ln -s "$FIXTURES/mock-id.sh" "$MOCK_BIN/id"
ln -s "$FIXTURES/mock-curl.sh" "$MOCK_BIN/curl"
ln -s "$FIXTURES/mock-tar.sh" "$MOCK_BIN/tar"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_contains() {
  expected=$1
  actual=$2
  case "$actual" in *"$expected"*) ;; *) fail "expected output to contain '$expected'; got: $actual" ;; esac
}
run_bootstrap() {
  app=$1
  log=$2
  "$SCRIPT_DIR/bootstrap-macos.sh" --app-dir "$app" > "$log" 2>&1 \
    || { sed -n '1,220p' "$log" >&2; fail "bootstrap failed for $MOCK_ARCH"; }
}

export MOCK_FIXTURE_DIR=$FIXTURES
export MOCK_CURL_LOG=$TEST_ROOT/curl.log
export PATH=$MOCK_BIN:/usr/bin:/bin
export MOCK_OS=Darwin
export MOCK_MAC_VERSION=14.7.6
export MOCK_UID=501

for MOCK_ARCH in arm64 x86_64; do
  export MOCK_ARCH
  app=$TEST_ROOT/"portable = app \\ $MOCK_ARCH ✓"
  printf 'Checking mocked macOS %s install and cache reuse...\n' "$MOCK_ARCH"
  run_bootstrap "$app" "$TEST_ROOT/install-$MOCK_ARCH.log"
  assert_contains 'App-local toolchain is ready.' "$(cat "$TEST_ROOT/install-$MOCK_ARCH.log")"
  properties=$app/toolchain.properties
  [ -f "$properties" ] || fail "toolchain.properties missing for $MOCK_ARCH"
  case "$MOCK_ARCH" in arm64) platform=macos-arm64; node_arch=arm64 ;; *) platform=macos-x86_64; node_arch=x64 ;; esac
  grep -F "platform=$platform" "$properties" >/dev/null || fail "platform missing for $MOCK_ARCH"
  grep -F 'java17=' "$properties" >/dev/null || fail "Java 17 path missing for $MOCK_ARCH"
  grep -F 'java25=' "$properties" >/dev/null || fail "Java 25 path missing for $MOCK_ARCH"
  grep -F '\=' "$properties" >/dev/null || fail 'Java properties equals escaping was not emitted'
  grep -F '\\' "$properties" >/dev/null || fail 'Java properties backslash escaping was not emitted'
  decode_property() {
    sed -n "s/^$1=//p" "$properties" | sed 's/\\=/=/g; s/\\\\/\\/g'
  }
  java17=$(sed -n 's/^java17=//p' "$properties" | sed 's/\\=/=/g; s/\\\\/\\/g')
  java25=$(decode_property java25)
  node=$(decode_property node)
  npm=$(decode_property npm)
  [ -x "$java17" ] || fail "decoded Java 17 path is invalid for $MOCK_ARCH"
  [ -x "$java25" ] || fail "decoded Java 25 path is invalid for $MOCK_ARCH"
  case "$node" in "$app/.toolchain/node-v24.21.0-darwin-$node_arch-"*) ;; *) fail "Node path or architecture missing for $MOCK_ARCH" ;; esac
  case "$npm" in "$app/.toolchain/"*/npm-cli.js) ;; *) fail "npm path does not name app-local npm-cli.js" ;; esac
  before=$(wc -l < "$MOCK_CURL_LOG")
  run_bootstrap "$app" "$TEST_ROOT/reuse-$MOCK_ARCH.log"
  assert_contains 'already installed' "$(cat "$TEST_ROOT/reuse-$MOCK_ARCH.log")"
  after=$(wc -l < "$MOCK_CURL_LOG")
  [ "$before" -eq "$after" ] || fail "cached $MOCK_ARCH install unexpectedly downloaded vendor files"
done

printf 'Checking the GUI launcher starts Java from toolchain.properties...\n'
LAUNCH_APP=$TEST_ROOT/'launch app'
mkdir -p "$LAUNCH_APP"
: > "$LAUNCH_APP/eaglercraft-26.2-u1-patcher-gui.jar"
export MOCK_ARCH=arm64
export MOCK_LAUNCH_LOG=$TEST_ROOT/launch.log
"$SCRIPT_DIR/launch-gui-macos.sh" --app-dir "$LAUNCH_APP" --self-test > "$TEST_ROOT/launch-output.log" 2>&1 \
  || { sed -n '1,220p' "$TEST_ROOT/launch-output.log" >&2; fail 'GUI launcher failed'; }
assert_contains "-jar $LAUNCH_APP/eaglercraft-26.2-u1-patcher-gui.jar --self-test" "$(cat "$MOCK_LAUNCH_LOG")"

printf 'Checking dead same-host lock recovery and concurrent serialization...\n'
STALE_APP=$TEST_ROOT/stale-lock-app
mkdir -p "$STALE_APP/.toolchain/.bootstrap.lock"
printf '99999999 %s\n' "$(hostname)" > "$STALE_APP/.toolchain/.bootstrap.lock/owner"
run_bootstrap "$STALE_APP" "$TEST_ROOT/stale-lock.log"
assert_contains 'App-local toolchain is ready.' "$(cat "$TEST_ROOT/stale-lock.log")"

CONCURRENT_APP=$TEST_ROOT/concurrent-app
export MOCK_DELAY=1
"$SCRIPT_DIR/bootstrap-macos.sh" --app-dir "$CONCURRENT_APP" > "$TEST_ROOT/concurrent-1.log" 2>&1 &
first_pid=$!
"$SCRIPT_DIR/bootstrap-macos.sh" --app-dir "$CONCURRENT_APP" > "$TEST_ROOT/concurrent-2.log" 2>&1 &
second_pid=$!
wait "$first_pid" || { sed -n '1,220p' "$TEST_ROOT/concurrent-1.log" >&2; fail 'first concurrent bootstrap failed'; }
wait "$second_pid" || { sed -n '1,220p' "$TEST_ROOT/concurrent-2.log" >&2; fail 'second concurrent bootstrap failed'; }
unset MOCK_DELAY
[ -f "$CONCURRENT_APP/toolchain.properties" ] || fail 'concurrent bootstrap did not publish toolchain.properties'
concurrent_count=0
for entry in "$CONCURRENT_APP"/.toolchain/*; do
  [ -d "$entry" ] && concurrent_count=$((concurrent_count + 1))
done
[ "$concurrent_count" -eq 3 ] || fail "concurrent bootstrap left $concurrent_count app-local toolchain directories, expected 3"

printf 'Checking checksum mismatch and unsafe archive rejection...\n'
BAD_APP=$TEST_ROOT/bad-checksum-app
export MOCK_TAMPER=node
if "$SCRIPT_DIR/bootstrap-macos.sh" --app-dir "$BAD_APP" > "$TEST_ROOT/tamper.log" 2>&1; then
  fail 'tampered Node archive was accepted'
fi
unset MOCK_TAMPER
assert_contains 'SHA-256 mismatch' "$(cat "$TEST_ROOT/tamper.log")"
[ ! -f "$BAD_APP/toolchain.properties" ] || fail 'manifest was written after checksum mismatch'
[ "$(find "$BAD_APP/.toolchain" -type f -print | wc -l)" -eq 0 ] \
  || fail 'partial tools remained after checksum mismatch'

UNSAFE_APP=$TEST_ROOT/unsafe-archive-app
export MOCK_UNSAFE_TAR=1
if "$SCRIPT_DIR/bootstrap-macos.sh" --app-dir "$UNSAFE_APP" > "$TEST_ROOT/unsafe.log" 2>&1; then
  fail 'archive with a parent traversal member was accepted'
fi
unset MOCK_UNSAFE_TAR
assert_contains 'unsafe archive path' "$(cat "$TEST_ROOT/unsafe.log")"
[ ! -f "$UNSAFE_APP/toolchain.properties" ] || fail 'manifest was written after unsafe archive rejection'

UNSAFE_LINK_APP=$TEST_ROOT/unsafe-link-app
export MOCK_UNSAFE_LINK=1
if "$SCRIPT_DIR/bootstrap-macos.sh" --app-dir "$UNSAFE_LINK_APP" > "$TEST_ROOT/unsafe-link.log" 2>&1; then
  fail 'archive with an escaping symbolic link was accepted'
fi
unset MOCK_UNSAFE_LINK
assert_contains 'symbolic or hard link outside' "$(cat "$TEST_ROOT/unsafe-link.log")"
[ ! -f "$UNSAFE_LINK_APP/toolchain.properties" ] || fail 'manifest was written after unsafe symbolic-link rejection'

printf 'Checking unsupported host/version/architecture/rights fail before writes...\n'
for scenario in linux old-macos unsupported-arch root; do
  app=$TEST_ROOT/unsupported-$scenario
  case "$scenario" in
    linux) export MOCK_OS=Linux ;;
    old-macos) export MOCK_OS=Darwin MOCK_MAC_VERSION=12.7.6 ;;
    unsupported-arch) export MOCK_OS=Darwin MOCK_MAC_VERSION=14.7.6 MOCK_ARCH=ppc64 ;;
    root) export MOCK_OS=Darwin MOCK_MAC_VERSION=14.7.6 MOCK_ARCH=arm64 MOCK_UID=0 ;;
  esac
  if "$SCRIPT_DIR/bootstrap-macos.sh" --app-dir "$app" > "$TEST_ROOT/$scenario.log" 2>&1; then
    fail "unsupported $scenario host was accepted"
  fi
  [ ! -e "$app" ] || fail "unsupported $scenario host changed the filesystem"
done
assert_contains 'macOS only' "$(cat "$TEST_ROOT/linux.log")"
assert_contains 'macOS 13 Ventura or newer' "$(cat "$TEST_ROOT/old-macos.log")"
assert_contains 'unsupported macOS architecture' "$(cat "$TEST_ROOT/unsupported-arch.log")"
assert_contains 'normal user' "$(cat "$TEST_ROOT/root.log")"

printf 'PASS: macOS architecture contracts, vendor SHA verification, safe extraction checks, app-local cache/lock, launcher wiring, and explicit unsupported boundaries.\n'
