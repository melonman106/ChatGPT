#!/bin/sh
case "${1:-}" in
  -s) printf '%s\n' "${MOCK_OS:-Darwin}" ;;
  -m) printf '%s\n' "${MOCK_ARCH:-arm64}" ;;
  *) printf 'mock-uname: unsupported argument\n' >&2; exit 2 ;;
esac
