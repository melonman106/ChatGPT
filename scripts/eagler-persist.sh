#!/usr/bin/env bash
set -Eeuo pipefail

# Encrypted persistence helper for the GitHub Actions KDE/Sober desktop.
# Commands: restore, save, quiesce. All state is kept under HOME and the
# encrypted profile archive path supplied by EAGLER_PERSIST_ARCHIVE.
ARCHIVE=${EAGLER_PERSIST_ARCHIVE:-${RUNNER_TEMP:-/tmp}/desktop-profile.tar.gpg}
PASSPHRASE=${DISCORD_PROFILE_PASSPHRASE:-${EAGLER_PERSIST_PASSPHRASE:-}}
STATE="$HOME/.local/state/eagler-persist"
BASE="$STATE/baseline"
MAX_BYTES=${EAGLER_PERSIST_MAX_BYTES:-3500000000}
mkdir -p "$STATE"

log() { printf '[eagler-persist] %s\n' "$*"; }
warn() { printf '[eagler-persist] WARNING: %s\n' "$*" >&2; }
need_passphrase() {
  if [[ -z "$PASSPHRASE" ]]; then
    echo "DISCORD_PROFILE_PASSPHRASE (or EAGLER_PERSIST_PASSPHRASE) is required." >&2
    return 1
  fi
}
apt_list() {
  if command -v apt-mark >/dev/null 2>&1; then apt-mark showmanual 2>/dev/null | sort -u; fi
}
flatpak_list() {
  if command -v flatpak >/dev/null 2>&1; then
    flatpak list --user --app --columns=application,origin 2>/dev/null | awk -F '\t' 'NF>=1 && $1!="" {print "user\t"$1"\t"($2==""?"-":$2)}'
    flatpak list --system --app --columns=application,origin 2>/dev/null | awk -F '\t' 'NF>=1 && $1!="" {print "system\t"$1"\t"($2==""?"-":$2)}'
  fi | sort -u
}
capture_baseline() {
  apt_list > "$BASE.apt.tmp" && mv "$BASE.apt.tmp" "$BASE.apt"
  flatpak_list > "$BASE.flatpak.tmp" && mv "$BASE.flatpak.tmp" "$BASE.flatpak"
}
safe_extra_paths() {
  local file="$HOME/extra-paths.txt" item
  [[ -f "$file" ]] || return 0
  while IFS= read -r item || [[ -n "$item" ]]; do
    item="${item%%#*}"; item="$(printf '%s' "$item" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    [[ -z "$item" ]] && continue
    if [[ "$item" == /* || "$item" == *..* || "$item" == . || "$item" == ./* || "$item" == *$'\t'* ]]; then
      warn "Ignoring unsafe extra path: $item"; continue
    fi
    [[ -e "$HOME/$item" ]] && printf '%s\n' "$item"
  done < "$file"
}
restore() {
  if [[ "${EAGLER_PERSIST_RESET:-0}" == 1 ]]; then log "Reset requested; saved profile ignored."; return 0; fi
  if [[ ! -s "$ARCHIVE" ]]; then log "No saved profile; starting a fresh desktop."; capture_baseline; return 0; fi
  need_passphrase
  local tmp list
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  if ! gpg --batch --yes --pinentry-mode loopback --passphrase "$PASSPHRASE" --decrypt "$ARCHIVE" > "$tmp/profile.tar" 2>"$tmp/gpg.err"; then
    echo "Could not decrypt saved profile; nothing was restored." >&2; return 1
  fi
  if ! tar -tf "$tmp/profile.tar" >/dev/null 2>&1; then echo "Saved profile archive is invalid; nothing was restored." >&2; return 1; fi
  # Extract only after decryption and archive validation succeed.
  tar -xf "$tmp/profile.tar" -C "$HOME"
  # Legacy profile archives may contain the Flatpak OSTree repository; never
  # overlay that system/runtime store onto a freshly provisioned runner.
  rm -rf "$HOME/.local/share/flatpak/repo"
  if [[ -f "$HOME/.local/state/eagler-persist/baseline.apt" ]]; then cp "$HOME/.local/state/eagler-persist/baseline.apt" "$BASE.apt"; fi
  if [[ -f "$HOME/.local/state/eagler-persist/baseline.flatpak" ]]; then cp "$HOME/.local/state/eagler-persist/baseline.flatpak" "$BASE.flatpak"; fi
  if command -v apt-get >/dev/null 2>&1 && [[ -f "$HOME/.local/state/eagler-persist/packages.apt" ]]; then
    sudo apt-get update || warn "apt-get update failed; attempting package restoration anyway."
    local pkg
    cp "$HOME/.local/state/eagler-persist/packages.apt" "$tmp/packages.apt"
    while IFS= read -r pkg; do
      [[ -z "$pkg" ]] && continue
      if ! dpkg-query -W -f='${Status}' "$pkg" 2>/dev/null | grep -q 'install ok installed'; then
        if ! sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "$pkg"; then
          warn "Could not restore APT package: $pkg (kept in manifest for next run)"
        fi
      fi
    done < "$tmp/packages.apt"
  fi
  if command -v flatpak >/dev/null 2>&1 && [[ -f "$HOME/.local/state/eagler-persist/packages.flatpak" ]]; then
    while IFS=$'\t' read -r scope app origin; do
      [[ -z "$app" || "$app" == org.vinegarhq.Sober ]] && continue
      if ! flatpak info "$app" >/dev/null 2>&1; then
        if [[ "$scope" == system ]]; then
          sudo flatpak install --system --noninteractive -y "${origin:-flathub}" "$app" || warn "Could not restore Flatpak app: $app"
        else
          flatpak install --user --noninteractive -y "${origin:-flathub}" "$app" || warn "Could not restore Flatpak app: $app"
        fi
      fi
    done < "$HOME/.local/state/eagler-persist/packages.flatpak"
  fi
  capture_baseline
  log "Saved profile restored."
}
save() {
  need_passphrase
  mkdir -p "$STATE" "$HOME/eagler-visual-session/workspace" "$HOME/eagler-visual-session/inbox"
  # Keep existing package manifests if this is a first/partial session without a baseline.
  if [[ -f "$BASE.apt" ]]; then
    apt_list > "$STATE/current.apt"
    comm -13 "$BASE.apt" "$STATE/current.apt" > "$STATE/packages.apt.new" || true
    if [[ -s "$STATE/packages.apt" ]]; then cat "$STATE/packages.apt" >> "$STATE/packages.apt.new"; fi
    sort -u "$STATE/packages.apt.new" > "$STATE/packages.apt"; rm -f "$STATE/packages.apt.new"
  fi
  if [[ -f "$BASE.flatpak" ]]; then
    flatpak_list > "$STATE/current.flatpak"
    comm -13 "$BASE.flatpak" "$STATE/current.flatpak" > "$STATE/packages.flatpak.new" || true
    if [[ -s "$STATE/packages.flatpak" ]]; then cat "$STATE/packages.flatpak" >> "$STATE/packages.flatpak.new"; fi
    sort -u "$STATE/packages.flatpak.new" > "$STATE/packages.flatpak"; rm -f "$STATE/packages.flatpak.new"
    grep -v $'^system\torg.vinegarhq.Sober\t' "$STATE/packages.flatpak" > "$STATE/packages.flatpak.tmp" || true
    mv "$STATE/packages.flatpak.tmp" "$STATE/packages.flatpak"
  fi
  local paths=(.config .local/share .local/state .var/app Desktop .mozilla eagler-visual-session/workspace eagler-visual-session/inbox)
  while IFS= read -r p; do paths+=("$p"); done < <(safe_extra_paths)
  local existing=() p
  for p in "${paths[@]}"; do [[ -e "$HOME/$p" ]] && existing+=("$p"); done
  local size=0
  for p in "${existing[@]}"; do
    [[ "$p" == .cache || "$p" == .local/share/flatpak/repo ]] && continue
    n="$(du -sb "$HOME/$p" 2>/dev/null | awk '{print $1}')"; size=$((size+${n:-0}))
  done
  if (( size > MAX_BYTES )); then
    echo "Profile exceeds size limit ($size bytes > $MAX_BYTES); inspect large folders before saving." >&2
    for p in "${existing[@]}"; do du -sh "$HOME/$p" 2>/dev/null || true; done
    return 1
  fi
  local tmp
  tmp="$(mktemp)"
  trap 'rm -f "$tmp"' RETURN
  # Exclude caches, trash, lock files and system-owned Flatpak runtimes.
  tar -czf "$tmp" -C "$HOME" \
    --exclude='*/.cache' --exclude='.cache' --exclude='*/Cache' --exclude='*/Code Cache' \
    --exclude='*/GPUCache' --exclude='*/SingletonLock' --exclude='*/SingletonCookie' \
    --exclude='*/.parentlock' --exclude='*/lock' --exclude='*/Trash' \
    --exclude='.local/share/flatpak/repo' "${existing[@]}"
  if ! tar -tzf "$tmp" >/dev/null; then echo "Profile archive verification failed." >&2; return 1; fi
  gpg --batch --yes --symmetric --cipher-algo AES256 --pinentry-mode loopback \
    --passphrase "$PASSPHRASE" --output "$ARCHIVE.tmp" "$tmp"
  gpg --batch --yes --pinentry-mode loopback --passphrase "$PASSPHRASE" --decrypt "$ARCHIVE.tmp" | tar -tzf - >/dev/null
  mv "$ARCHIVE.tmp" "$ARCHIVE"
  log "Encrypted profile saved ($size bytes of source data)."
}
quiesce() {
  for app in Discord discord sober Sober; do pkill -TERM -x "$app" 2>/dev/null || true; done
  sleep 2
  log "Application shutdown requests sent."
}
case "${1:-}" in
  restore) restore ;;
  save) save ;;
  quiesce) quiesce ;;
  *) echo "Usage: $0 {restore|save|quiesce}" >&2; exit 2 ;;
esac
