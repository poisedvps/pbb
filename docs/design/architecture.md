# 医院信息科排班表 · 架构设计 v1.0

> 需求来源：需求方确认记录（2026-09-27）；界面以 `docs/design/prototype/index.html`（原型 v0.2）为准。

## 1. 需求要点（已确认）

| # | 内容 |
|---|---|
| 1 | 班次固定 6 种：白班 D、夜班 N、值班 Z、备班 B、请假 L、休息 X（可在班次设置中改时间/名称，不增删代码） |
| 2 | 人员不分组、人数不限，按 `sort_order` 排序显示 |
| 3 | 规则：周一至周五白班，双休日与节假日休息；“调休上班”日按工作日。节假日由科长每年录入 |
| 4 | 夜班/值班/备班/请假由科长手工调整；手工格子标记 `is_manual`，“按规则生成”不覆盖 |
| 5 | 系统自带账号密码；角色：`ADMIN`（科长）、`MEMBER`（成员）、`SCREEN`（大屏只读） |
| 6 | 仅科长可编辑、发布排班、审批调班 |
| 7 | 不做消息推送 |
| 8 | 内网 PC 使用；大屏页全屏显示**整月**已发布排班，自动刷新，人多时自动翻页 |

## 2. 技术栈与部署

| 层 | 选型 |
|---|---|
| 前端 | Vue 3 + Vite + Element Plus + Vue Router + Pinia + Axios |
| 后端 | Java 17 + Spring Boot 3.5 + Spring Data JPA + Flyway + Spring Security（会话/JWT 见 §6） |
| 数据库 | PostgreSQL 16 |
| 部署 | Docker Compose：`web`(nginx，静态资源 + `/api` 反代) / `backend` / `db` |

```
浏览器 ──:8090──> web(nginx) ──/api──> backend:8080 ──> db:5432（不对外暴露）
```

- 服务器部署方案与端口见 `docs/design/deploy.md`（测试服务器对外端口 8097，本地开发仍为 8090）。
- 所有密钥（数据库密码、JWT 密钥、初始管理员密码）只存在服务器上的 `.env`（git 忽略），仓库只有 `.env.example` 占位符。
- 时区统一 `Asia/Shanghai`；日期字段用 `date`，时间戳用 `timestamptz`。

## 3. 模块划分

| 模块 | 后端包 `com.hospital.pbb.*` | 前端 | 职责 |
|---|---|---|---|
| common | `common` | `api/http.js` | 统一返回体、异常处理、分页 |
| auth | `auth` | `views/LoginView.vue`、`stores/auth.js` | 登录、登出、改密、锁定、当前用户 |
| user | `user` | `views/UsersView.vue` | 账号管理（重置密码、解锁、停用） |
| staff | `staff` | `views/StaffView.vue` | 人员增删改、排序；新增人员自动建账号 |
| shift | `shift` | `views/ShiftsView.vue` | 班次查询与编辑 |
| holiday | `holiday` | `views/HolidayView.vue` | 节假日按年增删改、复制上一年 |
| schedule | `schedule` | `views/ScheduleView.vue`、`views/MineView.vue` | 排班查询、单元格修改、按规则生成、发布、导出 |
| screen | `screen` | `views/ScreenView.vue` | 大屏数据（整月已发布 + 今日概况） |
| swap | `swap` | `views/SwapView.vue` | 调班申请、对方确认、科长审批并回写排班 |
| stats | `stats` | `views/StatsView.vue` | 按人统计班次/工时，导出 |
| oplog | `oplog` | `views/LogsView.vue` | 操作日志记录与查询 |

模块边界：**只有 schedule 模块写 `schedule_entry` / `schedule_published_entry`**；swap 审批通过后调用 `ScheduleService` 的公开方法，不直接写表。

## 4. 数据表（Flyway `V1__init_schema.sql`）

| 表 | 说明 | 关键字段 |
|---|---|---|
| `staff` | 人员 | `emp_no` 唯一、`name`、`position`、`phone`、`schedulable`、`sort_order`、`active` |
| `app_user` | 账号 | `username` 唯一、`password_hash`(BCrypt)、`role`、`staff_id`、`enabled`、`must_change_password`、`failed_attempts`、`locked_until` |
| `shift_type` | 班次 | `code`(D/N/Z/B/L/X) 唯一、`name`、`start_time`、`end_time`、`cross_day`、`work_hours`、`color`、`counts_as_work` |
| `holiday` | 节假日 | `year`、`name`、`start_date`、`end_date`、`type`(HOLIDAY/WORKDAY) |
| `schedule_month` | 月度状态 | `year_month`(YYYY-MM) 唯一、`status`(DRAFT/PUBLISHED)、`version`、`published_at`、`published_by` |
| `schedule_entry` | 排班草稿（科长编辑） | `(staff_id, work_date)` 唯一、`shift_code`、`is_manual`、`remark` |
| `schedule_published_entry` | 已发布快照（成员/大屏读） | `(staff_id, work_date)` 唯一、`shift_code`、`version` |
| `swap_request` | 调班申请 | `type`(SWAP/LEAVE/COVER)、申请人及日期、对方及日期、`status` |
| `operation_log` | 操作日志 | `user_id`、`username`、`action`、`target`、`detail`、`ip` |

发布 = 删除该月 `schedule_published_entry` 后整体复制 `schedule_entry`，`version+1`。草稿修改后 `schedule_month.status` 回到 `DRAFT`，已发布快照保持不变直到再次发布。

