# 医院信息科排班表 · 架构设计 v1.1

> 需求来源：需求方确认记录（2026-09-27）；界面以 `docs/design/prototype/index.html`（原型 v0.2）为准。
> v1.1（2026-09-28）：新增暂存草稿、排班周期模板、值班电话（需求 SWO-49，需求方 2026-09-28 确认），增量设计见 §8。

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
| 1304 | shift | 颜色格式应为 #RRGGBB（值班电话底色，M4） |
| 1505–1507 | schedule | 值班电话的周起始日必须是周一 / 该周与本月没有交集 / 排班周期模板不存在（M4） |
| 1203 | staff | 科长不能删除（M5） |
| 1210 | staff | 导入失败（message 列出前 20 处错误，M5） |
| 1800–1804 | cycle | 排班周期模板不存在 / 模板名称已存在 / 默认模板不能删除 / 模板中的班次不存在或已停用 / 至少保留一个默认模板（M4） |

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
| M4 | 暂存草稿、排班周期模板、值班电话及其统计（设计见 §8，任务单见 `docs/design/tasks/M4.md`） |
| M5 | 删除人员（同步清理）、人员批量导入导出、排班表隐藏工号 / 暂存常驻 / 拖动排序（设计见 §9，任务单见 `docs/design/tasks/M5.md`） |

## 8. M4 增量：暂存草稿、排班周期、值班电话（v1.1）

### 8.1 需求确认记录（需求方 2026-09-28）

| # | 需求 | 确认结论 |
|---|---|---|
| 1 | 暂存 | 排班表页改为“先在页面上批量修改，点【暂存】才一次性保存到草稿；点【发布】才对外可见”。有未暂存修改时，切月、离开页面、按规则生成、发布都要先提醒 |
| 2 | 排班周期 | 做成**多种周期模板**：每个模板规定周一至周日各排什么班；按规则生成时选择模板。法定节假日仍为休息 X、调休上班日仍为白班 D（沿用 §1 第 3 条） |
| 3 | 值班电话 | 每周（周一至周日）指定 1 人负责接听值班电话，只能从“参与排班”的人员中选；此人当周 7 天（含周六、周日，与当天班次无关）的格子**整格底色**标亮，班次文字不变。跨月的那一周在两个月的排班表里都标亮。大屏同样标亮，但**不显示电话号码** |
| 4 | 底色 | 默认黄色 `#fde047`，在“班次设置”页可改 |
| 5 | 生效 | 值班电话安排与排班一起走“草稿 → 发布”，发布后成员与大屏才可见 |
| 6 | 统计 | 统计报表增加“值班电话（天）”列，按区间内的天数统计，并进入 Excel 导出 |
| 7 | 调班 | 值班电话不参与调班申请，只由科长在排班表里改 |

### 8.2 模块划分（增量）

| 模块 | 后端包 | 前端 | 新增职责 |
|---|---|---|---|
| cycle（新） | `cycle` | `views/CycleView.vue`（菜单“基础设置 / 排班周期”）、`api/cycles.js` | 周期模板增删改查 |
| shift | `shift` | `views/ShiftsView.vue`、`api/settings.js` | 值班电话底色（`app_setting` 表的唯一写入方） |
| schedule | `schedule` | `views/ScheduleView.vue` | 批量暂存、按模板生成、值班电话草稿与发布 |
| screen | `screen` | `views/ScreenView.vue` | 读 `MonthScheduleVO.dutyPhones` 标亮，后端无改动 |
| stats | `stats` | `views/StatsView.vue` | 值班电话天数、导出列 |

模块边界补充：
- 只有 schedule 写 `duty_phone_week`、`duty_phone_published` 和 `schedule_month.cycle_template_id`；只有 cycle 写 `cycle_template`；只有 shift 写 `app_setting`。
- schedule 只读 `cycle_template`（按规则生成、恢复规则默认）与 `app_setting`（月视图带出底色）；stats 只读 `duty_phone_published`。

### 8.3 数据表（Flyway `V2__cycle_template_duty_phone.sql`）

