#!/usr/bin/env bash
# 在 Linux / WSL / macOS 上构建 Camera Agent 的 Debug APK，并检查打包结果。
#
# 用法：
#   ./scripts/build-apk.sh [输出目录]
#
# 环境变量：
#   ANDROID_SDK_ROOT / ANDROID_HOME   Android SDK 根目录（必须包含
#                                     platforms/android-35 与 build-tools/35.0.0）
#   JAVA_HOME                         JDK 17
#   GRADLE_HOME                       可选；Gradle 安装目录（bin/gradle 可直接执行）
#                                     或指向 bin/gradle 本身
#   GRADLE_USER_HOME                  可选；Gradle 缓存目录
#   GRADLE_ARGS                       可选；附加给 Gradle 的参数，例如
#                                     "--init-script my-mirrors.gradle" 或 "-q"
#
# 说明：
# - 仓库的 Gradle Wrapper 固定为 Gradle 9.x，而 Android Gradle Plugin 8.8.2 只支持到
#   Gradle 8.x，因此本脚本优先使用 GRADLE_HOME 指定的 Gradle 8.x，未指定时先尝试
#   PATH 中的 gradle，最后才回退到 gradlew。
# - local.properties（sdk.dir）由本脚本按需生成，已加入 .gitignore，不会进入提交。
# - 输出目录缺省为仓库根目录下的 dist/，APK 会复制为
#   dist/camera-agent-<版本>-debug.apk，并生成同名 .sha256 校验文件。
#
# 退出码：0 构建与检查通过；非 0 表示构建失败或 APK 检查未通过。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-$ROOT_DIR/dist}"
APP_GRADLE="$ROOT_DIR/app/build.gradle.kts"

log() { printf '[build-apk] %s\n' "$*"; }
fail() { printf '[build-apk] 错误：%s\n' "$*" >&2; exit 1; }

# ---------- 环境检查 ----------

JAVA_BIN=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
fi
[ -n "$JAVA_BIN" ] || fail "未找到 Java，请安装 JDK 17 并设置 JAVA_HOME。"

JAVA_MAJOR="$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 \
  | sed -n 's/^ *java\.specification\.version = //p' | head -n 1)"
if [ -z "$JAVA_MAJOR" ]; then
  JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)"
fi
[ "$JAVA_MAJOR" = "17" ] || fail "需要 JDK 17，当前 Java 版本为 ${JAVA_MAJOR:-未知}（$JAVA_BIN）。"
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVA_BIN")")")"
fi
export JAVA_HOME
log "Java：$JAVA_BIN"

SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK_DIR" ] && [ -f "$ROOT_DIR/local.properties" ]; then
  SDK_DIR="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | head -n 1)"
fi
[ -n "$SDK_DIR" ] || fail "未设置 ANDROID_SDK_ROOT（或 ANDROID_HOME），也未在 local.properties 中配置 sdk.dir。"
[ -d "$SDK_DIR" ] || fail "Android SDK 目录不存在：$SDK_DIR"
SDK_DIR="$(cd "$SDK_DIR" && pwd)"
[ -d "$SDK_DIR/platforms/android-35" ] || fail "缺少 Android SDK Platform 35（$SDK_DIR/platforms/android-35）。"
[ -f "$SDK_DIR/build-tools/35.0.0/aapt2" ] || fail "缺少 Android SDK Build-Tools 35.0.0（$SDK_DIR/build-tools/35.0.0）。"
export ANDROID_SDK_ROOT="$SDK_DIR"
export ANDROID_HOME="$SDK_DIR"
log "Android SDK：$SDK_DIR"

if [ ! -f "$ROOT_DIR/local.properties" ] || ! grep -qx "sdk\.dir=$SDK_DIR" "$ROOT_DIR/local.properties" 2>/dev/null; then
  printf 'sdk.dir=%s\n' "$SDK_DIR" > "$ROOT_DIR/local.properties"
  log "已写入 local.properties（sdk.dir=$SDK_DIR）"
fi

GRADLE_CMD=()
if [ -n "${GRADLE_HOME:-}" ]; then
  if [ -x "$GRADLE_HOME/bin/gradle" ]; then
    GRADLE_CMD=("$GRADLE_HOME/bin/gradle")
  elif [ -x "$GRADLE_HOME" ]; then
    GRADLE_CMD=("$GRADLE_HOME")
  fi
  [ "${#GRADLE_CMD[@]}" -gt 0 ] || fail "GRADLE_HOME 指向的位置没有可执行的 gradle：$GRADLE_HOME"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE_CMD=("$(command -v gradle)")
else
  GRADLE_CMD=("$ROOT_DIR/gradlew")
fi
chmod +x "$ROOT_DIR/gradlew" 2>/dev/null || true

# 读取 defaultConfig 块（先合并续行），用于版本号、包名与 SDK 级别检查。
DEFAULT_CONFIG="$(tr -d '\n' < "$APP_GRADLE" | sed -n 's/.*defaultConfig *{//p' | sed 's/}.*//')"
gr_value() {
  printf '%s' "$DEFAULT_CONFIG" | sed -n "s/.*$1 *= *\([^ ;}]*\).*/\1/p" | head -n 1 | tr -d '"'
}
VERSION_NAME="$(gr_value versionName)"
EXPECTED_VERSION="$(gr_value versionCode)"
EXPECTED_PACKAGE="$(gr_value applicationId)"
EXPECTED_MIN_SDK="$(gr_value minSdk)"
[ -n "$VERSION_NAME" ] && [ -n "$EXPECTED_VERSION" ] && [ -n "$EXPECTED_PACKAGE" ] && [ -n "$EXPECTED_MIN_SDK" ] \
  || fail "无法从 app/build.gradle.kts 的 defaultConfig 读取 versionName/versionCode/applicationId/minSdk。"

