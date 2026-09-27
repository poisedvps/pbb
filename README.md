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

## 本机部署与冒烟测试

在本机（装有 Docker 的 Mac）做一次全新部署：

```bash
cp .env.example .env      # DB_PASSWORD 用 openssl rand -hex 16、PBB_JWT_SECRET 用 openssl rand -hex 32 生成
                          # PBB_ADMIN_INIT_PASSWORD 留空（即用默认的 admin），PBB_HTTP_PORT 保持默认 8090
docker compose down -v    # -v 清掉本机测试库数据，只允许在本机执行
docker compose up -d --build
curl -s http://localhost:8090/api/health    # 出现 "db":"ok" 即启动完成（最多约 3 分钟）
```

访问 <http://localhost:8090>，初始账号 `admin/admin`，首次登录会强制修改密码。

部署完成后跑一遍端到端冒烟测试（只依赖 `curl` 和 `python3`）：

```bash
ADMIN_PW=<科长新密码> scripts/smoke-test.sh
# 等价于 BASE_URL=http://localhost:8090 ADMIN_PW=<科长新密码> scripts/smoke-test.sh
```

脚本依次跑健康检查、科长登录改密、新增成员、生成/发布排班、我的排班、请假、换班、统计与导出、操作日志、大屏账号十个步骤，每步打印 `[PASS] 步骤名` 或 `[FAIL] 步骤名: 原因`，全部通过输出 `ALL PASS`。脚本只建带时间戳的测试数据，不打印任何密码；科长新密码建议记在仓库根目录的 `.smoke-admin-password`（`chmod 600`，该文件已被 git 忽略）。
