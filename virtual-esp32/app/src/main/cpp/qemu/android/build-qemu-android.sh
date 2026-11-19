#!/bin/bash
# build-qemu-android.sh — cross-compile Espressif QEMU (xtensa-softmmu, TCI)
# for Android/Bionic arm64-v8a using the Android NDK.
#
# Usage: ./build-qemu-android.sh <qemu-src-dir> <abi> <api-level>
#   e.g.: ./build-qemu-android.sh ~/espressif-qemu arm64-v8a 26
#
# Output: ./install-<abi>/  (include/ + lib/libqemu_xtensa_softmmu.a + lib/libqemu_deps.a)
#
# This builds, in order: libffi -> pcre2 -> glib2 -> pixman -> QEMU.
# zlib comes from the NDK stable API (no build needed).
#
# Versions pinned here were current 2026-10-09. If a tarball 404s, bump to
# the newest micro release of the same major line.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QEMU_SRC="${1:?usage: $0 <qemu-src-dir> <abi> <api-level>}"
ABI="${2:?usage: $0 <qemu-src-dir> <abi> <api-level>}"
API="${3:?usage: $0 <qemu-src-dir> <abi> <api-level>}"
NDK="${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT to the NDK directory}"

if [ "$ABI" != "arm64-v8a" ]; then
    echo "Only arm64-v8a is supported by this script (got $ABI)"; exit 1
fi

TRIPLE=aarch64-linux-android
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$TOOLCHAIN/sysroot"
PREFIX="$PWD/deps-$ABI"
INSTALL="$PWD/install-$ABI"
JOBS="$(nproc)"
# NOTE: keep these exports on separate lines — `export A=.. B=$A/..` on one
# line does NOT expand A when setting B.

LIBFFI_VER=3.4.6
PCRE2_VER=10.44
GLIB_VER=2.82.4
PIXMAN_VER=0.44.2
LIBICONV_VER=1.17

export CC="$TOOLCHAIN/bin/${TRIPLE}${API}-clang"
export CXX="$TOOLCHAIN/bin/${TRIPLE}${API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
export CFLAGS="--sysroot=$SYSROOT -fPIC -O2"
export LDFLAGS="--sysroot=$SYSROOT -fPIC -L$PREFIX/lib"
# Bionic lacks iconv; glib's meson iconv check needs these to find libiconv.
export LIBRARY_PATH="$PREFIX/lib"
export CPATH="$PREFIX/include"

mkdir -p "$PREFIX" "$INSTALL/include" "$INSTALL/lib" build-tmp
cd build-tmp

fetch() { # url outfile
    [ -f "$2" ] || curl -sSL -o "$2" "$1"
}

# libiconv: Bionic does not provide iconv(); glib requires it.
echo "=== libiconv $LIBICONV_VER ==="
fetch "https://ftp.gnu.org/pub/gnu/libiconv/libiconv-$LIBICONV_VER.tar.gz" libiconv.tar.gz
[ -d libiconv-$LIBICONV_VER ] || tar xzf libiconv.tar.gz
cd libiconv-$LIBICONV_VER
[ -f Makefile ] || ./configure --host=$TRIPLE --prefix="$PREFIX" --disable-shared --enable-static >/dev/null
make -j"$JOBS" >/dev/null && make install >/dev/null
cd ..
# libiconv ships no .pc file; provide one for pkg-config consumers.
cat > "$PREFIX/lib/pkgconfig/iconv.pc" <<EOF
prefix=$PREFIX
exec_prefix=\${prefix}
libdir=\${exec_prefix}/lib
includedir=\${prefix}/include
Name: libiconv
Description: Character set conversion library
Version: $LIBICONV_VER
Libs: -L\${libdir} -liconv
Cflags: -I\${includedir}
EOF

echo "=== libffi $LIBFFI_VER ==="
fetch "https://github.com/libffi/libffi/releases/download/v$LIBFFI_VER/libffi-$LIBFFI_VER.tar.gz" libffi.tar.gz
[ -d libffi-$LIBFFI_VER ] || tar xzf libffi.tar.gz
cd libffi-$LIBFFI_VER
[ -f Makefile ] || ./configure --host=$TRIPLE --prefix="$PREFIX" --disable-shared --enable-static --disable-docs >/dev/null
make -j"$JOBS" >/dev/null && make install >/dev/null
cd ..

echo "=== pcre2 $PCRE2_VER ==="
fetch "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-$PCRE2_VER/pcre2-$PCRE2_VER.tar.gz" pcre2.tar.gz
[ -d pcre2-$PCRE2_VER ] || tar xzf pcre2.tar.gz
cd pcre2-$PCRE2_VER
[ -f Makefile ] || ./configure --host=$TRIPLE --prefix="$PREFIX" --disable-shared --enable-static >/dev/null
make -j"$JOBS" >/dev/null && make install >/dev/null
cd ..

