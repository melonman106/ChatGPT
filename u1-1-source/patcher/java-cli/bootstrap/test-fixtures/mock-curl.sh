#!/usr/bin/env sh
set -eu

output=
url=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --output) output=$2; shift 2 ;;
    --connect-timeout|--max-time|--proto|--proto-redir) shift 2 ;;
    --*) shift ;;
    *) url=$1; shift ;;
  esac
done
[ -n "$output" ] && [ -n "$url" ] || { printf 'mock-curl: missing output or URL\n' >&2; exit 2; }
printf '%s\n' "$url" >> "$MOCK_CURL_LOG"
archive_hash() {
  sha256sum "$MOCK_FIXTURE_DIR/$1" | awk '{print $1}'
}
case "$url" in
  'https://api.adoptium.net/v3/assets/latest/17/hotspot?'*)
    sed "s/@SHA@/$(archive_hash temurin17.archive)/" "$MOCK_FIXTURE_DIR/java17.json.in" > "$output"
    ;;
  'https://api.adoptium.net/v3/assets/latest/25/hotspot?'*)
    sed "s/@SHA@/$(archive_hash temurin25.archive)/" "$MOCK_FIXTURE_DIR/java25.json.in" > "$output"
    ;;
  'https://github.com/adoptium/temurin17-binaries/releases/download/'*.sha256.txt)
    printf '%s  OpenJDK17U-jdk_x64_linux_hotspot_17.0.20_1.tar.gz\n' "$(archive_hash temurin17.archive)" > "$output"
    ;;
  'https://github.com/adoptium/temurin25-binaries/releases/download/'*.sha256.txt)
    printf '%s  OpenJDK25U-jdk_x64_linux_hotspot_25.0.4_1.tar.gz\n' "$(archive_hash temurin25.archive)" > "$output"
    ;;
  'https://github.com/adoptium/temurin17-binaries/releases/download/'*.tar.gz)
    cp "$MOCK_FIXTURE_DIR/temurin17.archive" "$output"
    ;;
  'https://github.com/adoptium/temurin25-binaries/releases/download/'*.tar.gz)
    cp "$MOCK_FIXTURE_DIR/temurin25.archive" "$output"
    ;;
  'https://nodejs.org/dist/index.json')
    cp "$MOCK_FIXTURE_DIR/node-index.json" "$output"
    ;;
  'https://nodejs.org/dist/v24.21.0/SHASUMS256.txt')
    printf '%s  node-v24.21.0-linux-x64.tar.xz\n' "$(archive_hash node24.archive)" > "$output"
    ;;
  'https://nodejs.org/dist/v24.21.0/node-v24.21.0-linux-x64.tar.xz')
    if [ "${MOCK_TAMPER_ARCHIVE:-}" = node ]; then
      printf 'tampered Node.js archive\n' > "$output"
    else
      cp "$MOCK_FIXTURE_DIR/node24.archive" "$output"
    fi
    ;;
  *) printf 'mock-curl: unexpected URL: %s\n' "$url" >&2; exit 22 ;;
esac
