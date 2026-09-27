#!/usr/bin/env bash
# 在服务器上以 root 执行（由 deploy.sh 调用）
set -euo pipefail
cd "$1"

if [ ! -f .env ]; then
  echo "==> 首次部署：生成 .env（随机密码，仅保存在服务器）"
  sed -e "s|^DB_PASSWORD=.*|DB_PASSWORD=$(openssl rand -hex 16)|" \
      -e "s|^PBB_JWT_SECRET=.*|PBB_JWT_SECRET=$(openssl rand -hex 32)|" \
      -e "s|^PBB_ADMIN_INIT_PASSWORD=.*|PBB_ADMIN_INIT_PASSWORD=Pbb$(openssl rand -hex 4)|" \
      .env.example > .env
  chmod 600 .env
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