echo "=== glib $GLIB_VER (meson, needs python3) ==="
fetch "https://download.gnome.org/sources/glib/${GLIB_VER%.*}/glib-$GLIB_VER.tar.xz" glib.tar.xz
[ -d glib-$GLIB_VER ] || tar xf glib.tar.xz
# meson cross file for NDK
cat > android-cross.ini <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
[properties]
sys_root = '$SYSROOT'
pkg_config_libdir = '$PREFIX/lib/pkgconfig'
c_args = ['--sysroot=$SYSROOT', '-fPIC', '-I$PREFIX/include']
c_link_args = ['--sysroot=$SYSROOT', '-fPIC', '-L$PREFIX/lib']
EOF
cd glib-$GLIB_VER
# NOTE: glib's meson iconv check needs LDFLAGS/LIBRARY_PATH/CPATH exported
# (done at the top of this script) or it fails to link -liconv.
[ -d build-android ] || meson setup build-android --cross-file ../android-cross.ini \
    --prefix="$PREFIX" --libdir=lib --default-library=static \
    -Dtests=false -Ddocumentation=false -Dman=false >/dev/null
ninja -C build-android >/dev/null && ninja -C build-android install >/dev/null
cd ..

echo "=== pixman $PIXMAN_VER ==="
fetch "https://cairographics.org/releases/pixman-$PIXMAN_VER.tar.gz" pixman.tar.gz
[ -d pixman-$PIXMAN_VER ] || tar xzf pixman.tar.gz
cd pixman-$PIXMAN_VER
[ -d build-android ] || meson setup build-android --cross-file ../android-cross.ini \
    --prefix="$PREFIX" --libdir=lib --default-library=static >/dev/null
ninja -C build-android >/dev/null && ninja -C build-android install >/dev/null
cd ..

echo "=== QEMU xtensa-softmmu (TCI) ==="
cd "$QEMU_SRC"
# Apply Android port patches (idempotent).
for p in "$SCRIPT_DIR/patches/"*.patch; do
    [ -f "$p" ] || continue
    patch -p1 --forward --silent < "$p" 2>/dev/null || true
done
[ -d build-android ] || ./configure --cross-prefix="$TOOLCHAIN/bin/${TRIPLE}${API}-" \
    --cc="$CC" --host-cc=gcc \
    --target-list=xtensa-softmmu \
    --enable-tcg-interpreter \
    --disable-tools --disable-docs --disable-guest-agent \
    --disable-gtk --disable-sdl --disable-vnc --disable-spice --disable-opengl \
    --disable-virglrenderer --disable-capstone --disable-slirp \
    --disable-xen --disable-kvm --disable-hvf --disable-whpx --disable-nvmm \
    --disable-curses --disable-brlapi --disable-curl --disable-gnutls \
    --disable-nettle --disable-gcrypt --disable-auth-pam \
    --disable-libssh --disable-rdma --disable-lzo --disable-snappy \
    --disable-bzip2 --disable-lz4 --disable-zstd \
    --disable-fdt --disable-cap-ng --disable-attr --disable-vhost-net \
    --disable-vhost-user --disable-vhost-vdpa --disable-vhost-kernel \
    --disable-qom-cast-debug --disable-strip \
    --extra-cflags="--sysroot=$SYSROOT -fPIC" \
    --extra-ldflags="--sysroot=$SYSROOT -fPIC -landroid -llog"
make -j"$JOBS" >/dev/null

echo "=== packaging into $INSTALL ==="
# Collect the softmmu objects into one archive for the app's CMake.
# QEMU's build leaves per-subsystem archives; merge the ones we need.
QEMU_B="$QEMU_SRC/build"
# The main softmmu binary objects:
find "$QEMU_B" -name "libqemu-xtensa-softmmu.a" -exec cp {} "$INSTALL/lib/libqemu_xtensa_softmmu.a" \;
# Dependency archives (glib/gobject/gio/ffi/pcre2/pixman):
for a in "$PREFIX"/lib/libglib-2.0.a "$PREFIX"/lib/libgobject-2.0.a \
         "$PREFIX"/lib/libgio-2.0.a "$PREFIX"/lib/libgmodule-2.0.a \
         "$PREFIX"/lib/libffi.a "$PREFIX"/lib/libpcre2-8.a \
         "$PREFIX"/lib/libpixman-1.a; do
    [ -f "$a" ] || { echo "MISSING dep archive: $a"; exit 1; }
done
# Merge deps into a single archive for simple CMake linking:
rm -f "$INSTALL/lib/libqemu_deps.a"
mkdir -p merge && cd merge && rm -f *.o
for a in "$PREFIX"/lib/libglib-2.0.a "$PREFIX"/lib/libgobject-2.0.a \
         "$PREFIX"/lib/libgio-2.0.a "$PREFIX"/lib/libgmodule-2.0.a \
         "$PREFIX"/lib/libffi.a "$PREFIX"/lib/libpcre2-8.a \
         "$PREFIX"/lib/libpixman-1.a; do
    "$AR" x "$a"
done
"$AR" rcs "$INSTALL/lib/libqemu_deps.a" *.o
cd .. && rm -rf merge
# Headers: QEMU include/ + generated headers from the build tree.
cp -r "$QEMU_SRC/include" "$INSTALL/include/qemu-include"
mkdir -p "$INSTALL/include/qemu-gen"
cp -r "$QEMU_B"/qapi/qapi-types-run-state.h "$INSTALL/include/qemu-gen/" 2>/dev/null || true
find "$QEMU_B" -name "config-host.h" -exec cp {} "$INSTALL/include/qemu-gen/" \;
echo "DONE. Point CMake at: -DQEMU_BUILD_DIR=$INSTALL"
echo "Note: include path for the app build must cover:"
echo "  $INSTALL/include/qemu-include and $INSTALL/include/qemu-gen"
