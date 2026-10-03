#!/usr/bin/env sh
case "${1:-}" in
  -s) printf '%s\n' "${MOCK_OS:-Linux}" ;;
  -m) printf 'x86_64\n' ;;
  *) printf 'mock-uname: unsupported argument\n' >&2; exit 2 ;;
esac
