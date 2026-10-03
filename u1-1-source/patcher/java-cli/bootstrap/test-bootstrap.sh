#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
FIXTURES=$SCRIPT_DIR/test-fixtures
REAL_GUI_JAR=${1:-}
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/eagler-bootstrap-test.XXXXXXXX")
cleanup() {
  rm -rf -- "$TEST_ROOT"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

MOCK_BIN=$TEST_ROOT/mock-bin
mkdir -p "$MOCK_BIN"
ln -s "$FIXTURES/mock-uname.sh" "$MOCK_BIN/uname"
ln -s "$FIXTURES/mock-id.sh" "$MOCK_BIN/id"
ln -s "$FIXTURES/mock-curl.sh" "$MOCK_BIN/curl"
ln -s "$FIXTURES/mock-tar.sh" "$MOCK_BIN/tar"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  expected=$1
  actual=$2
  case "$actual" in
    *"$expected"*) ;;
    *) fail "expected output to contain '$expected'; got: $actual" ;;
  esac
}

APP_DIR=$TEST_ROOT/'app = with \ slash ✓'
export MOCK_FIXTURE_DIR=$FIXTURES
export MOCK_CURL_LOG=$TEST_ROOT/curl.log
export PATH=$MOCK_BIN:/usr/bin:/bin
export MOCK_OS=Linux

printf 'Running mocked Linux x86_64 installation...\n'
"$SCRIPT_DIR/bootstrap-linux-x86_64.sh" --app-dir "$APP_DIR" >"$TEST_ROOT/install.log" 2>&1 \
  || { sed -n '1,220p' "$TEST_ROOT/install.log" >&2; fail 'bootstrap install failed'; }
assert_contains 'App-local toolchain is ready.' "$(cat "$TEST_ROOT/install.log")"
[ -f "$APP_DIR/toolchain.properties" ] || fail 'toolchain.properties was not written'
JAVA17_TAG=$(sha256sum "$FIXTURES/temurin17.archive" | awk '{print substr($1, 1, 12)}')
JAVA25_TAG=$(sha256sum "$FIXTURES/temurin25.archive" | awk '{print substr($1, 1, 12)}')
NODE_TAG=$(sha256sum "$FIXTURES/node24.archive" | awk '{print substr($1, 1, 12)}')
[ -x "$APP_DIR/.toolchain/temurin17-17.0.20+1-$JAVA17_TAG/bin/java" ] || fail 'Java 17 was not installed under the app-local directory'
[ -x "$APP_DIR/.toolchain/temurin25-25.0.4+1-$JAVA25_TAG/bin/java" ] || fail 'Java 25 was not installed under the app-local directory'
[ -x "$APP_DIR/.toolchain/node-v24.21.0-linux-x64-$NODE_TAG/bin/node" ] || fail 'Node.js was not installed under the app-local directory'
grep -F 'platform=linux-x86_64' "$APP_DIR/toolchain.properties" >/dev/null || fail 'platform was not recorded'
grep -F 'java17=/tmp/' "$APP_DIR/toolchain.properties" >/dev/null || fail 'Java path was not recorded'
grep -F '\=' "$APP_DIR/toolchain.properties" >/dev/null || fail 'Java properties escaping for equals was not emitted'
grep -F '\\' "$APP_DIR/toolchain.properties" >/dev/null || fail 'Java properties escaping for backslash was not emitted'

first_count=$(wc -l < "$MOCK_CURL_LOG")
printf 'Checking cached re-use without additional downloads...\n'
"$SCRIPT_DIR/bootstrap-linux-x86_64.sh" --app-dir "$APP_DIR" >"$TEST_ROOT/reuse.log" 2>&1 \
  || { sed -n '1,220p' "$TEST_ROOT/reuse.log" >&2; fail 'idempotent bootstrap run failed'; }
assert_contains 'already installed' "$(cat "$TEST_ROOT/reuse.log")"
second_count=$(wc -l < "$MOCK_CURL_LOG")
[ "$first_count" -eq "$second_count" ] || fail 'a valid cached toolchain unexpectedly triggered vendor downloads'

