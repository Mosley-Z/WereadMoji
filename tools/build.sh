#!/usr/bin/env bash
# 微读墨记（微信读书周报）—— 免 Gradle 构建脚本（Windows / Git Bash）
# 依赖：JDK 17+、Android SDK(build-tools 33.0.2 + platforms;android-33)、Python 3
# 用法：bash tools/build.sh
# 可移植：所有路径自动探测，也可用环境变量覆盖：JAVA_HOME / ANDROID_HOME / PYTHON / TOOLS_DIR
set -e

# ---- 工程根目录（先算出来，后面探测要用）----
PROJ="$(cd "$(dirname "$0")/.." && pwd)"

# ---- Git Bash 的 usr/bin（grep/find 等）；把 PortableGit 补回 PATH ----
_pg=""
for g in /c/Users/*/.workbuddy/binaries/PortableGit/versions/*/usr/bin \
         "/c/Program Files/Git/usr/bin" ; do
  [ -x "$g/grep" ] && { _pg="$g"; break; }
done
[ -n "$_pg" ] && export PATH="$_pg:${_pg%/usr/bin}/mingw64/bin:/usr/bin:/bin:$PATH"

# ---- 候选工具根目录（本机把 JDK/SDK 解压在工程同级或上层的 _tools/）----
_cands="$TOOLS_DIR $PROJ/../_tools $PROJ/../../_tools /f/WBStation/_tools"

# ---- 探测 JAVA_HOME ----
if [ -z "$JAVA_HOME" ] || [ ! -d "$(cygpath -u "$JAVA_HOME" 2>/dev/null)" ]; then
  _jdk=""
  for j in $TOOLS_DIR/jdk* \
           $PROJ/../_tools/jdk* $PROJ/../../_tools/jdk* \
           /f/WBStation/_tools/jdk* \
           "/c/Program Files/Java/jdk-17" "/c/Program Files/Java/jdk-21" \
           "/c/Program Files/Java/jdk-11" "/c/Program Files/Eclipse Adoptium/jdk-17"* \
           /c/Users/*/.workbuddy/binaries/jdk*/* \
           /c/Users/*/scoop/apps/temurin17/current ; do
    if [ -x "$j/bin/javac.exe" ]; then _jdk="$j"; break; fi
  done
  if [ -z "$_jdk" ]; then
    _jc="$(type -P javac.exe 2>/dev/null || type -P javac 2>/dev/null)"
    [ -n "$_jc" ] && _jdk="$(cd "$(dirname "$_jc")/.." && pwd)"
  fi
  [ -n "$_jdk" ] || {
    echo "错误：未找到 JDK 17+。请安装后设置 JAVA_HOME，例如："
    echo "  export JAVA_HOME='F:\\WBStation\\_tools\\jdk17'"
    exit 1
  }
  JAVA_HOME="$(cygpath -w "$_jdk")"     # Windows 形式，给 exe / .bat 用
  _JAVA_BIN="$_jdk/bin"                  # Unix 形式，给 bash 用
fi
[ -n "$_JAVA_BIN" ] || _JAVA_BIN="$(cygpath -u "$JAVA_HOME")/bin"
export JAVA_HOME

# ---- 探测 ANDROID_HOME ----
if [ -z "$ANDROID_HOME" ] || [ ! -d "$(cygpath -u "$ANDROID_HOME" 2>/dev/null)" ]; then
  _sdk=""
  for s in $TOOLS_DIR/android-sdk \
           $PROJ/../_tools/android-sdk $PROJ/../../_tools/android-sdk \
           /f/WBStation/_tools/android-sdk \
           "/d/Program Files/Android/Sdk" "/c/Program Files/Android/Sdk" \
           /c/Users/*/AppData/Local/Android/Sdk "/c/Android/Sdk" ; do
    [ -d "$s" ] && { _sdk="$s"; break; }
  done
  [ -n "$_sdk" ] || {
    echo "错误：未找到 Android SDK。安装后设置 ANDROID_HOME，例如："
    echo "  export ANDROID_HOME='F:\\WBStation\\_tools\\android-sdk'"
    exit 1
  }
  ANDROID_HOME="$(cygpath -w "$_sdk")"
  _SDK_P="$_sdk"
fi
[ -n "$_SDK_P" ] || _SDK_P="$(cygpath -u "$ANDROID_HOME")"
export ANDROID_HOME

# ---- 探测 python ----
if [ -z "$PYTHON" ] || [ ! -x "$(cygpath -u "$PYTHON" 2>/dev/null)" ]; then
  _py=""
  for p in /c/Users/*/.workbuddy/binaries/python/versions/*/python.exe \
           /c/Users/*/AppData/Local/Programs/Python/Python3*/python.exe \
           "$(type -P python.exe 2>/dev/null)" "$(type -P python 2>/dev/null)" \
           "$(type -P python3 2>/dev/null)" ; do
    [ -n "$p" ] && [ -x "$p" ] && { _py="$p"; break; }
  done
  [ -n "$_py" ] || { echo "错误：未找到 python（请设置 PYTHON）"; exit 1; }
  PYTHON="$(cygpath -w "$_py")"
  _PY="$_py"
