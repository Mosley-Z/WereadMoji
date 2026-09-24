#!/usr/bin/env bash
# 微读墨记（微信读书周报）—— 免 Gradle 构建脚本（Windows / Git Bash）
# 依赖：JDK 17+、Android SDK(build-tools 33.0.2 + platforms;android-33)、Python 3
# 用法：bash tools/build.sh          # 发布包（默认；行为与"引入 --dev 开关之前"完全一致）
#       bash tools/build.sh --dev    # dev 变体：manifest 里插入 android:debuggable="true"
#                                    #   ⇒ 打开 MainActivity 的 `am start … --es api_key` 调试入口
#                                    #   ⇒ 输出名带 -dev-debug 后缀
#                                    #   🔴 仍用 **release 签名**（否则覆盖安装不上，测试等于白做）
#                                    #   🔴 不生成 update.json、不写 dist/.last_version_code
# 可移植：所有路径自动探测，也可用环境变量覆盖：JAVA_HOME / ANDROID_HOME / PYTHON / TOOLS_DIR
set -e

# ---- 参数解析（v0.6.1，TASK-003）----
# 只加开关，**不改默认路径的任何行为**（签名、manifest、update.json 三处都不许受影响）。
DEV=0
for _a in "$@"; do
  case "$_a" in
    --dev) DEV=1 ;;
    -h|--help)
      echo "用法： bash tools/build.sh [--dev]"
      echo "  无参数  = 发布包（默认）"
      echo "  --dev   = 加 android:debuggable 的 dev 变体，仍用 release 签名、不动发布清单"
      exit 0 ;;
    *)
      echo "错误：未知参数 '$_a'（只认 --dev / --help）" >&2
      exit 2 ;;
  esac
done

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

# JDK 版本提示（v0.5.3）：首选 17，其它版本能跑但没有实测过
_JV="$("$_JAVA_BIN/java.exe" -version 2>&1 | head -1)"
case "$_JV" in
  *'"17'*) ;;
  *) echo "⚠️  警告：JDK 不是 17（$_JV）。本项目验证过的组合是 JDK 17 + build-tools 33.0.2 + android-33。" ;;
esac

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
# ---- 选 build-tools 与 platform ----
# 首选「33.0.2 + android-33」= 本项目**唯一验证过**的组合（v0.5.3 起）：
# 外部审查用 JDK 21 + build-tools 34 时 d8 直接抛 NullPointerException。
# 旧脚本是**静默**退档，用户看不出自己没跑在验证过的组合上 —— 现在退档会打警告，
# 也可以用 BT_VERSION / PLATFORM_VERSION 显式指定。
BT_WANT="${BT_VERSION:-33.0.2}"
JAR_WANT="${PLATFORM_VERSION:-33}"
BT_P=""
if [ -d "$SDK_P/build-tools/$BT_WANT" ]; then
  BT_P="$SDK_P/build-tools/$BT_WANT"
else
  for v in 33.0.2 34.0.0 32.0.0 31.0.0 30.0.3; do
    [ -d "$SDK_P/build-tools/$v" ] && BT_P="$SDK_P/build-tools/$v" && break
  done
  if [ -n "$BT_P" ]; then
    echo "⚠️  警告：未找到 build-tools $BT_WANT，退到 $(basename "$BT_P")。已验证的组合是 33.0.2。"
  fi
fi
[ -n "$BT_P" ] || { echo "错误：未找到 build-tools（请用 sdkmanager 装 build-tools;$BT_WANT）"; exit 1; }
JAR_P=""
if [ -f "$SDK_P/platforms/android-$JAR_WANT/android.jar" ]; then
  JAR_P="$SDK_P/platforms/android-$JAR_WANT/android.jar"
else
  for v in 33 34 32 31 30; do
    [ -f "$SDK_P/platforms/android-$v/android.jar" ] && JAR_P="$SDK_P/platforms/android-$v/android.jar" && break
  done
  if [ -n "$JAR_P" ]; then
    echo "⚠️  警告：未找到 platforms;android-$JAR_WANT，退到 $(basename "$(dirname "$JAR_P")")。已验证的组合是 android-33。"
  fi
fi
[ -n "$JAR_P" ] || { echo "错误：未找到 platforms/android-*/android.jar（请用 sdkmanager 装 platforms;android-$JAR_WANT）"; exit 1; }

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
# dev 构建**不参与**发版链路 ⇒ 跳过"versionCode 必须递增"检查，也**不写**这个状态文件。
# （不然同一 VERSION_CODE 先跑发布包、再跑 dev 包，第 2 次会直接报错退出。）
if [ -f "$LVC_FILE" ] && [ -z "$SKIP_VERSION_CHECK" ] && [ "$DEV" = "0" ]; then
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
if [ "$DEV" = "1" ]; then
  echo "    ⚠️  DEV 变体：manifest 将插入 android:debuggable=\"true\"（仍用 release 签名）"
fi
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$DIST"

echo "==> 1/7 编译资源 (aapt2 compile)"
"$BT_P/aapt2.exe" compile --dir "$SRC_W\\res" -o "$BUILD_W\\res.zip"