printf 'Checking launcher bootstraps then starts the GUI JAR...\n'
GUI_JAR=$APP_DIR/eaglercraft-26.2-u1-patcher-gui.jar
: > "$GUI_JAR"
export MOCK_LAUNCH_LOG=$TEST_ROOT/launch.log
"$SCRIPT_DIR/launch-gui-linux-x86_64.sh" --app-dir "$APP_DIR" --self-test >"$TEST_ROOT/launch-output.log" 2>&1 \
  || { sed -n '1,220p' "$TEST_ROOT/launch-output.log" >&2; fail 'GUI launcher failed'; }
assert_contains "-jar $GUI_JAR --self-test" "$(cat "$MOCK_LAUNCH_LOG")"

if [ -n "$REAL_GUI_JAR" ]; then
  printf 'Checking real GUI discovery of bootstrapped tools...\n'
  cp -- "$REAL_GUI_JAR" "$GUI_JAR"
  mkdir -p "$TEST_ROOT/gui-contract"
  javac --release 17 -cp "$GUI_JAR" -d "$TEST_ROOT/gui-contract" \
    "$SCRIPT_DIR/../src/test/java/com/eaglercraft/patcher/GuiBundledToolsContractTest.java"
  java -cp "$GUI_JAR:$TEST_ROOT/gui-contract" \
    com.eaglercraft.patcher.GuiBundledToolsContractTest "$APP_DIR"
fi

printf 'Checking digest mismatch stops before install...\n'
BAD_APP_DIR=$TEST_ROOT/bad-app
export MOCK_TAMPER_ARCHIVE=node
if "$SCRIPT_DIR/bootstrap-linux-x86_64.sh" --app-dir "$BAD_APP_DIR" >"$TEST_ROOT/tamper.log" 2>&1; then
  fail 'tampered vendor archive was accepted'
fi
unset MOCK_TAMPER_ARCHIVE
assert_contains 'SHA-256 mismatch' "$(cat "$TEST_ROOT/tamper.log")"
[ ! -f "$BAD_APP_DIR/toolchain.properties" ] || fail 'manifest was written after a checksum mismatch'
[ "$(find "$BAD_APP_DIR/.toolchain" -mindepth 1 ! -name .bootstrap.lock -print | wc -l)" -eq 0 ] || fail 'partial toolchains were installed after a checksum mismatch'

printf 'Checking two simultaneous launches share one install...\n'
CONCURRENT_APP=$TEST_ROOT/concurrent-app
"$SCRIPT_DIR/bootstrap-linux-x86_64.sh" --app-dir "$CONCURRENT_APP" >"$TEST_ROOT/concurrent-1.log" 2>&1 &
first_pid=$!
"$SCRIPT_DIR/bootstrap-linux-x86_64.sh" --app-dir "$CONCURRENT_APP" >"$TEST_ROOT/concurrent-2.log" 2>&1 &
second_pid=$!
wait "$first_pid" || fail 'first concurrent launch failed'
wait "$second_pid" || fail 'second concurrent launch failed'
[ -f "$CONCURRENT_APP/toolchain.properties" ] || fail 'concurrent launch did not publish a manifest'
[ "$(find "$CONCURRENT_APP/.toolchain" -mindepth 1 -maxdepth 1 -type d | wc -l)" -eq 3 ] \
  || fail 'concurrent launch left nested or partial installs'

for unsupported in Darwin MINGW64_NT-10.0; do
  unsupported_dir=$TEST_ROOT/unsupported-$unsupported
  export MOCK_OS=$unsupported
  if "$SCRIPT_DIR/bootstrap-linux-x86_64.sh" --app-dir "$unsupported_dir" >"$TEST_ROOT/platform.log" 2>&1; then
    fail "unsupported platform $unsupported was accepted"
  fi
  case "$unsupported" in
    Darwin) assert_contains 'macOS bootstrap is not implemented' "$(cat "$TEST_ROOT/platform.log")" ;;
    *) assert_contains 'Windows bootstrap is not implemented' "$(cat "$TEST_ROOT/platform.log")" ;;
  esac
  [ ! -e "$unsupported_dir" ] || fail "unsupported platform $unsupported changed the filesystem"
done

printf 'PASS: app-local paths, verified archive hashes, checksum rejection, cached reuse, launcher, and unsupported-platform diagnostics.\n'
