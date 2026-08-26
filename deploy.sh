#!/usr/bin/env bash
#
# Deploy OpenVeilCam to a Raspberry Pi over SSH and build it there.
#
# The whole `src/` tree is synced rather than a hand-listed set of files. The
# old file-by-file copy silently skipped every module added after it was
# written, which meant the Pi built stale code — or, once `main.rs` started
# importing from `lib.rs`, did not build at all.

set -euo pipefail

RPI="${RPI:-prarthana@192.168.29.167}"
REMOTE_DIR="${REMOTE_DIR:-nostreye-cam}"
LOCAL_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "==> Checking RPi environment ($RPI)..."
ssh "$RPI" "
  source ~/.cargo/env 2>/dev/null || true
  echo '--- Rust ---'
  rustc --version 2>&1 || echo 'MISSING: rustc'
  cargo --version 2>&1 || echo 'MISSING: cargo'
  echo '--- Camera ---'
  # The code shells out to rpicam-still; libcamera-still is its older name and
  # is reported only as a hint, not as a substitute.
  rpicam-still --version 2>&1 | head -1 || echo 'MISSING: rpicam-still (install rpicam-apps)'
  libcamera-still --version 2>&1 | head -1 || true
  echo '--- Build deps for vendored OpenSSL (needed by c2pa) ---'
  cc --version 2>&1 | head -1 || echo 'MISSING: cc (install build-essential)'
  perl --version 2>&1 | sed -n 2p || echo 'MISSING: perl'
  make --version 2>&1 | head -1 || echo 'MISSING: make'
  echo '--- System ---'
  uname -m
  grep PRETTY_NAME /etc/os-release
"

echo ""
echo "==> Syncing source to $RPI:~/$REMOTE_DIR ..."
ssh "$RPI" "mkdir -p '$REMOTE_DIR/src'"

# Cargo.lock goes too, so the Pi resolves the same dependency versions that
# were tested here rather than picking up whatever is newest.
if command -v rsync >/dev/null 2>&1; then
    # --delete keeps the remote src/ an exact mirror: a module renamed or
    # removed locally must not linger on the Pi and get compiled.
    rsync -az --delete \
        "$LOCAL_DIR/src/" "$RPI:$REMOTE_DIR/src/"
    rsync -az \
        "$LOCAL_DIR/Cargo.toml" "$LOCAL_DIR/Cargo.lock" "$RPI:$REMOTE_DIR/"
else
    echo "    (rsync not found — falling back to scp)"
    ssh "$RPI" "rm -rf '$REMOTE_DIR/src'"
    scp -rq "$LOCAL_DIR/src" "$RPI:$REMOTE_DIR/"
    scp -q "$LOCAL_DIR/Cargo.toml" "$LOCAL_DIR/Cargo.lock" "$RPI:$REMOTE_DIR/"
fi

echo "    synced: $(cd "$LOCAL_DIR/src" && find . -name '*.rs' | wc -l) source files + Cargo.toml + Cargo.lock"

echo ""
echo "==> Building on RPi (first build is slow: c2pa compiles OpenSSL from source)..."
ssh "$RPI" "source ~/.cargo/env 2>/dev/null || true; cd '$REMOTE_DIR' && cargo build 2>&1"

echo ""
echo "==> Done. Run the camera app with:"
echo "    ssh $RPI 'source ~/.cargo/env; cd $REMOTE_DIR && cargo run'"
echo ""
echo "    Or the bridge (no camera needed):"
echo "    ssh $RPI 'source ~/.cargo/env; cd $REMOTE_DIR && cargo run --bin c2pa-bridge -- inspect FILE'"