fi
[ -n "$_PY" ] || _PY="$(cygpath -u "$PYTHON")"

SDK_P="$_SDK_P"
# ---- 选 build-tools 与 platform（优先 33.0.2 / android-33）----
BT_P=""
for v in 33.0.2 34.0.0 32.0.0 31.0.0 30.0.3; do
  [ -d "$SDK_P/build-tools/$v" ] && BT_P="$SDK_P/build-tools/$v" && break
done
[ -n "$BT_P" ] || { echo "错误：未找到 build-tools（请用 sdkmanager 装 build-tools;33.0.2）"; exit 1; }
JAR_P=""
for v in 33 34 32 31 30; do
  [ -f "$SDK_P/platforms/android-$v/android.jar" ] && JAR_P="$SDK_P/platforms/android-$v/android.jar" && break
done
[ -n "$JAR_P" ] || { echo "错误：未找到 platforms/android-*/android.jar（请用 sdkmanager 装 platforms;android-33）"; exit 1; }

JAVA_BIN="$_JAVA_BIN"
PY="$_PY"

SRC="$PROJ/app/src/main"
BUILD="$PROJ/build"
DIST="$PROJ/dist"

# Windows 风格路径（给 exe 用）
w() { cygpath -w "$1"; }
BT_W="$(w "$BT_P")"; JAR_W="$(w "$JAR_P")"
SRC_W="$(w "$SRC")"; BUILD_W="$(w "$BUILD")"; DIST_W="$(w "$DIST")"

# ---- 版本号：唯一来源 version.properties ----
# 发版只改那一个文件。文件不存在、或字段为空时，才回落到环境变量 / 默认值。
VP="$PROJ/version.properties"
NOTES=""
if [ -f "$VP" ]; then
  _vn="$(grep -E '^VERSION_NAME=' "$VP" | head -1 | cut -d= -f2- | tr -d ' \r')"
  _vc="$(grep -E '^VERSION_CODE=' "$VP" | head -1 | cut -d= -f2- | tr -d ' \r')"
  _nt="$(grep -E '^NOTES='      "$VP" | head -1 | cut -d= -f2- | tr -d '\r')"
  [ -n "$_vn" ] && VERSION_NAME="$_vn"
  [ -n "$_vc" ] && VERSION_CODE="$_vc"
  NOTES="$_nt"
fi
VERSION_NAME="${VERSION_NAME:-0.3.0-dev}"
VERSION_CODE="${VERSION_CODE:-3}"

# VERSION_CODE 必须是递增整数：App 靠它判断有没有新版。
# 写小了或复用了，老版本会认为自己比新版本还新，永远收不到更新。
case "$VERSION_CODE" in
  ''|*[!0-9]*)
    echo "错误：VERSION_CODE 必须是整数（当前 '${VERSION_CODE}'）。请检查 version.properties。"
    exit 1 ;;
esac
LVC_FILE="$DIST/.last_version_code"
if [ -f "$LVC_FILE" ] && [ -z "$SKIP_VERSION_CHECK" ]; then
  _last="$(tr -d ' \r\n' < "$LVC_FILE")"
  if [ -n "$_last" ] && [ "$VERSION_CODE" -le "$_last" ] 2>/dev/null; then
    echo "错误：VERSION_CODE=$VERSION_CODE 未大于上次构建的 $_last。"
    echo "      它必须递增，否则已装老版本的设备永远收不到这次更新。"
    echo "      确认无误可用 SKIP_VERSION_CHECK=1 跳过本检查。"
    exit 1
  fi
fi

echo "==> 工程: $PROJ"
echo "    JDK=$JAVA_HOME"
echo "    SDK=$ANDROID_HOME (build-tools $(basename "$BT_P"), platform $(basename "$(dirname "$JAR_P")"))"
echo "    PY =$PYTHON"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$DIST"

echo "==> 1/7 编译资源 (aapt2 compile)"
"$BT_P/aapt2.exe" compile --dir "$SRC_W\\res" -o "$BUILD_W\\res.zip"

echo "==> 2/7 链接资源并生成 R.java (aapt2 link)"
"$BT_P/aapt2.exe" link \
  -o "$BUILD_W\\app-unsigned.apk" \
  -I "$JAR_W" \
  --manifest "$SRC_W\\AndroidManifest.xml" \
  --java "$BUILD_W\\gen" \
  --min-sdk-version 23 \
  --target-sdk-version 30 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  "$BUILD_W\\res.zip"