# ---------- 构建 ----------

cd "$ROOT_DIR"
log "Gradle：${GRADLE_CMD[*]}"
if [ "$(basename "${GRADLE_CMD[0]}")" = "gradlew" ]; then
  log "警告：未找到本地 Gradle 8.x，回退到 Gradle Wrapper。"
  log "      Wrapper 版本（gradle/wrapper/gradle-wrapper.properties）需与 AGP 8.8.2 兼容（Gradle 8.10.2 已验证）；"
  log "      若构建失败，请安装 Gradle 8.10.2 并通过 GRADLE_HOME 指定。"
fi

build_args=(:app:assembleDebug --console=plain)
if [ -n "${GRADLE_ARGS:-}" ]; then
  # shellcheck disable=SC2206  # 允许按空格拆分为多个参数
  build_args+=(${GRADLE_ARGS})
fi
"${GRADLE_CMD[@]}" "${build_args[@]}"

APK_PATH="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK_PATH" ] || fail "构建结束但未找到 $APK_PATH"

# ---------- APK 检查 ----------

AAPT2="$SDK_DIR/build-tools/35.0.0/aapt2"
BADGING="$("$AAPT2" dump badging "$APK_PATH")"

ACTUAL_PACKAGE="$(printf '%s\n' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)"
[ "$ACTUAL_PACKAGE" = "$EXPECTED_PACKAGE" ] || fail "包名不符：期望 $EXPECTED_PACKAGE，实际 ${ACTUAL_PACKAGE:-未知}。"

# 权限：CAMERA 与 INTERNET 必须存在；ACCESS_NETWORK_STATE 不应被打包。
for permission in CAMERA INTERNET; do
  printf '%s\n' "$BADGING" | grep -q "uses-permission: name='android.permission.$permission'" \
    || fail "APK 缺少权限 android.permission.$permission"
done
if printf '%s\n' "$BADGING" | grep -q "uses-permission: name='android.permission.ACCESS_NETWORK_STATE'"; then
  fail "APK 不应包含 android.permission.ACCESS_NETWORK_STATE"
fi
log "权限检查通过：包含 CAMERA、INTERNET，不含 ACCESS_NETWORK_STATE"

# 版本号与 SDK 级别需与源配置一致。
printf '%s\n' "$BADGING" | grep -q "versionCode='$EXPECTED_VERSION'" \
  || fail "versionCode 与 app/build.gradle.kts 不一致（期望 $EXPECTED_VERSION）"
printf '%s\n' "$BADGING" | grep -q "versionName='$VERSION_NAME'" \
  || fail "versionName 与 app/build.gradle.kts 不一致（期望 $VERSION_NAME）"
MIN_SDK="$(printf '%s\n' "$BADGING" | sed -n "s/^\(min\|sdk\)SdkVersion:'\([0-9]*\)'.*/\2/p" | head -n 1)"
[ "$MIN_SDK" = "$EXPECTED_MIN_SDK" ] || fail "minSdkVersion 与 app/build.gradle.kts 不一致（期望 $EXPECTED_MIN_SDK，实际 ${MIN_SDK:-未知}）"
log "元数据检查通过：包名 $ACTUAL_PACKAGE，versionCode $EXPECTED_VERSION，versionName $VERSION_NAME，minSdk $MIN_SDK"

# 签名：Debug 构建应带有 APK 签名；Debug 包使用本机 Android Debug 证书。
APKSIGNER="$SDK_DIR/build-tools/35.0.0/apksigner"
if [ -f "$APKSIGNER" ]; then
  SIGN_OUT="$(mktemp)"
  # apksigner 脚本通过 PATH 查找 java，因此显式把 JAVA_HOME/bin 放在最前面。
  if ! PATH="$JAVA_HOME/bin:$PATH" "$APKSIGNER" verify --print-certs "$APK_PATH" >"$SIGN_OUT" 2>&1; then
    cat "$SIGN_OUT" >&2
    rm -f "$SIGN_OUT"
    fail "APK 签名校验失败"
  fi
  if grep -q "CN=Android Debug" "$SIGN_OUT"; then
    log "签名检查通过（Android Debug 证书，仅用于安装测试）"
  else
    log "签名检查通过（证书不是 Android Debug）"
  fi
  rm -f "$SIGN_OUT"
else
  log "警告：未找到 apksigner，跳过签名检查"
fi

# ---------- 输出 ----------

mkdir -p "$OUT_DIR"
OUT_APK="$OUT_DIR/camera-agent-$VERSION_NAME-debug.apk"
cp -f "$APK_PATH" "$OUT_APK"
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$OUT_DIR" && sha256sum "$(basename "$OUT_APK")" > "$(basename "$OUT_APK").sha256")
elif command -v shasum >/dev/null 2>&1; then
  (cd "$OUT_DIR" && shasum -a 256 "$(basename "$OUT_APK")" > "$(basename "$OUT_APK").sha256")
else
  log "警告：未找到 sha256sum/shasum，未生成校验文件"
fi

SIZE_BYTES="$(wc -c < "$OUT_APK" | tr -d ' ')"
log "构建成功"
log "APK：$OUT_APK"
log "大小：$((SIZE_BYTES / 1024 / 1024)) MB（$SIZE_BYTES 字节）"
if [ -f "$OUT_APK.sha256" ]; then
  log "SHA-256：$(cut -d' ' -f1 "$OUT_APK.sha256")"
fi
log "这是用 Android Debug 证书签名的测试包，仅用于安装验证，不能作为正式发布版本分发。"
