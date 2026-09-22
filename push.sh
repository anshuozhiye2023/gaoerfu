#!/usr/bin/env bash
# 一条命令把改动同时推到 Gitee（主仓）和 GitHub（编译机）。
#
# 用法：
#   ./push.sh "描述这次改了什么"
#   ./push.sh                      # 不给描述就用时间戳
#
# 前置：两个 remote 都配好了（见 IOS-SIDELOAD.md §1B）
#   git remote add gitee  git@gitee.com:<用户名>/<仓库名>.git
#   git remote add github https://github.com/<用户名>/<仓库名>.git
#
# 为什么是「同时推两个」而不是「只推 Gitee 让它自动同步」：
#   Gitee 的仓库镜像功能要会员/企业版，个人没开的时候干脆手动双推，一样自动化。

set -euo pipefail

MSG="${1:-update $(date '+%Y-%m-%d %H:%M')}"

# 提醒：H5 改了要同步到两处原生副本，否则编出来的还是旧界面。
# （工作流里有 SHA-256 校验，真忘了同步会在 CI 上直接红，这里只是提前拦一下。）
if [ -n "$(git status --porcelain ../GolfAppWeb 2>/dev/null || true)" ]; then
  echo "⚠  ../GolfAppWeb 有未提交改动 —— 如果是改 H5，记得先同步到："
  echo "     ios/GolfApp/index.html"
  echo "     android/app/src/main/assets/index.html"
  echo "   （忘了也没事，CI 的包内 H5 校验会拦下来）"
fi

git add -A
if git diff --cached --quiet; then
  echo "没有需要提交的改动。"
else
  git commit -m "$MSG"
fi

for r in gitee github; do
  if git remote | grep -qx "$r"; then
    echo "→ 推送到 $r …"
    git push "$r" HEAD
  else
    echo "! 未配置 remote：$r （跳过，配置方法见 IOS-SIDELOAD.md §1B）"
  fi
done

echo
echo "完成。看着 GitHub 的 Actions 页面：全绿后在该次 run 的 Artifacts 里下载 GolfApp-unsigned-ipa。"
