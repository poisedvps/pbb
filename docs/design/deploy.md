# 部署设计：测试服务器

> 需求来源：SWO-1「自动部署排班表项目」。需求方确认：一次性部署（不做持续部署）、目标为**测试环境**、执行者可访问内网服务器、服务器已装 Docker、对外端口 **8097**、**M1 完成后再部署**。

## 1. 方案

沿用 M0 的 `scripts/deploy.sh` → `scripts/remote-deploy.sh`（rsync 同步 → 服务器上 `docker compose up -d --build` → `/api/health` 检查），只做两处增强：

| 增强 | 原因 |
|---|---|
| 服务器对外端口由 `scripts/deploy.env` 的 `PBB_HTTP_PORT` 指定，写入服务器 `.env` | 服务器要用 8097；本地开发仍是 8090，`.env.example` 和 M1 验收命令不变 |
| `SUDO_NOPASS=1` 时用 `sudo -n`，不读 sudo 密码 | 执行者是非交互运行，不能也不应持有服务器密码 |

数据库是 compose 内的 PostgreSQL 容器（测试数据），不连接任何外部库。

## 2. 凭据与服务器准备（由人工完成，任务单中不出现）

1. 修改服务器登录用户与 root 密码（原密码已在任务描述中明文暴露），并从任务描述中删除。
2. 把执行者所在机器的 SSH 公钥加入服务器登录用户的 `~/.ssh/authorized_keys`。
3. 在执行者机器的仓库目录创建 `scripts/deploy.env`（git 忽略），填写 `DEPLOY_HOST`、`DEPLOY_USER`、`DEPLOY_DIR`、`SSH_KEY`，并设 `PBB_HTTP_PORT=8097`、`SUDO_NOPASS=1`。
4. 在服务器用 `visudo -f /etc/sudoers.d/pbb-deploy` 添加（路径按实际部署目录）：
   ```
   <登录用户> ALL=(root) NOPASSWD: /usr/bin/bash <部署目录>/scripts/remote-deploy.sh *
   ```
   注意：该脚本会被部署同步覆盖，因此这条规则**等价于该用户拥有 root 权限**，仅适用于测试服务器。

## 3. 任务单

| 编号 | 内容 | 依赖 |
|---|---|---|
| D-01 | 部署脚本支持自定义端口与免密 sudo | 无 |
| D-02 | 部署 M1 到测试服务器 | D-01、M1 全部合并、第 2 节人工准备完成 |

---

## D-01 让部署脚本支持自定义服务器端口与免密 sudo

**背景**：测试服务器对外端口要用 8097，且部署由非交互的执行者运行，不能输入 sudo 密码。

**允许修改的文件**
- `scripts/deploy.sh`
- `scripts/remote-deploy.sh`
- `scripts/deploy.env.example`
- `README.md`（只改「部署」一节）

**禁止修改**：`.env.example`、`docker-compose.yml`、`docs/**`、`backend/**`、`frontend/**`。不要创建 `scripts/deploy.env` 或 `.env` 并提交。

**接口定义**

```bash
# scripts/deploy.env.example 末尾新增两行（含注释）
# 服务器对外 HTTP 端口，写入服务器 .env；不填则保持服务器 .env 原值
PBB_HTTP_PORT=8097
# 1 = 服务器已为 remote-deploy.sh 配置免密 sudo，不再询问密码；0 = 询问密码
SUDO_NOPASS=0

# remote-deploy.sh 新的调用方式
#   bash remote-deploy.sh <部署目录> [端口]
# 环境变量 PBB_ENV_ONLY=1：只准备 .env 就退出 0（用于测试，不启动 docker）
```

**实现要点**
1. `remote-deploy.sh`：读取 `PORT_ARG="${2:-}"`；若非空，校验为 1–65535 的纯数字，否则输出 `端口无效：<值>` 并 `exit 1`（在生成 `.env` 之前校验）。
2. `remote-deploy.sh`：保留原有首次生成 `.env` 的逻辑；之后若 `PORT_ARG` 非空，用可移植方式更新端口：`grep -v '^PBB_HTTP_PORT=' .env > .env.tmp || true; echo "PBB_HTTP_PORT=$PORT_ARG" >> .env.tmp; mv .env.tmp .env; chmod 600 .env`（不要用 `sed -i`）。
3. `remote-deploy.sh`：紧接着若 `PBB_ENV_ONLY=1`，输出 `==> 仅准备 .env，跳过启动` 并 `exit 0`。
4. `deploy.sh`：`source` 之后设置默认值 `SUDO_NOPASS="${SUDO_NOPASS:-0}"`、`PBB_HTTP_PORT="${PBB_HTTP_PORT:-}"`。
5. `deploy.sh`：仅当 `SUDO_NOPASS` 不等于 `1` 时才读取 sudo 密码（保留原有 `-t 0` 判断）。
6. `deploy.sh`：最后一步改为：`SUDO_NOPASS=1` 时执行 `"${SSH[@]}" "sudo -n bash '$DEPLOY_DIR/scripts/remote-deploy.sh' '$DEPLOY_DIR' '$PBB_HTTP_PORT'"`；否则沿用原来的 `sudo -S` 管道，同样追加 `'$PBB_HTTP_PORT'` 参数。
7. `README.md`「部署」一节：说明 `PBB_HTTP_PORT`、`SUDO_NOPASS` 两个配置项；访问地址写成 `http://<服务器地址>:<PBB_HTTP_PORT>`。

