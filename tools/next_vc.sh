#!/usr/bin/env bash
# 微读墨记 —— 全局 versionCode 账本（双通道发布 · TASK-019）
# ---------------------------------------------------------------------------
# 用途：任何一次发版（正式版 main / Beta beta），VERSION_CODE 都应 =
#       「全局已发布最大值 + 1」。本脚本遍历仓库里所有 v* tag，从每个 tag
#       自身的 version.properties 读 VERSION_CODE，打印 max+1（纯整数）。
#
# 🔴 为什么从 **tag** 读、而**不读工作区文件**：
#     tag 是仓库级、跨分支共享；而工作区的 version.properties 只是"当前分支"
#     的那一份 —— 在 beta 分支上它是 Beta 版，直接读工作区会在切分支后算错。
#     判据（卡内验收 A3）：切到 beta、工作区版本名含 -beta 时，输出仍是同一个整数。
#
# 只读：不写任何文件、不改 git 工作区状态（卡内验收 A2：连跑两次结果一致、
#       git status 逐行相同）。
#
# 容错（卡内验收 A4）：遇到没有 version.properties（或字段非法）的 tag
#       **跳过并在 stderr 警告**，不崩、不以非零码退出。
#
# 用法：
#   bash tools/next_vc.sh          # stdout 只打印建议的 vc 整数（便于脚本消费）
#                                  # 人类可读的"当前全局最大 vc=… @ tag"走 stderr
# 发版 checklist 第一步永远是它（纪律 D4）。
# ---------------------------------------------------------------------------
set -euo pipefail

# 仓库根：脚本位于 <repo>/tools/ 下
REPO="$(cd "$(dirname "$0")/.." && pwd)"

max=0
max_tag=""

for t in $(git -C "$REPO" tag -l 'v*'); do
  # 从 tag 自身取该提交里的 version.properties；取不到就跳过（fail-soft）
  raw="$(git -C "$REPO" show "$t:version.properties" 2>/dev/null || true)"
  # 只认形如 VERSION_CODE=<纯数字> 的行；兼容行尾 \r（Windows 检出）
  vc="$(printf '%s\n' "$raw" \
        | sed -n 's/^VERSION_CODE=\([0-9][0-9]*\).*/\1/p' \
        | head -1)"

  if [ -z "$vc" ]; then
    echo "⚠️  跳过 $t：没有可读的 VERSION_CODE（无 version.properties 或字段非法）" >&2
    continue
  fi

  if [ "$vc" -gt "$max" ] 2>/dev/null; then
    max="$vc"
    max_tag="$t"
  fi
done

# 人类提示走 stderr，别污染 stdout 的整数
echo "（当前全局最大 vc=$max @ ${max_tag:-无} ⇒ 建议下一个 vc=$((max + 1))）" >&2
echo $((max + 1))
