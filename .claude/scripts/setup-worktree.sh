#!/usr/bin/env bash
# Prepare a git worktree of this repo for Gradle builds. Run from inside the worktree.
# Copies the gitignored host inputs from the main checkout (the APK's bootstrap.tar, and
# native/bootstrap/<appId>/ that :app:packageHostAssets<Flavor> reads even for unit tests),
# then inits the termux-x11 C submodules from the main checkout's module repos with a
# per-command -c url (never `git config submodule.*.url`, which rewrites the shared .git/config).
set -euo pipefail
WT=$(git rev-parse --show-toplevel)
MAIN=$(cd "$(git rev-parse --git-common-dir)/.." && pwd)
if [ "$WT" = "$MAIN" ]; then
  echo "run this inside a worktree, not the main checkout" >&2
  exit 2
fi
cd "$WT"
mkdir -p app/src/ivarna/assets
[ -f app/src/ivarna/assets/bootstrap.tar ] ||
  cp "$MAIN/app/src/ivarna/assets/bootstrap.tar" app/src/ivarna/assets/bootstrap.tar
for nb in "$MAIN"/native/bootstrap/*/; do
  app_id=$(basename "$nb")
  dest="native/bootstrap/$app_id"
  mkdir -p "$dest"
  [ ! -f "$nb/bootstrap.tar" ] || [ -f "$dest/bootstrap.tar" ] || cp "$nb/bootstrap.tar" "$dest/bootstrap.tar"
  if [ -d "$nb/root" ]; then
    mkdir -p "$dest/root"
    cp -an "$nb/root/." "$dest/root/"
  fi
done
git config -f .gitmodules --get-regexp 'submodule\.termux-x11.*\.path' |
  while read -r key path; do
    name=${key#submodule.}
    name=${name%.path}
    [ ! -e "$path/.git" ] || continue
    git -c "submodule.$name.url=$MAIN/.git/modules/$name" -c protocol.file.allow=always \
      submodule update --init -- "$path"
  done
echo "worktree ready: $WT"