```sql
-- 系统设置（键值），目前只有值班电话底色一项
CREATE TABLE app_setting (
    setting_key    VARCHAR(64)  PRIMARY KEY,
    setting_value  VARCHAR(200) NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
INSERT INTO app_setting (setting_key, setting_value) VALUES ('duty_phone_color', '#fde047');

-- 排班周期模板：day1..day7 = 周一..周日的班次代号
CREATE TABLE cycle_template (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(32)  NOT NULL UNIQUE,
    day1        VARCHAR(4)   NOT NULL REFERENCES shift_type (code),
    day2        VARCHAR(4)   NOT NULL REFERENCES shift_type (code),
    day3        VARCHAR(4)   NOT NULL REFERENCES shift_type (code),
    day4        VARCHAR(4)   NOT NULL REFERENCES shift_type (code),
    day5        VARCHAR(4)   NOT NULL REFERENCES shift_type (code),
    day6        VARCHAR(4)   NOT NULL REFERENCES shift_type (code),
    day7        VARCHAR(4)   NOT NULL REFERENCES shift_type (code),
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 最多一个默认模板
CREATE UNIQUE INDEX uq_cycle_template_default ON cycle_template (is_default) WHERE is_default;
INSERT INTO cycle_template (name, day1, day2, day3, day4, day5, day6, day7, is_default)
VALUES ('标准周期', 'D', 'D', 'D', 'D', 'D', 'X', 'X', TRUE);

-- 该月最近一次按规则生成所用模板；“恢复规则默认”按它重算。模板被删时置空，退回内置规则
ALTER TABLE schedule_month
    ADD COLUMN cycle_template_id BIGINT REFERENCES cycle_template (id) ON DELETE SET NULL;

-- 值班电话草稿：一周一行，week_start 必须是周一
CREATE TABLE duty_phone_week (
    week_start  DATE         PRIMARY KEY CHECK (EXTRACT(ISODOW FROM week_start) = 1),
    staff_id    BIGINT       NOT NULL REFERENCES staff (id),
    updated_by  BIGINT REFERENCES app_user (id),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 值班电话已发布快照（成员、大屏、统计读）
CREATE TABLE duty_phone_published (
    week_start  DATE         PRIMARY KEY CHECK (EXTRACT(ISODOW FROM week_start) = 1),
    staff_id    BIGINT       NOT NULL REFERENCES staff (id)
);
```

**“某月涉及的周”**：`week_start` 从“该月 1 日所在周的周一”（可能落在上个月）到该月最后一天，即与该月有交集的所有周，通常 5 周，最多 6 周。由 `ScheduleMonths.firstWeekStart(ym)` 统一计算。

### 8.4 接口（增量）

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| GET | `/cycle-templates` | ADMIN | 模板列表，按 id 升序 |
| POST | `/cycle-templates` | ADMIN | `{name, days[7], isDefault}` → `CycleTemplateVO` |
| PUT | `/cycle-templates/{id}` | ADMIN | 同上 |
| DELETE | `/cycle-templates/{id}` | ADMIN | 默认模板不能删（1802） |
| GET | `/settings/duty-phone-color` | 登录 | `{color}` |
| PUT | `/settings/duty-phone-color` | ADMIN | `{color}`，须为 `#RRGGBB`（1304） |
| POST | `/schedules/{yearMonth}/generate?templateId=` | ADMIN | **改**：`templateId` 可省略，省略时用默认模板 |
| PUT | `/schedules/{yearMonth}/draft` | ADMIN | **新**：暂存，`{entries[], dutyPhones[]}` 一次性保存，全部成功或全部不生效 |
| GET | `/schedules/{yearMonth}` | 登录 | **改**：返回体新增 `cycleTemplateId`、`dutyPhoneColor`、`dutyPhones[]` |
| POST | `/schedules/{yearMonth}/publish` | ADMIN | **改**：同时发布该月涉及各周的值班电话 |
| GET | `/stats` · `/stats/export` | ADMIN / MEMBER | **改**：每行新增 `dutyPhoneDays`；导出在“节假日/周末上班”与“总工时”之间新增“值班电话（天）”列 |

