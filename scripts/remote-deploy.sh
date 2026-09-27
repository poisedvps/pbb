#!/usr/bin/env bash
# 在服务器上以 root 执行（由 deploy.sh 调用）
# 用法：bash remote-deploy.sh <部署目录> [端口]
# 环境变量 PBB_ENV_ONLY=1：只准备 .env 就退出 0（用于测试，不启动 docker）
set -euo pipefail
cd "$1"

PORT_ARG="${2:-}"
if [ -n "$PORT_ARG" ]; then
  case "$PORT_ARG" in
    ''|*[!0-9]*) echo "端口无效：$PORT_ARG"; exit 1 ;;
  esac
  if [ "$PORT_ARG" -lt 1 ] || [ "$PORT_ARG" -gt 65535 ]; then
    echo "端口无效：$PORT_ARG"; exit 1
  fi
fi

if [ ! -f .env ]; then
  echo "==> 首次部署：生成 .env（随机密码，仅保存在服务器）"
  sed -e "s|^DB_PASSWORD=.*|DB_PASSWORD=$(openssl rand -hex 16)|" \
      -e "s|^PBB_JWT_SECRET=.*|PBB_JWT_SECRET=$(openssl rand -hex 32)|" \
      -e "s|^PBB_ADMIN_INIT_PASSWORD=.*|PBB_ADMIN_INIT_PASSWORD=Pbb$(openssl rand -hex 4)|" \
      .env.example > .env
  chmod 600 .env
fi

if [ -n "$PORT_ARG" ]; then
  grep -v '^PBB_HTTP_PORT=' .env > .env.tmp || true
  echo "PBB_HTTP_PORT=$PORT_ARG" >> .env.tmp
  mv .env.tmp .env
  chmod 600 .env
fi

if [ "${PBB_ENV_ONLY:-}" = "1" ]; then
  echo "==> 仅准备 .env，跳过启动"
  exit 0
fi

docker compose up -d --build --remove-orphans
docker image prune -f >/dev/null

PORT=$(grep -E '^PBB_HTTP_PORT=' .env | cut -d= -f2)
PORT=${PORT:-8090}
echo "==> 等待服务就绪（端口 $PORT）"
for i in $(seq 1 60); do
  if out=$(curl -fsS "http://127.0.0.1:$PORT/api/health" 2>/dev/null) && echo "$out" | grep -q '"db":"ok"'; then
    echo "==> 部署成功：$out"
    docker compose ps
    exit 0
  fi
  sleep 3
done
echo "!! 健康检查超时，最近日志："
docker compose ps
docker compose logs --tail 80 backend
exit 1
