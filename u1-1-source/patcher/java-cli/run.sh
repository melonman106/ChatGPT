#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
jar_path="$root_dir/build/eaglercraft-26.2-java-cli.jar"
if [[ ! -f "$jar_path" ]]; then
  "$root_dir/build.sh"
fi
exec java -jar "$jar_path" "$@"
