#!/usr/bin/env sh
case "${1:-}" in
  --version) printf 'v24.21.0\n' ;;
  */npm-cli.js) printf '11.19.0\n' ;;
  *) exit 1 ;;
esac
