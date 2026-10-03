#!/usr/bin/env sh
case "${1:-}" in
  -version) printf 'openjdk version "17.0.20" 2026-09-01\n' >&2 ;;
  -jar) printf '%s\n' "$*" > "$MOCK_LAUNCH_LOG" ;;
  *) exit 1 ;;
esac
