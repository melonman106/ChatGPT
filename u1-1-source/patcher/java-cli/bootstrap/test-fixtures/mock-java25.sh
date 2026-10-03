#!/usr/bin/env sh
if [ "${1:-}" = -version ]; then
  printf 'openjdk version "25.0.4" 2026-09-01\n' >&2
else
  exit 1
fi
