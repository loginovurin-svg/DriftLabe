#!/system/bin/sh
#
# Rebuild a downloaded Termux .deb before dpkg sees it.
#
# Termux packages are archives whose data paths are rooted at
# data/data/<package>/..., so changing only the APK/bootstrap leaves the
# package archive itself pointing at the old application directory.

set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: dpkg-sanitize-deb.sh PACKAGE.deb" >&2
    exit 64
fi

DEB="$1"
PREFIX="/data/data/com.logbox/files/usr"
TMP_BASE="${TMPDIR:-$PREFIX/tmp}"

# The APT pre-install hook and the dpkg wrapper can both see the same archive.
# Avoid rebuilding an archive a second time after the hook has already fixed it.
if ! "$PREFIX/bin/dpkg-deb" --contents "$DEB" | grep 'data/data/com\.termux' >/dev/null; then
    exit 0
fi

WORK="$(mktemp -d "$TMP_BASE/logbox-deb.XXXXXX")"

cleanup() {
    rm -rf "$WORK"
}
trap cleanup EXIT HUP INT TERM

ROOT="$WORK/root"
CONTROL="$ROOT/DEBIAN"
OLD_ID="com.$(printf '%s' 'termux')"
NEW_ID="com.logbox"

mkdir -p "$ROOT"
"$PREFIX/bin/dpkg-deb" --raw-extract "$DEB" "$ROOT"

# The archive member path is the part that makes stock packages escape the
# application's private directory during dpkg unpack.
OLD_TREE="$ROOT/data/data/$OLD_ID"
NEW_TREE="$ROOT/data/data/$NEW_ID"
if [ -e "$OLD_TREE" ] || [ -L "$OLD_TREE" ]; then
    mkdir -p "$ROOT/data/data"
    if [ -e "$NEW_TREE" ] || [ -L "$NEW_TREE" ]; then
        echo "refusing to merge both old and new package roots in $DEB" >&2
        exit 1
    fi
    mv "$OLD_TREE" "$NEW_TREE"
fi

# Patch regular payload/control files. This also covers maintainer scripts,
# dynamic loader metadata and configuration files that carry an absolute
# application path. Do not use sed -i here: it replaces files and can reset
# executable permissions on postinst/prerm scripts, making dpkg-deb reject the
# rebuilt package. Redirect into a temporary file, then write over the
# original, which preserves its mode and ownership.
find "$ROOT" -type f -print | while IFS= read -r file; do
    patched_file="$(mktemp "$WORK/file.XXXXXX")"
    sed \
        -e "s#/data/data/$OLD_ID#/data/data/$NEW_ID#g" \
        -e "s#$OLD_ID#$NEW_ID#g" \
        "$file" > "$patched_file"
    cat "$patched_file" > "$file"
    rm "$patched_file"
done

# Patch symlink targets, which are not modified by sed on the link itself.
find "$ROOT" -type l -print | while IFS= read -r link; do
    target="$(readlink "$link")"
    patched_target="$(printf '%s' "$target" | sed \
        -e "s#/data/data/$OLD_ID#/data/data/$NEW_ID#g" \
        -e "s#$OLD_ID#$NEW_ID#g")"
    if [ "$target" != "$patched_target" ]; then
        rm "$link"
        ln -s "$patched_target" "$link"
    fi
done

# Termux repositories may store maintainer scripts with 0644 permissions.
# dpkg-deb --build requires executable permissions for these control scripts.
for maintainer_script in preinst postinst prerm postrm; do
    if [ -f "$CONTROL/$maintainer_script" ]; then
        chmod 0755 "$CONTROL/$maintainer_script"
    fi
done

# md5sums belongs to the control archive and must match the rewritten data.
if [ -f "$CONTROL/md5sums" ]; then
    (
        cd "$ROOT"
        find . -type f ! -path './DEBIAN/*' -print | sort | xargs md5sum > "$CONTROL/md5sums"
    )
fi

REBUILT="$WORK/$(basename "$DEB")"
"$PREFIX/bin/dpkg-deb" --build "$ROOT" "$REBUILT" >/dev/null
mv "$REBUILT" "$DEB"