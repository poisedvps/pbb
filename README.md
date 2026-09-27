# 医院信息科排班表（pbb）

排班管理系统：排班表编辑与发布、我的排班、调班申请、统计报表、节假日与人员管理、大屏全屏展示。

- 设计文档：`docs/design/architecture.md`
- 界面原型：`docs/design/prototype/index.html`（浏览器直接打开）

## 技术栈

Vue 3 + Element Plus ｜ Java 17 + Spring Boot 3.5 ｜ PostgreSQL 16 ｜ Docker Compose

## 目录

```
backend/     Spring Boot 后端（Flyway 迁移在 src/main/resources/db/migration）
frontend/    Vue 3 前端（构建后由 nginx 提供，/api 反代到后端）
docs/design/ 架构设计与原型
scripts/     部署脚本
docker-compose.yml
.env.example 环境变量模板（真实 .env 不入库）
```

## 本地开发

```bash
# 数据库
docker compose up -d db          # 需先复制 .env.example 为 .env 并填写
# 后端（需 JDK 17 + Maven）
cd backend && DB_PASSWORD=<密码> mvn spring-boot:run
# 前端
cd frontend && npm install && npm run dev    # http://localhost:5173
```

## 部署

```bash
cp scripts/deploy.env.example scripts/deploy.env   # 填写服务器信息
scripts/deploy.sh                                  # 输入服务器 sudo 密码
```

`scripts/deploy.env` 中两个可选项：

- `PBB_HTTP_PORT`：服务器对外 HTTP 端口，每次部署写入服务器 `.env`；不填则保持服务器 `.env` 原值。
- `SUDO_NOPASS`：`1` 表示服务器已为 `remote-deploy.sh` 配置免密 sudo，部署时不再询问密码；`0`（默认）表示询问密码。

首次部署会在服务器生成 `.env`（随机数据库密码、JWT 密钥、初始管理员密码），只保存在服务器上。
访问：`http://<服务器地址>:<PBB_HTTP_PORT>`，健康检查：`/api/health`。

常用运维（在服务器部署目录下）：

```bash
sudo docker compose ps
sudo docker compose logs -f backend
sudo docker compose restart backend
sudo docker compose down          # 停止（数据保留在卷 pbb_pbb-pgdata）
```