`PUT /schedules/{yearMonth}/entries`（单格保存）保留，前端不再使用。

```java
// 周期模板
public record CycleTemplateVO(Long id, String name, List<String> days, boolean isDefault) {}   // days: 7 个班次代号，下标 0=周一
public record CycleTemplateRequest(@NotBlank @Size(max = 32) String name,
                                   @NotNull @Size(min = 7, max = 7) List<@NotBlank String> days,
                                   boolean isDefault) {}
// 值班电话底色
public record DutyPhoneColorVO(String color) {}
// 暂存
public record DutyPhoneChange(@NotNull LocalDate weekStart, Long staffId) {}        // staffId=null 表示清除该周
public record SaveDraftRequest(@NotNull @Size(max = 2000) List<@Valid UpdateEntryRequest> entries,
                               @NotNull @Size(max = 6) List<@Valid DutyPhoneChange> dutyPhones) {}
public record SaveDraftResultVO(int entries, int dutyPhones) {}
// 月视图新增
public record DutyPhoneVO(LocalDate weekStart, LocalDate weekEnd, Long staffId, String name) {}
// MonthScheduleVO(yearMonth, status, version, publishedAt, draft, days, rows,
//                 Long cycleTemplateId, String dutyPhoneColor, List<DutyPhoneVO> dutyPhones)
// StatsRowVO(staffId, empNo, name, counts, offDayWork, totalHours, int dutyPhoneDays)
```

### 8.5 关键流程

**按模板生成**（改 §4 算法中的默认值计算，其余不变）：

```
template = templateId != null ? 按 id 取（不存在 → 1507） : 默认模板（没有默认模板 → null）
template 中任何一天的班次不存在或已停用 → 1502
默认班次(d)：
  d 为调休上班日（WORKDAY 类型节假日）→ D
  d 为放假日（HOLIDAY 类型节假日）     → X
  否则                                 → template == null ? (周末 X / 工作日 D) : template.days[星期几-1]
生成结束后 schedule_month.cycle_template_id = template?.id
```

“恢复规则默认”（单格或暂存里 `shiftCode=null`）按该月 `cycle_template_id` 对应模板计算；为空时用内置规则。

**暂存 `PUT /schedules/{ym}/draft`**：
1. 先校验全部内容，任何一条不合法就报错、一条都不写：格子日期在该月内（1503）、人员有效（1501）、班次启用（1502）；值班电话 `weekStart` 是周一（1505）、该周与该月有交集（1506）、`staffId` 非 null 时人员有效（1501）。
2. 取锁：该月，以及每个值班电话周所跨的月份（`YearMonth.from(weekStart)`、`YearMonth.from(weekStart+6)`），去重后升序逐个 `lockMonth`。
3. 逐格写草稿，写法与单格保存相同（含每格一条 `修改排班` 日志）。
4. 逐周写值班电话草稿：`staffId` 为 null 删除该周，否则新增或覆盖；每周一条 `设置值班电话` 日志，detail 为姓名或“清除”。
5. 涉及的月份（第 2 步的全部月份）都打回 DRAFT；记一条 `暂存排班` 日志，detail=`{n}格，值班电话{m}周`。

**发布**（在 §4 发布流程后追加）：删除 `duty_phone_published` 中该月涉及各周的行，再把 `duty_phone_week` 中同范围的行整体复制过去。加锁时若该月第一周的周一落在上个月，先取上个月的锁再取本月的锁（升序），避免与上个月的发布同时写同一周。
跨月那一周以**最近一次发布（无论哪个月）**时的草稿为准，两个月的排班表显示同一个人。

**标亮**：前端对每个 `dutyPhones[i]`，把 `staffId` 对应行中 `weekStart..weekEnd` 且落在本月的格子 `td` 背景设为 `dutyPhoneColor`；班次色块与文字不变。科长看草稿，成员与大屏看已发布。

