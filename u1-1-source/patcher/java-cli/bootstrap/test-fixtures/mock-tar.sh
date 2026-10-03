#!/usr/bin/env sh
set -eu

archive=
destination=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --no-same-owner|--no-same-permissions|-xf) shift ;;
    -C) destination=$2; shift 2 ;;
    *)
      if [ -z "$archive" ]; then archive=$1; fi
      shift
      ;;
  esac
done
[ -n "$archive" ] && [ -n "$destination" ] || { printf 'mock-tar: missing archive or destination\n' >&2; exit 2; }
payload=$(cat "$archive")
case "$payload" in
  *'temurin17 test archive'*)
    root=$destination/jdk-17.0.20+1
    kind=java17
    ;;
  *'temurin25 test archive'*)
    root=$destination/jdk-25.0.4+1
    kind=java25
    ;;
  *'node24 test archive'*)
    root=$destination/node-v24.21.0-linux-x64
    kind=node
    ;;
  *) printf 'mock-tar: unknown fixture archive\n' >&2; exit 2 ;;
esac
if [ "$kind" = java17 ] || [ "$kind" = java25 ]; then
  mkdir -p "$root/bin"
  cp "$MOCK_FIXTURE_DIR/mock-$kind.sh" "$root/bin/java"
  cp "$MOCK_FIXTURE_DIR/mock-javac.sh" "$root/bin/javac"
  chmod 755 "$root/bin/java" "$root/bin/javac"
else
  mkdir -p "$root/bin" "$root/lib/node_modules/npm/bin"
  cp "$MOCK_FIXTURE_DIR/mock-node.sh" "$root/bin/node"
  cp "$MOCK_FIXTURE_DIR/mock-npm-cli.js" "$root/lib/node_modules/npm/bin/npm-cli.js"
  chmod 755 "$root/bin/node"
fi
