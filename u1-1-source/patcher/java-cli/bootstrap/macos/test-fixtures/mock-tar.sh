#!/bin/sh
set -eu

mode=
archive=
destination=
while [ "$#" -gt 0 ]; do
  case "$1" in
    -tf) mode=list; shift ;;
    -tvf) mode=verbose; shift ;;
    -xzf) mode=extract; shift ;;
    -C) destination=$2; shift 2 ;;
    *)
      if [ -z "$archive" ]; then archive=$1; fi
      shift
      ;;
  esac
done
[ -n "$archive" ] || { printf 'mock-tar: missing archive\n' >&2; exit 2; }
kind=$(sed -n '1p' "$archive")
case "$kind" in
  temurin17*) root=jdk-17.0.20.1+1; kind=java17 ;;
  temurin25*) root=jdk-25.0.4.1+1; kind=java25 ;;
  node24*)
    case "${MOCK_ARCH:-arm64}" in x86_64|amd64) node_arch=x64 ;; *) node_arch=arm64 ;; esac
    root=node-v24.21.0-darwin-$node_arch
    kind=node
    ;;
  *) printf 'mock-tar: unknown fixture archive\n' >&2; exit 2 ;;
esac
case "$mode" in
  list)
    printf '%s/\n' "$root"
    if [ "$kind" = java17 ] || [ "$kind" = java25 ]; then
      printf '%s/Contents/\n%s/Contents/Home/\n%s/Contents/Home/bin/\n' "$root" "$root" "$root"
      printf '%s/Contents/Home/bin/java\n%s/Contents/Home/bin/javac\n' "$root" "$root"
    else
      printf '%s/bin/\n%s/lib/\n%s/lib/node_modules/\n%s/lib/node_modules/npm/\n%s/lib/node_modules/npm/bin/\n' \
        "$root" "$root" "$root" "$root" "$root"
      printf '%s/bin/node\n%s/bin/npm\n%s/lib/node_modules/npm/bin/npm-cli.js\n' "$root" "$root" "$root"
    fi
    if [ "${MOCK_UNSAFE_TAR:-0}" = 1 ]; then printf '../escape.txt\n'; fi
    ;;
  verbose)
    if [ "$kind" = node ]; then
      if [ "${MOCK_UNSAFE_LINK:-0}" = 1 ]; then
        printf 'lrwxr-xr-x  1 root  wheel  42 Sep 25 2026 %s/bin/npm -> ../../../../outside/npm-cli.js\n' "$root"
      else
        printf 'lrwxr-xr-x  1 root  wheel  42 Sep 25 2026 %s/bin/npm -> ../lib/node_modules/npm/bin/npm-cli.js\n' "$root"
      fi
    fi
    ;;
  extract)
    [ -n "$destination" ] || { printf 'mock-tar: missing extraction directory\n' >&2; exit 2; }
    if [ "$kind" = java17 ] || [ "$kind" = java25 ]; then
      mkdir -p "$destination/$root/Contents/Home/bin"
      cp "$MOCK_FIXTURE_DIR/mock-$kind.sh" "$destination/$root/Contents/Home/bin/java"
      cp "$MOCK_FIXTURE_DIR/mock-javac.sh" "$destination/$root/Contents/Home/bin/javac"
      chmod 755 "$destination/$root/Contents/Home/bin/java" "$destination/$root/Contents/Home/bin/javac"
    else
      mkdir -p "$destination/$root/bin" "$destination/$root/lib/node_modules/npm/bin"
      cp "$MOCK_FIXTURE_DIR/mock-node.sh" "$destination/$root/bin/node"
      cp "$MOCK_FIXTURE_DIR/mock-npm-cli.js" "$destination/$root/lib/node_modules/npm/bin/npm-cli.js"
      chmod 755 "$destination/$root/bin/node"
    fi
    ;;
  *) printf 'mock-tar: unsupported tar operation\n' >&2; exit 2 ;;
esac
