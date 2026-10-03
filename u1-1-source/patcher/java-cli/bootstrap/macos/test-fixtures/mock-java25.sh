#!/bin/sh
if [ "${1:-}" = -version ]; then
  printf 'openjdk version "25.0.4.1" 2026-08-19\n' >&2
else
  exit 1
fi