### 规则生成算法（`ScheduleService.generateByRule(yearMonth)`）

```
for 每个 schedulable 且 active 的人员 s:
  for 该月每一天 d:
    若 (s,d) 已存在且 is_manual = true → 跳过
    若 d 属于 WORKDAY 类型节假日        → D
    否则若 d 属于 HOLIDAY 类型节假日    → X
    否则若 d 是周六或周日               → X
    否则                                → D
    upsert (s,d,shift,is_manual=false)
```

## 5. 接口清单（前缀 `/api`，返回体 `{code, message, data}`，`code=0` 成功）

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| GET | `/health` | 匿名 | 健康检查（含数据库连通性） |
| POST | `/auth/login` | 匿名 | `{username,password}` → `{token, user}`；连续失败 5 次锁定 15 分钟 |
| POST | `/auth/logout` | 登录 | |
| GET | `/auth/me` | 登录 | 当前用户、角色、`mustChangePassword` |
| POST | `/auth/change-password` | 登录 | `{oldPassword,newPassword}`，≥8 位含字母和数字 |
| GET/POST/PUT | `/staff`、`/staff/{id}` | ADMIN | 人员列表/新增/修改；新增时自动建账号（用户名 = 工号，角色 ADMIN 或 MEMBER） |
| PUT | `/staff/order` | ADMIN | `[id...]` 保存排序（界面用“上移/下移”按钮，不做拖拽） |
| GET | `/users` | ADMIN | 账号列表 |
| POST | `/users` | ADMIN | 新增**大屏账号**（仅 SCREEN 角色；ADMIN/MEMBER 账号随人员创建） |
| POST | `/users/{id}/reset-password` · `/unlock` · `/disable` · `/enable` | ADMIN | |
| GET/PUT | `/shift-types`、`/shift-types/{code}` | 登录 / ADMIN | |
| GET | `/holidays?year=` | 登录 | |
| POST/PUT/DELETE | `/holidays`、`/holidays/{id}` | ADMIN | |
| POST | `/holidays/copy?fromYear=&toYear=` | ADMIN | |
| GET | `/schedules/{yearMonth}` | 登录 | ADMIN 读草稿，其他读已发布 |
| PUT | `/schedules/{yearMonth}/entries` | ADMIN | `{staffId, workDate, shiftCode|null}`；null=恢复规则默认 |
| POST | `/schedules/{yearMonth}/generate` | ADMIN | 按规则生成 |
| POST | `/schedules/{yearMonth}/publish` | ADMIN | |
| GET | `/schedules/{yearMonth}/export` | 登录 | xlsx |
| GET | `/schedules/mine?yearMonth=` | 登录 | 本人已发布排班 |
| GET | `/screen?yearMonth=` | SCREEN / ADMIN | 整月已发布 + 今日概况 |
| GET/POST | `/swaps` | 登录 | 列表 / 发起 |
| POST | `/swaps/{id}/confirm` · `/reject-peer` | 对方成员 | |
| POST | `/swaps/{id}/approve` · `/reject` | ADMIN | 通过后调用 schedule 模块回写并记日志 |
| GET | `/stats?from=&to=` · `/stats/export` | 登录（MEMBER 仅本人） | |
| GET | `/logs?page=&size=` | ADMIN | |

### 5.1 错误码

业务错误统一 HTTP 200 + `{code, message}`；鉴权错误用 HTTP 401/403。

| 范围 | 模块 | 已定义 |
|---|---|---|
| 400 / 401 / 403 / 404 / 500 | 通用 | 参数错误 / 未登录或登录已过期 / 无权限 / 接口不存在 / 系统错误 |
| 4031 | 通用 | 请先修改初始密码（HTTP 403） |
| 1001–1006 | auth | 用户名或密码错误 / 账号已锁定 / 账号已停用 / 原密码错误 / 新密码强度不足 / 新旧密码相同 |
| 1100–1102 | user | 账号不存在 / 不能停用自己 / 只能新增大屏账号 |
| 1200–1202 | staff | 人员不存在 / 工号已存在 / 人员角色只能是科长或成员 |
| 1300–1303 | shift | 班次不存在 / 白班和休息不能停用 / 上下班时间需同时填写 / 下班时间须晚于上班时间 |
| 1400–1405 | holiday | 节假日不存在 / 结束早于开始 / 不能跨年 / 日期重叠 / 目标年已有数据 / 源年份无数据 |

## 6. 安全规范

- 密码 BCrypt 存储；初始管理员 `admin` 首次启动时由 `.env` 的 `PBB_ADMIN_INIT_PASSWORD` 创建，`must_change_password=true`。
- 认证：登录返回 JWT（HS256，密钥来自 `.env`，有效期 12 小时；SCREEN 账号 30 天）；前端存 `localStorage`，请求头 `Authorization: Bearer`。
- 所有写操作记录 `operation_log`；日志不得输出密码、token、完整手机号。
- 接口层按角色鉴权（`@PreAuthorize`），前端隐藏菜单只是体验，不是安全手段。
- 本系统不接入 HIS，不涉及患者数据。

## 7. 里程碑

| 里程碑 | 内容 |
|---|---|
| M0 | 项目骨架 + Docker 部署跑通（本次完成） |
| M1 | 认证与账号、人员、班次、节假日（任务单见 `docs/design/tasks/M1.md`） |
| M2 | 排班表（生成/编辑/发布）、我的排班、大屏 |
| M3 | 调班申请、统计报表、导出、操作日志 |