echo "==> 2/7 链接资源并生成 R.java (aapt2 link)"
# dev 变体：让 aapt2 往 manifest 的 <application> 里插 android:debuggable="true"。
# build-tools 33.0.2 `aapt2 link --help` 原文：
#   "Inserts android:debuggable=\"true\" in to the application node of the manifest,
#    making the application debuggable even on production devices."
# ⇒ dev 变体**不需要手改 AndroidManifest.xml**，一个开关就够。
AAPT_DEBUG=""
if [ "$DEV" = "1" ]; then AAPT_DEBUG="--debug-mode"; fi
"$BT_P/aapt2.exe" link \
  -o "$BUILD_W\\app-unsigned.apk" \
  -I "$JAR_W" \
  --manifest "$SRC_W\\AndroidManifest.xml" \
  --java "$BUILD_W\\gen" \
  $AAPT_DEBUG \
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

echo "==> 6/7 对齐 + 签名（APK Signature Scheme v3 轮换）"
"$BT_P/zipalign.exe" -f -p 4 "$BUILD_W\\app-dex.apk" "$BUILD_W\\app-aligned.apk"

# ---- 签名密钥：只从仓库外的私有目录读取，绝不入库 ----
# 应用签名唯一的意义 = 证明「这个更新来自原作者」。私钥一旦公开即永久失效，
# 因此本脚本【绝不】使用调试签名、也绝不自动生成密钥；缺任何一个文件就构建失败。
# 默认目录 $PROJ/../_keys，可用 KEYS_DIR 覆盖。
KEYS_DIR="${KEYS_DIR:-$PROJ/../_keys}"
KS_NEW="$KEYS_DIR/wereadmoji-release.keystore"   # release 私钥（PKCS12，离线保管）
KS_NEW_PASS="$KEYS_DIR/release.pass"             # 它的口令（纯文本单行）
LINEAGE="$KEYS_DIR/lineage.bin"                  # 旧 key → 新 key 的轮换证明
KS_OLD="$KEYS_DIR/old/debug.keystore"            # 轮换链起点（历史遗留的旧签名者）
KS_OLD_PASS="$KEYS_DIR/old/pass.txt"             # 旧 key 口令

_miss=""
for _f in "$KS_NEW" "$KS_NEW_PASS" "$LINEAGE" "$KS_OLD" "$KS_OLD_PASS"; do
  [ -f "$_f" ] || _miss="$_miss $_f"
done
if [ -n "$_miss" ]; then
  echo "错误：缺少签名密钥文件，构建中止（不会退回调试签名）。"
  echo "      缺失:$_miss"
  echo
  echo "  在 $KEYS_DIR/ 下应存在："
  echo "    wereadmoji-release.keystore  release 私钥（RSA4096）"
  echo "    release.pass                 它的口令（纯文本单行）"
  echo "    lineage.bin                  旧 key → 新 key 的轮换证明（apksigner rotate 生成）"
  echo "    old/debug.keystore           轮换链起点"
  echo "    old/pass.txt                 旧 key 口令"
  echo "  也可用 KEYS_DIR=/path/to/keys 指定别处。详见 README「从源码构建」。"
  exit 1
fi

# dev 变体加后缀 ⇒ **绝不与发布包同名**（避免 dev 包被误当发布包挂上 Release）
OUT_SUFFIX=""
if [ "$DEV" = "1" ]; then OUT_SUFFIX="-dev-debug"; fi
OUT_P="$DIST/weread-stats-$VERSION_NAME$OUT_SUFFIX.apk"
OUT_W="$(w "$OUT_P")"

# 旧 key 在前 —— v1/v2 恒由 lineage 里最老的签名者签，因此 Android 6–8（只认 v1/v2）
# 看到的签名者与历史版本完全一致，能无缝覆盖安装；
# 新 key 用 --next-signer + --lineage，API 28+ 走 v3 轮换拿到新证书。
# 注意：只给新 signer 会报 "oldest signer ... is missing"，必须同时给出最老的签名者。
"$BT_P/apksigner.bat" sign \
  --ks "$(w "$KS_OLD")" --ks-pass "file:$(w "$KS_OLD_PASS")" --ks-key-alias androiddebugkey \
  --next-signer \
  --ks "$(w "$KS_NEW")" --ks-pass "file:$(w "$KS_NEW_PASS")" --ks-key-alias wereadmoji \
  --lineage "$(w "$LINEAGE")" \
  --rotation-min-sdk-version 28 \
  --out "$OUT_W" "$BUILD_W\\app-aligned.apk"

echo "    签名方案与签名者："
"$BT_P/apksigner.bat" verify --print-certs "$OUT_W" | head -8

# ---- dev 变体到此为止：**绝不触碰发布链路** ----
# 不生成 update.json、不写 dist/.last_version_code（这两样都是"发布"的产物；
# 一次 dev 构建若覆盖了它们，发布清单就被悄悄改掉了）。
if [ "$DEV" = "1" ]; then
  echo
  echo "==> 完成(dev): $OUT_P"
  echo "    ⏭  已跳过 update.json 与 dist/.last_version_code（发布链路未被触碰）"
  echo "    ⚠️  dev 包留在 dist/ 会让发版门禁 1 FAIL ⇒ 发版前删掉它："
  echo "         rm -f \"$OUT_P\""
  ls -l "$DIST"
  exit 0
fi

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
echo "  2. 给这次提交打 tag：v$VERSION_NAME，然后 push 这个 tag"
echo "     （清单里的下载地址用 tag 引用，不打 tag 则下载 404）"
echo "  3. push tag 后 GitHub Actions 会自动建 Release 并把 APK 挂上去"
echo "     （.github/workflows/release.yml，用自带 GITHUB_TOKEN，无需密钥）"
echo "  4. 打 tag 后 App 即可检查到 v$VERSION_NAME"
echo "──────────────────────────────────────────────────────"
