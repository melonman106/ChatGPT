#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
"$root_dir/build.sh" >/dev/null
output=$(/usr/lib/jvm/java-17-openjdk-amd64/bin/jshell -q \
  --class-path "$root_dir/build/eaglercraft-26.2-java-cli.jar" <<'EOF'
import java.lang.reflect.*;
var type = Class.forName("com.eaglercraft.patcher.Main");
var method = type.getDeclaredMethod("selectVersionLine", String.class);
method.setAccessible(true);
System.out.println(method.invoke(null, "NOTE: Picked up JDK_JAVA_OPTIONS: -Dexample=true\nopenjdk version \"17.0.19\" 2026-04-21\nOpenJDK Runtime Environment"));
EOF
)
if ! grep -Fq 'openjdk version "17.0.19" 2026-04-21' <<<"$output"; then
  echo "version-line selection failed" >&2
  printf '%s\n' "$output" >&2
  exit 1
fi
version_output=$(JDK_JAVA_OPTIONS='-Dthis_should_not_reach_child=true' \
  /usr/lib/jvm/java-17-openjdk-amd64/bin/jshell -q \
  --class-path "$root_dir/build/eaglercraft-26.2-java-cli.jar" 2>/dev/null <<'EOF'
import java.lang.reflect.*; import java.nio.file.*;
var type = Class.forName("com.eaglercraft.patcher.Main");
var method = type.getDeclaredMethod("readProcessVersion", Path.class);
method.setAccessible(true);
System.out.println(method.invoke(null, Path.of("/usr/lib/jvm/java-17-openjdk-amd64/bin/java")));
EOF
)
if ! grep -Fq 'openjdk version "17.0.19" 2026-04-21' <<<"$version_output" \
  || grep -Fq 'Picked up JDK_JAVA_OPTIONS' <<<"$version_output"; then
  echo "child Java option scrub failed" >&2
  printf '%s\n' "$version_output" >&2
  exit 1
fi
printf 'version-line selection: PASS (note-prefixed output ignored; child env scrubbed)\n'