echo "==> 3/7 编译 Java (javac)"
"$PY" -c "
import os
root = r'$SRC_W'.replace('/','\\\\')
gen = r'$BUILD_W'.replace('/','\\\\') + r'\\gen'
out = []
for base in (root + r'\\java', gen):
    for d, _, fs in os.walk(base):
        for f in fs:
            if f.endswith('.java'):
                out.append(os.path.join(d, f))
open(r'$BUILD_W'.replace('/','\\\\') + r'\\sources.txt', 'w', encoding='utf-8').write('\n'.join(out))
print('    源文件 %d 个' % len(out))
"
"$JAVA_BIN/javac.exe" -encoding UTF-8 --release 8 \
  -classpath "$JAR_W" \
  -d "$BUILD_W\\classes" \
  "@$BUILD_W\\sources.txt"

echo "==> 4/7 转 dex (d8)"
cd "$BUILD/classes"
D8_JAR_W="$(w "$BT_P/lib/d8.jar")"
"$JAVA_BIN/java.exe" -cp "$D8_JAR_W" com.android.tools.r8.D8 \
  --min-api 23 \
  --lib "$JAR_W" \
  --output "$BUILD_W\\dex" \
  $(find . -name '*.class')
cd "$PROJ"

echo "==> 5/7 打包 dex 进 APK"
"$PY" -c "
import zipfile, shutil, os
src = r'$BUILD_W'.replace('/','\\\\') + r'\\app-unsigned.apk'
dst = r'$BUILD_W'.replace('/','\\\\') + r'\\app-dex.apk'
dex = r'$BUILD_W'.replace('/','\\\\') + r'\\dex\\classes.dex'
shutil.copyfile(src, dst)
z = zipfile.ZipFile(dst, 'a', zipfile.ZIP_DEFLATED)
z.write(dex, 'classes.dex')
z.close()
print('    APK 大小: %d bytes' % os.path.getsize(dst))
"

echo "==> 6/7 对齐 + 签名"
"$BT_P/zipalign.exe" -f -p 4 "$BUILD_W\\app-dex.apk" "$BUILD_W\\app-aligned.apk"
KEYSTORE_P="$PROJ/keystore/debug.keystore"
if [ ! -f "$KEYSTORE_P" ]; then
  mkdir -p "$PROJ/keystore"
  "$JAVA_BIN/keytool.exe" -genkeypair -keystore "$(w "$KEYSTORE_P")" \
    -storepass android -keypass android -alias androiddebugkey \
    -dname "CN=Android Debug,O=Android,C=CN" -keyalg RSA -keysize 2048 -validity 10000
  echo "    已生成调试签名: $KEYSTORE_P"
fi
OUT_P="$DIST/weread-stats-$VERSION_NAME-debug.apk"
OUT_W="$(w "$OUT_P")"
"$BT_P/apksigner.bat" sign --ks "$(w "$KEYSTORE_P")" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT_W" "$BUILD_W\\app-aligned.apk"
"$BT_P/apksigner.bat" verify --print-certs "$OUT_W" | head -4

# ---- 7/7 生成版本清单 update.json（供 App「检查更新」读取）----
echo
echo "==> 7/7 生成版本清单"
APK_NAME="$(basename "$OUT_P")"
APK_REL="dist/$APK_NAME"
APK_SIZE="$(wc -c < "$OUT_P" | tr -d ' \r\n')"
APK_SHA="$("$PY" -c "import hashlib;print(hashlib.sha256(open(r'$OUT_W','rb').read()).hexdigest())")"
UPDATE_JSON="$DIST/update.json"
# 传给 Windows 版 python 的路径必须是 Windows 形式：
# /d/xxx 会被它当成当前盘下的相对路径，解析成 D:\d\xxx 而报找不到文件。
GEN_PY_W="$(w "$PROJ/tools/gen_update_json.py")"
UPDATE_JSON_W="$(w "$UPDATE_JSON")"
REPO="${REPO:-Mosley-Z/WereadMoji}" \
VERSION_NAME="$VERSION_NAME" VERSION_CODE="$VERSION_CODE" NOTES="$NOTES" \
APK_REL="$APK_REL" SIZE="$APK_SIZE" SHA256="$APK_SHA" \
OUT="$UPDATE_JSON_W" \
  "$PY" "$GEN_PY_W" || { echo "错误：生成 update.json 失败"; exit 1; }

# 记录本次构建的 versionCode，供下次构建做递增校验（文件本身不入库）
printf '%s' "$VERSION_CODE" > "$LVC_FILE"

echo
echo "==> 完成: $OUT_P"
echo "         $UPDATE_JSON"
ls -l "$DIST"
echo
echo "──── 发布提醒 ────────────────────────────────────────"
echo "  1. git add -A && git commit && git push"
echo "  2. 给这次提交打 tag：v$VERSION_NAME"
echo "     （清单里的下载地址用 tag 引用，不打 tag 则下载 404）"
echo "  3. 打 tag 后 App 即可检查到 v$VERSION_NAME"
echo "──────────────────────────────────────────────────────"
