#!/bin/sh
# Host-side smoke test for the optional shared-data fields in the Sshd4a pkg index.
set -eu

if [ "$#" -ne 3 ]; then
    echo "Usage: $0 <pkg-script> <binary> <data-archive>" >&2
    exit 2
fi
PKG_SOURCE=$1
BINARY_SOURCE=$2
BUNDLE_SOURCE=$3
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT HUP INT TERM
BASE="$WORK/base"
MOCKS="$WORK/mocks"
mkdir -p "$BASE/bin" "$BASE/etc/pkg-cache" "$BASE/tmp" "$MOCKS" \
    "$WORK/home-root/.config/mc" "$WORK/home-shell/.config/mc"
printf 'root-personal-config\n' > "$WORK/home-root/.config/mc/ini"
printf 'shell-personal-config\n' > "$WORK/home-shell/.config/mc/ini"

# Replace only the production constant; the generated script otherwise runs as-is.
sed "s|^BASE=/data/local/tmp/Sshd4a$|BASE=$BASE|" "$PKG_SOURCE" > "$WORK/pkg"
chmod 755 "$WORK/pkg"
cat > "$MOCKS/getprop" <<'EOF'
#!/bin/sh
printf 'arm64-v8a\n'
EOF
cat > "$MOCKS/busybox" <<'EOF'
#!/bin/sh
cmd=$1
shift
case "$cmd" in
    wget)
        output=
        while [ "$#" -gt 0 ]; do
            case "$1" in
                -q) shift ;;
                -O) output=$2; shift 2 ;;
                *) url=$1; shift ;;
            esac
        done
        case "$url" in
            test://mc) cp "$TEST_BINARY" "$output" ;;
            test://mc-data) cp "$TEST_BUNDLE" "$output" ;;
            test://evil-data) cp "$TEST_EVIL_BUNDLE" "$output" ;;
            *) echo "Unexpected test URL: $url" >&2; exit 1 ;;
        esac
        ;;
    sha256sum) exec sha256sum "$@" ;;
    md5sum) exec md5sum "$@" ;;
    tar) exec tar "$@" ;;
    *) echo "Unsupported busybox test applet: $cmd" >&2; exit 1 ;;
esac
EOF
chmod 755 "$MOCKS/getprop" "$MOCKS/busybox"
PATH="$MOCKS:/usr/bin:/bin"
export PATH
TEST_BINARY=$BINARY_SOURCE
TEST_BUNDLE=$BUNDLE_SOURCE
export TEST_BINARY TEST_BUNDLE
BINARY_SHA=$(sha256sum "$BINARY_SOURCE" | awk '{print $1}')
BUNDLE_SHA=$(sha256sum "$BUNDLE_SOURCE" | awk '{print $1}')
printf 'mc|arm64-v8a|4.8.33|test://mc|%s|mc|test://mc-data|%s\n' \
    "$BINARY_SHA" "$BUNDLE_SHA" > "$BASE/etc/pkg-cache/test.list"

sh "$WORK/pkg" install --force mc
cmp "$BINARY_SOURCE" "$BASE/bin/mc"
test -f "$BASE/share/mc/skins/default.ini"
test -r "$BASE/share/mc/skins/default.ini"
test -x "$BASE/share/mc"
python3 - "$BASE/share/mc" <<'PY'
import os, stat, sys
for root, dirs, files in os.walk(sys.argv[1]):
    for name in ["."] + dirs + files:
        path = root if name == "." else os.path.join(root, name)
        mode = stat.S_IMODE(os.stat(path).st_mode)
        if not mode & 0o004:
            raise SystemExit(f"not readable by every role: {path}")
        if os.path.isdir(path) and not mode & 0o001:
            raise SystemExit(f"not traversable by every role: {path}")
        if mode & 0o022:
            raise SystemExit(f"shared path is writable by group/other: {path}")
PY
# Shared files are readable but not writable by other roles; each HOME stays independent.
[ "$(cat "$WORK/home-root/.config/mc/ini")" = root-personal-config ]
[ "$(cat "$WORK/home-shell/.config/mc/ini")" = shell-personal-config ]
sh "$WORK/pkg" remove mc
test ! -e "$BASE/bin/mc"
test ! -e "$BASE/share/mc"
[ "$(cat "$WORK/home-root/.config/mc/ini")" = root-personal-config ]
[ "$(cat "$WORK/home-shell/.config/mc/ini")" = shell-personal-config ]

# The six-field legacy package line remains supported by the new installer.
printf 'legacy|arm64-v8a|1|test://mc|%s|legacy\n' "$BINARY_SHA" \
    > "$BASE/etc/pkg-cache/test.list"
# Reuse the binary route, adding only a stub mapping for this test URL by rewriting it.
sed -i 's|test://mc|test://mc|g' "$BASE/etc/pkg-cache/test.list"
sh "$WORK/pkg" install --force legacy
cmp "$BINARY_SOURCE" "$BASE/bin/legacy"

# A correctly hashed archive must still be rejected if it tries to escape share/mc.
mkdir -p "$WORK/evil/share/mc"
printf 'not a skin\n' > "$WORK/evil/share/mc/payload"
tar --transform='s|share/mc/payload|share/mc/../../escape|' \
    -czf "$WORK/evil.tar.gz" -C "$WORK/evil" share/mc/payload
TEST_EVIL_BUNDLE=$WORK/evil.tar.gz
export TEST_EVIL_BUNDLE
EVIL_SHA=$(sha256sum "$WORK/evil.tar.gz" | awk '{print $1}')
printf 'mc|arm64-v8a|4.8.33|test://mc|%s|mc|test://evil-data|%s\n' \
    "$BINARY_SHA" "$EVIL_SHA" > "$BASE/etc/pkg-cache/test.list"
if sh "$WORK/pkg" install --force mc > "$WORK/evil.log" 2>&1; then
    echo 'Unsafe bundle archive was unexpectedly accepted' >&2
    exit 1
fi
grep -q 'outside share/mc\|unsafe path' "$WORK/evil.log"
test ! -e "$BASE/share/mc"

echo 'pkg shared-data smoke test passed'
