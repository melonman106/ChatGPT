#!/usr/bin/env sh
if [ "${1:-}" = -u ]; then
  printf '1000\n'
else
  printf 'mock-id: unsupported argument\n' >&2
  exit 2
fi
