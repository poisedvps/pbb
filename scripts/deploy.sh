#!/usr/bin/env bash
# 一键部署：同步代码到服务器 → 构建镜像 → 启动容器 → 健康检查
# 用法：scripts/deploy.sh          （会提示输入服务器 sudo 密码）
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f scripts/deploy.env ] || { echo "缺少 scripts/deploy.env，请从 deploy.env.example 复制并填写"; exit 1; }
# shellcheck disable=SC1091
source scripts/deploy.env
SSH_KEY="${SSH_KEY/#\~/$HOME}"
SSH=(ssh -i "$SSH_KEY" -p "$DEPLOY_PORT" -o BatchMode=yes "$DEPLOY_USER@$DEPLOY_HOST")

if [ -t 0 ]; then read -rsp "服务器 sudo 密码: " SUDO_PW; echo; else read -r SUDO_PW; fi

echo "==> 同步代码到 $DEPLOY_HOST:$DEPLOY_DIR"
"${SSH[@]}" "mkdir -p '$DEPLOY_DIR'"
rsync -az --delete \
  --exclude .git --exclude .env --exclude 'scripts/deploy.env' \
  --exclude node_modules --exclude frontend/dist --exclude backend/target --exclude .DS_Store \
  -e "ssh -i $SSH_KEY -p $DEPLOY_PORT -o BatchMode=yes" \
  ./ "$DEPLOY_USER@$DEPLOY_HOST:$DEPLOY_DIR/"

echo "==> 服务器端构建并启动"
printf '%s\n' "$SUDO_PW" | "${SSH[@]}" "sudo -S -p '' bash '$DEPLOY_DIR/scripts/remote-deploy.sh' '$DEPLOY_DIR'"
