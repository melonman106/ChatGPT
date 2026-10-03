#!/bin/sh
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
fixture_hash() { shasum -a 256 "$MOCK_FIXTURE_DIR/$1" | awk '{print $1}'; }
copy_fixture() {
  fixture=$1
  if [ "${MOCK_TAMPER:-}" = "$2" ]; then
    printf 'tampered fixture archive\n' > "$output"
  else
    cp "$MOCK_FIXTURE_DIR/$fixture" "$output"
  fi
  if [ "${MOCK_DELAY:-0}" -gt 0 ]; then sleep "$MOCK_DELAY"; fi
}
case "$url" in
  'https://api.adoptium.net/v3/assets/latest/17/hotspot?'*)
    case "$url" in *'architecture=aarch64'*) arch=aarch64 ;; *) arch=x64 ;; esac
    sed "s#@SHA@#$(fixture_hash temurin17.archive)#g; s#@ARCH@#$arch#g" \
      "$MOCK_FIXTURE_DIR/java17.json.in" > "$output"
    ;;
  'https://api.adoptium.net/v3/assets/latest/25/hotspot?'*)
    case "$url" in *'architecture=aarch64'*) arch=aarch64 ;; *) arch=x64 ;; esac
    sed "s#@SHA@#$(fixture_hash temurin25.archive)#g; s#@ARCH@#$arch#g" \
      "$MOCK_FIXTURE_DIR/java25.json.in" > "$output"
    ;;
  'https://github.com/adoptium/temurin17-binaries/releases/download/'*.sha256.txt)
    printf '%s  OpenJDK17U-jdk_mac_hotspot_fixture.tar.gz\n' "$(fixture_hash temurin17.archive)" > "$output"
    ;;
  'https://github.com/adoptium/temurin25-binaries/releases/download/'*.sha256.txt)
    printf '%s  OpenJDK25U-jdk_mac_hotspot_fixture.tar.gz\n' "$(fixture_hash temurin25.archive)" > "$output"
    ;;
  'https://github.com/adoptium/temurin17-binaries/releases/download/'*.tar.gz)
    copy_fixture temurin17.archive java17
    ;;
  'https://github.com/adoptium/temurin25-binaries/releases/download/'*.tar.gz)
    copy_fixture temurin25.archive java25
    ;;
  'https://nodejs.org/dist/index.json')
    cp "$MOCK_FIXTURE_DIR/node-index.json" "$output"
    ;;
  'https://nodejs.org/dist/v24.21.0/SHASUMS256.txt')
    printf '%s  node-v24.21.0-darwin-arm64.tar.gz\n' "$(fixture_hash node24.archive)" > "$output"
    printf '%s  node-v24.21.0-darwin-x64.tar.gz\n' "$(fixture_hash node24.archive)" >> "$output"
    ;;
  'https://nodejs.org/dist/v24.21.0/node-v24.21.0-darwin-'*.tar.gz)
    copy_fixture node24.archive node
    ;;
  *) printf 'mock-curl: unexpected URL: %s\n' "$url" >&2; exit 22 ;;
esac
