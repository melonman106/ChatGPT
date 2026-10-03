#!/bin/sh
if [ "${1:-}" = -productVersion ]; then
  printf '%s\n' "${MOCK_MAC_VERSION:-14.7.6}"
else
  printf 'mock-sw_vers: unsupported argument\n' >&2
  exit 2
fi