**统计**：`dutyPhoneDays` = 该人在 `duty_phone_published` 中各周的 7 天与 `[from, to]` 交集的天数之和（与当天班次无关）。

### 8.6 安全

- 所有写接口仅 ADMIN（`@PreAuthorize("hasRole('ADMIN')")`）；新增写操作都记操作日志。
- 大屏与值班电话相关的展示只出现姓名，不出现手机号；月视图 `DutyPhoneVO` 不含手机号字段。

## 9. M5 增量：删除人员、人员导入导出、排班表调整（v1.2）

### 9.1 需求确认记录（需求方 2026-09-30，SWO-69 评论）

| # | 需求 | 确认结论 |
|---|---|---|
| 1 | 删除人员 | 人员管理页的“停用 / 启用”改为“删除”。删除后该人员的**全部**相关数据同步删除：登录账号、草稿与**已发布**排班（含历史月份）、值班电话草稿与已发布、本人发起或作为对方的调班申请、与本人相关的操作日志。删除本身另记一条日志：`删除人员`，target=工号，**不写姓名和手机号** |
| 2 | 删除权限 | 只有科长能删；**角色为科长的人员不能删除**（按钮不显示，后端拒绝 1203） |
| 3 | 批量导出 | 人员管理页【批量导出】，导出 xlsx，含手机号，仅科长可用 |
| 4 | 批量导入 | 人员管理页【批量导入】，上传与导出同格式的 xlsx：工号已存在则更新姓名/岗位/联系电话/参与排班（不改角色）；任何一行有错则**整批不导入**并列出错误；新人员自动建账号，初始密码写在导入成功后下载的“导入结果” xlsx 中，只出现这一次，首次登录强制改密 |
| 5 | 暂存 | “暂存”是整张排班表的暂存：按钮在表格加载完成后**始终可点**，不再要求先改格子。没有未暂存修改时点击只提示“排班表草稿已是最新”，不发请求（按规则生成的结果已直接写入草稿） |
| 6 | 隐藏工号 | 排班表页姓名下方不再显示工号；排班表导出 Excel 去掉“工号”列；搜索框仍可按工号搜 |
| 7 | 排序 | 科长在排班表页直接拖动姓名单元格调整人员顺序；与人员管理页共用同一个排序号，大屏、导出同步 |

### 9.2 模块划分（增量）

| 模块 | 新增职责 |
|---|---|
| staff | `DELETE /staff/{id}` 编排删除；`GET /staff/export`；`POST /staff/import`；`StaffImportParser` 解析与校验 |
| schedule | `ScheduleService.purgeStaff`：删该人员的 `schedule_entry`、`schedule_published_entry`、`duty_phone_week`、`duty_phone_published`，并把 `updated_by` / `published_by` 中该账号置空 |
| swap | `SwapService.purgeStaff`：删该人员相关的 `swap_request`，并把 `reviewed_by` 中该账号置空 |
| oplog | `OpLogService.purgeStaff`：按 9.4 的规则删日志 |
| 前端 | `StaffView.vue`（删除、导入、导出）、`ScheduleView.vue`（隐藏工号、暂存常驻、拖动排序）、`api/staff.js`、`api/download.js` |

模块边界不变：staff 不直接写 schedule / swap / oplog 的表，只调用上述三个 `purgeStaff`。**不新增迁移脚本**。

### 9.3 接口（增量）

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| DELETE | `/staff/{id}` | ADMIN | 删除人员及全部相关数据；不存在 1200；科长 1203 |
| GET | `/staff/export` | ADMIN | xlsx，文件名 `人员名单.xlsx`，在职人员按排序号 |
| POST | `/staff/import` | ADMIN | `multipart/form-data`，字段 `file`；成功返回 xlsx 文件 `人员导入结果.xlsx`；失败返回 `{code:1210, message}`，不写任何数据 |
| PUT | `/staff/order` | ADMIN | 不变；排班表页拖动后调用，body 为排班表当前所有行的 staffId |

