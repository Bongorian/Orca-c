#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

NDK="${ANDROID_NDK_HOME:-/home/bongorian/android-sdk/ndk/27.2.12479018}"
SYS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
RES="$NDK/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/18"
OUT="android/app/src/main/jniLibs/arm64-v8a/liborcacore.so"

mkdir -p "$(dirname "$OUT")"

clang --target=aarch64-linux-android23 \
  --sysroot="$SYS" \
  -resource-dir "$RES" \
  -fuse-ld=lld \
  --rtlib=compiler-rt \
  -fPIC \
  -shared \
  -std=c99 \
  -O2 \
  -Wall \
  -Wextra \
  -Wno-unused-parameter \
  -D_POSIX_C_SOURCE=200809L \
  -I. \
  android/app/src/main/cpp/native_bridge.c \
  field.c \
  gbuffer.c \
  sim.c \
  vmio.c \
  -o "$OUT"

file "$OUT"
