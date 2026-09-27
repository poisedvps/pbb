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
- 所有密钥（数据库密码、JWT 密钥）只存在服务器上的 `.env`（git 忽略），仓库只有 `.env.example` 占位符。初始管理员密码例外，见 §6。
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

模块边界：**只有 schedule 模块写 `schedule_month` / `schedule_entry` / `schedule_published_entry`**；swap 审批通过后调用 `ScheduleService.applyChanges`，不直接写表。跨模块**只读**可以直接用对方 Repository 的查询方法，**写**必须经过对方 Service。

schedule 模块内部分为 `ScheduleQueryService`（只读：月视图、我的排班、规则日历）和 `ScheduleService`（写：生成、改格、发布、调班回写）；导出放在单独的 `ScheduleExportController`。

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
| GET | `/schedules/{yearMonth}/export` | 登录 | xlsx（ADMIN 导出草稿，其他导出已发布） |
| GET | `/schedules/mine?yearMonth=` | 登录 | 本人已发布排班、各班次天数、工时、下一个班次 |
| GET | `/screen?yearMonth=` | SCREEN / ADMIN | 整月已发布 + 今日概况；yearMonth 缺省为当月 |
| GET/POST | `/swaps?scope=ALL\|MINE\|TODO` · `/swaps` | ADMIN / MEMBER | 列表 / 发起 |
| POST | `/swaps/{id}/confirm` · `/reject-peer` | 对方成员 | |
| POST | `/swaps/{id}/cancel` | 申请人 | 待确认、待审批时可撤销 |
| POST | `/swaps/{id}/approve` · `/reject` | ADMIN | 通过后调用 schedule 模块回写并记日志 |
| GET | `/stats?from=&to=` · `/stats/export` | ADMIN / MEMBER（MEMBER 仅本人） | 口径见 §5.4 |
| GET | `/logs?page=&size=&username=&action=` | ADMIN | page 从 0 开始，倒序 |

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
| 1500–1504 | schedule | 月份格式应为 YYYY-MM / 人员不存在或不参与排班 / 班次不存在或已停用 / 日期不在该月内 / 本月还没有排班 |
| 1600–1609 | swap | 申请不存在 / 账号未关联人员 / 没有已发布的班次（含“排班已变化”） / 只能申请今天及以后 / 对方不能是自己 / 对方人员无效 / 缺少对方人员或日期 / 该日期已有进行中的申请 / 当前状态不允许此操作 / 无权操作该申请 |
| 1700–1701 | stats | 开始日期晚于结束日期 / 统计范围超过 366 天 |

### 5.2 排班数据结构

- 月视图 `MonthScheduleVO { yearMonth, status, version, publishedAt, draft, days[], rows[] }`。`days[i] = { date, weekday(1-7), kind, holidayName }`，`rows[i] = { staffId, empNo, name, position, cells{ "YYYY-MM-DD": { shiftCode, manual, remark } } }`。
- `kind` 为 `WORKDAY`、`WEEKEND`、`HOLIDAY`、`ADJUSTED_WORKDAY`（调休上班），由 `RuleCalendar` 统一计算。优先级：调休上班 > 放假 > 周末 > 工作日。
- 行为 `active && schedulable` 的人员，按 `sort_order` 排序。
- 写排班的操作都按月取 `pg_advisory_xact_lock(1500, 年*100+月)`，与 holiday 的 1400 号段区分。

### 5.3 调班规则

| 类型 | 需对方确认 | 审批通过后改动（A=申请人、B=对方，按审批时的已发布班次计算） |
|---|---|---|
| SWAP 换班 | 是 | 对 {A 日期, B 日期} 的每一天 d：A@d 与 B@d 互换 |
| LEAVE 请假 | 否，直接待审批 | A@A日期 → L |
| COVER 替班 | 是 | B@A日期 → A 原班次；A@A日期 → X |

- 状态流转：`PENDING_PEER` →（对方同意）`PENDING_ADMIN` →（科长通过）`APPROVED`；对方拒绝或科长驳回 → `REJECTED`；申请人在两个待处理状态下可撤销 → `CANCELLED`。
- 发起时校验：涉及的格子都必须有已发布班次，日期不早于今天，同一申请人同一天只能有一条进行中的申请。
- 回写：同时改草稿（`is_manual=true`，remark=`调班 TB-xxxx`）和已发布快照；不改月份状态和版本号，所以无需重新发布，成员立即可见。

### 5.4 统计口径

- 数据源：`schedule_published_entry`，范围 `[from, to]`，最长 366 天。
- 各班次天数：按班次代码计数。
- 节假日/周末上班：班次 `counts_as_work=true`，且当天为 `WEEKEND` 或 `HOLIDAY` 的天数；调休上班日不算。
- 总工时：各格对应班次当前的 `work_hours` 之和。

## 6. 安全规范

- 密码 BCrypt 存储；库中没有 `admin` 时，启动时自动创建初始管理员 `admin`，`must_change_password=true`。
- 初始管理员密码固定默认为 `admin`（需求方 2026-09-27 确认）：第一次登录成功后强制跳转到改密页，改密前除 `/auth/me`、`/auth/change-password`、`/auth/logout` 外的接口都返回 4031。`.env` 中的 `PBB_ADMIN_INIT_PASSWORD` 若填了非占位值就以它为准；为空、`change-me` 或 `<...>` 占位符时一律使用 `admin`。
- 风险：新环境部署后到科长首次改密之前，内网任何人都能用 `admin/admin` 登录。部署后应立即登录改密。
- 认证：登录返回 JWT（HS256，密钥来自 `.env`，有效期 12 小时；SCREEN 账号 30 天）；前端存 `localStorage`，请求头 `Authorization: Bearer`。
- 所有写操作记录 `operation_log`；日志不得输出密码、token、完整手机号。
- 接口层按角色鉴权（`@PreAuthorize`），前端隐藏菜单只是体验，不是安全手段。
- 本系统不接入 HIS，不涉及患者数据。

## 7. 里程碑

| 里程碑 | 内容 |
|---|---|
| M0 | 项目骨架 + Docker 部署跑通（本次完成） |
| M1 | 认证与账号、人员、班次、节假日（任务单见 `docs/design/tasks/M1.md`） |
| M2 | 排班表（生成/编辑/发布）、我的排班、大屏（任务单见 `docs/design/tasks/M2.md`） |
| M3 | 调班申请、统计报表、导出、操作日志、本机部署与冒烟验收（任务单见 `docs/design/tasks/M3.md`） |