**验收标准**（在仓库根目录执行，全部通过）

```bash
# T0 语法检查 → 无输出，退出码 0
bash -n scripts/deploy.sh && bash -n scripts/remote-deploy.sh

# 准备临时目录
T=$(mktemp -d); cp .env.example "$T/"

# T1 首次：生成 .env 且端口为 8097
PBB_ENV_ONLY=1 bash scripts/remote-deploy.sh "$T" 8097
grep '^PBB_HTTP_PORT=' "$T/.env"          # → PBB_HTTP_PORT=8097（只有这一行）
grep -c '请填写' "$T/.env"                 # → 0
DB1=$(grep '^DB_PASSWORD=' "$T/.env")

# T2 再次：端口更新为 8098，数据库密码不变
PBB_ENV_ONLY=1 bash scripts/remote-deploy.sh "$T" 8098
grep '^PBB_HTTP_PORT=' "$T/.env"          # → PBB_HTTP_PORT=8098（只有这一行）
[ "$(grep '^DB_PASSWORD=' "$T/.env")" = "$DB1" ] && echo same   # → same

# T3 不传端口：保持 8098
PBB_ENV_ONLY=1 bash scripts/remote-deploy.sh "$T"
grep '^PBB_HTTP_PORT=' "$T/.env"          # → PBB_HTTP_PORT=8098

# T4 非法端口 → 输出「端口无效：abc」，退出码 1
PBB_ENV_ONLY=1 bash scripts/remote-deploy.sh "$T" abc; echo $?     # → 1
PBB_ENV_ONLY=1 bash scripts/remote-deploy.sh "$T" 70000; echo $?   # → 1

# T5 没有 deploy.env → 输出「缺少 scripts/deploy.env…」，退出码 1（确认本机没有 scripts/deploy.env）
bash scripts/deploy.sh </dev/null; echo $?                          # → 1

# T6 git status 中没有 .env、scripts/deploy.env
rm -rf "$T"
```

**规模**：约 40 行。**依赖**：无。

---

## D-02 部署 M1 到测试服务器

**背景**：M1 完成后，把 `main` 部署到测试服务器，供需求方试用。

**前置条件**（派单前由规划者确认）：D-01 与 M1 全部任务单已合并到 `main`；第 2 节人工准备已完成（执行者机器上已有 `scripts/deploy.env`，SSH 免密登录可用）。

**允许修改的文件**：无（只执行部署，不改代码、不提交）。

**禁止**：在评论、日志、提交中写出服务器地址、用户名、密码、`.env` 内容；连接测试服务器以外的任何机器；修改服务器上 `.env` 中除端口外的内容。

**实现要点**
1. `git checkout main && git pull`。
2. 确认 `scripts/deploy.env` 存在且 `SUDO_NOPASS=1`、`PBB_HTTP_PORT=8097`；缺失则停止并在任务下说明，不要自行创建。
3. 执行 `scripts/deploy.sh </dev/null`。
4. 若失败，把脚本输出中的错误段落贴到任务评论（先删除其中的地址、密码类信息），不要自行修改服务器配置。

**验收标准**
- `scripts/deploy.sh` 最后输出 `==> 部署成功：{...}`，其中含 `"db":"ok"`，退出码 0。
- 在执行者机器上：`curl -s -o /dev/null -w "%{http_code}" http://<服务器地址>:8097/api/health` → `200`（`<服务器地址>` 从本机 `scripts/deploy.env` 读取，不要写进评论）。
- `curl -s http://<服务器地址>:8097/api/staff` → HTTP 401。
- 评论中注明部署的 `main` 提交号（`git rev-parse --short HEAD`）。初始管理员为 `admin/admin`，部署后需求方首次登录时会被强制改密。

**规模**：无代码改动。**依赖**：D-01、M1-01 ~ M1-15。
