#!/bin/sh
if [ "${1:-}" = -u ]; then
  printf '%s\n' "${MOCK_UID:-501}"
else
  printf 'mock-id: unsupported argument\n' >&2
  exit 2
fi
