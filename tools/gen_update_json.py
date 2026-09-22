#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成供 App「检查更新」使用的版本清单 dist/update.json。

由 tools/build.sh 在构建末尾调用，全部输入经环境变量传入（避免 shell 转义中文）：

    VERSION_NAME   版本名，如 0.5.0
    VERSION_CODE   版本号（递增整数），如 22
    NOTES          更新说明，一行短句
    APK_REL        APK 在仓库内的相对路径，如 dist/weread-stats-0.5.0-debug.apk
    SIZE           APK 字节数
    SHA256         APK 的 sha256（App 下载后校验）
    REPO           GitHub 仓库，如 Mosley-Z/WereadMoji
    OUT            输出路径，如 dist/update.json

清单里的 urls 是给 App 按顺序尝试的下载地址：
    第一个是 jsDelivr（用 tag 引用 → 永久缓存 + CDN 加速，APK 内容不可变正合适），
    第二个是 raw.githubusercontent（缓存仅数分钟，作为回落）。
两者互为备份，任一源不可用仍能更新。
"""
import json
import os
import sys


def env(name, default=""):
    v = os.environ.get(name)
    return default if v is None or v == "" else v


def main():
    version_name = env("VERSION_NAME")
    version_code = env("VERSION_CODE")
    notes = env("NOTES")
    apk_rel = env("APK_REL")
    size = env("SIZE", "0")
    sha256 = env("SHA256")
    repo = env("REPO", "Mosley-Z/WereadMoji")
    out = env("OUT")

    missing = [k for k, v in (
        ("VERSION_NAME", version_name), ("VERSION_CODE", version_code),
        ("APK_REL", apk_rel), ("SHA256", sha256), ("OUT", out),
    ) if not v]
    if missing:
        print("gen_update_json: 缺少环境变量 " + ", ".join(missing), file=sys.stderr)
        return 1

    try:
        code_int = int(version_code)
        size_int = int(size)
    except ValueError:
        print("gen_update_json: VERSION_CODE / SIZE 必须是整数", file=sys.stderr)
        return 1

    tag = "v" + version_name
    data = {
        "versionCode": code_int,
        "versionName": version_name,
        "tag": tag,
        "notes": notes,
        "apk": apk_rel,
        "size": size_int,
        "sha256": sha256,
        "urls": [
            "https://cdn.jsdelivr.net/gh/%s@%s/%s" % (repo, tag, apk_rel),
            "https://raw.githubusercontent.com/%s/%s/%s" % (repo, tag, apk_rel),
        ],
    }

    parent = os.path.dirname(os.path.abspath(out))
    if parent and not os.path.isdir(parent):
        os.makedirs(parent)

    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print("    清单: %s" % out)
    print("    版本: %s (vc=%d)" % (version_name, code_int))
    print("    大小: %d bytes" % size_int)
    print("    sha256: %s" % sha256)
    print("    tag:  %s   ← push 后请务必打此 tag，否则下载地址无效" % tag)
    return 0


if __name__ == "__main__":
    sys.exit(main())