导入 / 导出列（顺序固定，表头逐字相同）：`工号 | 姓名 | 岗位 | 联系电话 | 参与排班 | 角色`。“参与排班”填“是 / 否”（空=是）；“角色”填“科长 / 成员”（空=成员，只对新人员生效）。
导入结果列：`工号 | 姓名 | 结果 | 初始密码`，结果为“新增 / 更新”，更新行的初始密码为空。

### 9.4 关键流程

**删除人员**（`StaffService.delete`，一个事务）：
1. 取人员（不存在 1200）与其账号 `user`（可能没有）；`user.role == ADMIN` → 1203。
2. `nameUnique = staffRepo.countByName(name) == 1`。
3. `scheduleService.purgeStaff(staffId, userId)` → `swapService.purgeStaff(staffId, userId)` → `opLog.purgeStaff(userId, empNo, name, nameUnique)`。
4. 删账号，删人员。
5. **最后**记 `opLog.record(删除人员, empNo, null)`（必须在第 3 步之后，否则会被第 3 步按工号删掉）。

**删日志规则**（`OpLogService.purgeStaff`）：
- `user_id = 该账号 id`（本人登录、改密等）；
- `target = 工号`（新增 / 修改人员、登录失败、重置密码等）；
- 仅当 `nameUnique` 且姓名不含 `%`、`_` 时：`action = 修改排班 AND target LIKE '姓名 %'`，以及 `action = 设置值班电话 AND detail = 姓名`。姓名与在册他人重复时跳过这两条，避免误删别人的日志。

**导入**（`StaffService.importStaff`，一个事务）：解析校验（9.5）→ 查库校验（新工号不得与已有账号用户名重复，否则“第 n 行：工号 xxx 已被其他账号占用”）→ 有任何错误抛 1210 → 逐行新增或更新（新增与单个新增共用同一段代码，照常记 `新增人员` / `修改人员` 日志）→ 记 `导入人员`，target=`人员导入`，detail=`新增{a}人，更新{b}人`。

**暂存常驻**：按钮禁用条件由 `pendingCount === 0 || monthLocked || saving || loading` 改为 `!data || monthLocked || saving || loading`；`pendingCount === 0` 时点击只提示，不发请求。

**拖动排序**：仅科长、搜索框为空、表格可编辑时，姓名单元格可拖动；放下后本地先重排，再 `PUT /staff/order`；失败则重新加载该月。排班表只列出“在职且参与排班”的人，未列出的人保持原排序号（与人员管理页“不显示已停用时调整顺序”的现有行为一致，排序号相同时按 id 排）。

### 9.5 导入校验（`StaffImportParser`，纯函数，不查库）

- 文件读不开 → “文件无法读取，请使用【批量导出】得到的 xlsx 文件”
- 第 1 个工作表第 1 行表头不等于 6 列 → “表头应为：工号、姓名、岗位、联系电话、参与排班、角色”
- 6 列全空的行跳过；数据行 0 行 → “文件里没有人员数据”；超过 500 行 → “一次最多导入 500 人”
- 单元格一律用 `DataFormatter` 取文本并去首尾空格（数字工号 `1001`、手机号不会变成科学计数法）
- 工号 `^[A-Za-z0-9_]{3,32}$`；姓名必填且 ≤ 32；岗位、联系电话 ≤ 32；参与排班 ∈ {空, 是, 否}；角色 ∈ {空, 科长, 成员}；文件内工号不得重复
- 错误信息格式 `第{n}行：…`，n 为 Excel 行号（表头是第 1 行）；**错误信息里不得出现手机号**

### 9.6 安全

- 新接口全部 `@PreAuthorize("hasRole('ADMIN')")`（`StaffController` 类上已有）。
- 删除不可恢复：前端要求输入该人员姓名确认；后端 1203 兜底科长。
- 导出含手机号，仅科长；导入结果含初始密码，只在该次响应中出现，后端不保存明文。
- 新增日志动作：`删除人员`、`导入人员`、`导出人员`；日志中不写手机号、密码。
