#!/usr/bin/env bash
#
# 端到端冒烟测试：在本机部署好的环境上跑一遍主要业务流程（任务单 M3-16）。
#
#   用法：BASE_URL=http://localhost:8090 ADMIN_PW=<科长新密码> scripts/smoke-test.sh
#   依赖：curl、python3（解析 JSON，不依赖 jq）
#
# 每一步打印 "[PASS] 步骤名" 或 "[FAIL] 步骤名: 原因"，任一步失败立即 exit 1，
# 全部通过打印 "ALL PASS" 并 exit 0。输出里不打印任何密码。

set -u

BASE_URL="${BASE_URL:-http://localhost:8090}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PW="${ADMIN_PW:-}"
TS="$(date +%s)"

# 本轮专用的测试数据：工号、大屏账号都带时间戳，重复执行不会和上一轮撞唯一键
SMKA="SMK${TS}A"
SMKB="SMK${TS}B"
MEMBER_PW="Smoke${TS}x1"
SCREEN_USER="screen${TS}"

STEP="依赖检查"
for cmd in curl python3; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "[FAIL] ${STEP}: 缺少依赖 ${cmd}"; exit 1; }
done
[ -n "$ADMIN_PW" ] || { echo "[FAIL] ${STEP}: 请先设置环境变量 ADMIN_PW"; exit 1; }

STEP=""
pass() { echo "[PASS] ${STEP}"; }
fail() { echo "[FAIL] ${STEP}: $1"; exit 1; }

# api METHOD PATH TOKEN [JSON] → 响应体（TOKEN 传空串表示不带登录态）
api() {
  local method="$1" path="$2" token="$3" body="${4:-}"
  local args=(-s -X "$method" "${BASE_URL}${path}" -H 'Content-Type: application/json')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer ${token}")
  [ -n "$body" ] && args+=(-d "$body")
  curl "${args[@]}"
}

# http_meta METHOD PATH TOKEN → "HTTP状态 响应content-type"（导出和大屏越权检查用）
http_meta() {
  local method="$1" path="$2" token="$3"
  local args=(-s -o /dev/null -w '%{http_code} %{content_type}' -X "$method" "${BASE_URL}${path}")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer ${token}")
  curl "${args[@]}"
}

# jget 'data.token' ← 标准输入，按点路径取 JSON 里的值；取不到输出空串
jget() {
  python3 -c '
import json, sys

def pick(node, keys):
    for key in keys:
        if isinstance(node, list) and key.isdigit() and int(key) < len(node):
            node = node[int(key)]
        elif isinstance(node, dict):
            node = node.get(key)
        else:
            return None
    return node

try:
    node = pick(json.load(sys.stdin), sys.argv[1].split("."))
except Exception:
    node = None
if isinstance(node, bool):
    node = "true" if node else "false"
elif isinstance(node, (dict, list)):
    node = json.dumps(node, ensure_ascii=False)
print("" if node is None else node)
' "$1"
}

# jpick 'data.rows' staffId 7 cells.2026-10-20.shiftCode ← 先在数组里按 key=value 找一行，再取字段
jpick() {
  python3 -c '
import json, sys

def pick(node, path):
    for key in path.split("."):
        if isinstance(node, list) and key.isdigit() and int(key) < len(node):
            node = node[int(key)]
        elif isinstance(node, dict):
            node = node.get(key)
        else:
            return None
    return node

try:
    rows = pick(json.load(sys.stdin), sys.argv[1])
except Exception:
    rows = None
found = None
for row in rows or []:
    if isinstance(row, dict) and str(row.get(sys.argv[2])) == sys.argv[3]:
        found = row
        break
node = pick(found, sys.argv[4]) if found is not None else None
if isinstance(node, bool):
    node = "true" if node else "false"
elif isinstance(node, (dict, list)):
    node = json.dumps(node, ensure_ascii=False)
print("" if node is None else node)
' "$1" "$2" "$3" "$4"
}

# code_of BODY → 响应体里的 code（错误原因只打印 code+message，绝不回显整个响应体）
err_of() {
  echo "code=$(printf '%s' "$1" | jget code) message=$(printf '%s' "$1" | jget message)"
}

# login USERNAME PASSWORD → token（失败输出空串）
login() {
  api POST /api/auth/login "" "{\"username\":\"$1\",\"password\":\"$2\"}" | jget data.token
}

# ---------------------------------------------------------------- 步骤 1
STEP="1 健康检查"
HEALTH="$(api GET /api/health '')"
case "$HEALTH" in
  *'"db":"ok"'*) pass ;;
  *) fail "响应里没有 \"db\":\"ok\"：$(err_of "$HEALTH")" ;;
esac

# ---------------------------------------------------------------- 步骤 2
STEP="2 科长登录"
FIRST="$(api POST /api/auth/login '' "{\"username\":\"${ADMIN_USER}\",\"password\":\"admin\"}")"
if [ "$(printf '%s' "$FIRST" | jget code)" = "0" ] \
   && [ "$(printf '%s' "$FIRST" | jget data.user.mustChangePassword)" = "true" ]; then
  # 还是初始密码：先改成 $ADMIN_PW，再用新密码登录，确认改密真的生效
  ADMIN_TOKEN="$(printf '%s' "$FIRST" | jget data.token)"
  CHANGED="$(api POST /api/auth/change-password "$ADMIN_TOKEN" \
    "{\"oldPassword\":\"admin\",\"newPassword\":\"${ADMIN_PW}\"}")"
  [ "$(printf '%s' "$CHANGED" | jget code)" = "0" ] || fail "改初始密码失败：$(err_of "$CHANGED")"
fi
TOKEN="$(login "$ADMIN_USER" "$ADMIN_PW")"
[ -n "$TOKEN" ] || fail "科长无法登录（首次登录请检查 admin/admin，已改密请检查 ADMIN_PW）"
pass

# ---------------------------------------------------------------- 步骤 3
STEP="3 新增两名成员并改密"
BODY_A="$(api POST /api/staff "$TOKEN" \
  "{\"empNo\":\"${SMKA}\",\"name\":\"冒烟甲\",\"position\":\"冒烟测试\",\"phone\":\"\",\"schedulable\":true,\"role\":\"MEMBER\"}")"
[ "$(printf '%s' "$BODY_A" | jget code)" = "0" ] || fail "新增冒烟甲失败：$(err_of "$BODY_A")"
SID_A="$(printf '%s' "$BODY_A" | jget data.staff.id)"
[ -n "$SID_A" ] || fail "响应里没有 data.staff.id"
TMP_A="$(printf '%s' "$BODY_A" | jget data.tempPassword)"
[ -n "$TMP_A" ] || fail "响应里没有 data.tempPassword"

BODY_B="$(api POST /api/staff "$TOKEN" \
  "{\"empNo\":\"${SMKB}\",\"name\":\"冒烟乙\",\"position\":\"冒烟测试\",\"phone\":\"\",\"schedulable\":true,\"role\":\"MEMBER\"}")"
[ "$(printf '%s' "$BODY_B" | jget code)" = "0" ] || fail "新增冒烟乙失败：$(err_of "$BODY_B")"
SID_B="$(printf '%s' "$BODY_B" | jget data.staff.id)"
TMP_B="$(printf '%s' "$BODY_B" | jget data.tempPassword)"
[ -n "$SID_B" ] && [ -n "$TMP_B" ] || fail "冒烟乙缺少 staff.id 或 tempPassword"

# 临时密码只能改密，改完重新登录拿正式 token
MA="$(login "$SMKA" "$TMP_A")"
[ -n "$MA" ] || fail "冒烟甲用临时密码登录失败"
CHANGED="$(api POST /api/auth/change-password "$MA" \
  "{\"oldPassword\":\"${TMP_A}\",\"newPassword\":\"${MEMBER_PW}\"}")"
[ "$(printf '%s' "$CHANGED" | jget code)" = "0" ] || fail "冒烟甲改密失败：$(err_of "$CHANGED")"
MA="$(login "$SMKA" "$MEMBER_PW")"
[ -n "$MA" ] || fail "冒烟甲用新密码登录失败"

MB="$(login "$SMKB" "$TMP_B")"
[ -n "$MB" ] || fail "冒烟乙用临时密码登录失败"
CHANGED="$(api POST /api/auth/change-password "$MB" \
  "{\"oldPassword\":\"${TMP_B}\",\"newPassword\":\"${MEMBER_PW}\"}")"
[ "$(printf '%s' "$CHANGED" | jget code)" = "0" ] || fail "冒烟乙改密失败：$(err_of "$CHANGED")"
MB="$(login "$SMKB" "$MEMBER_PW")"
[ -n "$MB" ] || fail "冒烟乙用新密码登录失败"
pass

# ---------------------------------------------------------------- 步骤 4
# 先 -v1d 把日置到 1 号再加一个月：月末（如 1-31）直接 +1m 会溢出到下下个月
NM="$(date -v1d -v+1m +%Y-%m)"
STEP="4 ${NM} 生成、改格子、发布"
GENERATED="$(api POST "/api/schedules/${NM}/generate" "$TOKEN")"
[ "$(printf '%s' "$GENERATED" | jget code)" = "0" ] || fail "生成失败：$(err_of "$GENERATED")"

D15="${NM}-15"
PUT_OK="$(api PUT "/api/schedules/${NM}/entries" "$TOKEN" \
  "{\"staffId\":${SID_A},\"workDate\":\"${D15}\",\"shiftCode\":\"N\",\"remark\":\"\"}")"
[ "$(printf '%s' "$PUT_OK" | jget code)" = "0" ] || fail "${D15} 改成 N 失败：$(err_of "$PUT_OK")"
[ "$(printf '%s' "$PUT_OK" | jget data.shiftCode)" = "N" ] || fail "${D15} 返回的不是 N"

PUBLISHED="$(api POST "/api/schedules/${NM}/publish" "$TOKEN")"
[ "$(printf '%s' "$PUBLISHED" | jget code)" = "0" ] || fail "发布失败：$(err_of "$PUBLISHED")"
VERSION="$(printf '%s' "$PUBLISHED" | jget data.version)"
case "$VERSION" in '' | *[!0-9]*) fail "响应里的 version 不是数字：${VERSION}" ;; esac
[ "$VERSION" -ge 1 ] || fail "version=${VERSION} 应 ≥ 1"
pass

# ---------------------------------------------------------------- 步骤 5
STEP="5 成员看我的排班（${D15} 为 N）"
MINE="$(api GET "/api/schedules/mine?yearMonth=${NM}" "$MA")"
[ "$(printf '%s' "$MINE" | jget code)" = "0" ] || fail "查询失败：$(err_of "$MINE")"
[ "$(printf '%s' "$MINE" | jget data.published)" = "true" ] || fail "data.published 不是 true"
[ "$(printf '%s' "$MINE" | jpick data.days date "$D15" shiftCode)" = "N" ] \
  || fail "${D15} 不是 N：$(printf '%s' "$MINE" | jpick data.days date "$D15" shiftCode)"
pass

# ---------------------------------------------------------------- 步骤 6
STEP="6 请假申请：审批通过后为 L"
D20="${NM}-20"
LEAVE="$(api POST /api/swaps "$MA" \
  "{\"type\":\"LEAVE\",\"applicantDate\":\"${D20}\",\"targetStaffId\":null,\"targetDate\":null,\"reason\":\"冒烟测试请假\"}")"
[ "$(printf '%s' "$LEAVE" | jget code)" = "0" ] || fail "发起请假失败：$(err_of "$LEAVE")"
LEAVE_ID="$(printf '%s' "$LEAVE" | jget data.id)"
[ -n "$LEAVE_ID" ] || fail "响应里没有 data.id"
[ "$(printf '%s' "$LEAVE" | jget data.status)" = "PENDING_ADMIN" ] \
  || fail "请假应直接进科长审批，实际 $(printf '%s' "$LEAVE" | jget data.status)"

APPROVED="$(api POST "/api/swaps/${LEAVE_ID}/approve" "$TOKEN" '{"comment":"冒烟测试同意"}')"
[ "$(printf '%s' "$APPROVED" | jget code)" = "0" ] || fail "审批失败：$(err_of "$APPROVED")"
[ "$(printf '%s' "$APPROVED" | jget data.status)" = "APPROVED" ] \
  || fail "状态应为 APPROVED，实际 $(printf '%s' "$APPROVED" | jget data.status)"

MONTH="$(api GET "/api/schedules/${NM}" "$MA")"
[ "$(printf '%s' "$MONTH" | jget code)" = "0" ] || fail "成员查月视图失败：$(err_of "$MONTH")"
CELL20="$(printf '%s' "$MONTH" | jpick data.rows staffId "$SID_A" "cells.${D20}.shiftCode")"
[ "$CELL20" = "L" ] || fail "${D20} 应为 L，实际 ${CELL20:-空}"
pass

# ---------------------------------------------------------------- 步骤 7
STEP="7 换班申请：对方确认 + 科长审批"
D21="${NM}-21"
D22="${NM}-22"
SWAP="$(api POST /api/swaps "$MA" \
  "{\"type\":\"SWAP\",\"applicantDate\":\"${D21}\",\"targetStaffId\":${SID_B},\"targetDate\":\"${D22}\",\"reason\":\"冒烟测试换班\"}")"
[ "$(printf '%s' "$SWAP" | jget code)" = "0" ] || fail "发起换班失败：$(err_of "$SWAP")"
SWAP_ID="$(printf '%s' "$SWAP" | jget data.id)"
[ "$(printf '%s' "$SWAP" | jget data.status)" = "PENDING_PEER" ] \
  || fail "换班应先等对方确认，实际 $(printf '%s' "$SWAP" | jget data.status)"

CONFIRMED="$(api POST "/api/swaps/${SWAP_ID}/confirm" "$MB")"
[ "$(printf '%s' "$CONFIRMED" | jget code)" = "0" ] || fail "乙确认失败：$(err_of "$CONFIRMED")"
[ "$(printf '%s' "$CONFIRMED" | jget data.status)" = "PENDING_ADMIN" ] \
  || fail "确认后应进科长审批，实际 $(printf '%s' "$CONFIRMED" | jget data.status)"

APPROVED="$(api POST "/api/swaps/${SWAP_ID}/approve" "$TOKEN" '{"comment":"冒烟测试同意"}')"
[ "$(printf '%s' "$APPROVED" | jget code)" = "0" ] || fail "审批失败：$(err_of "$APPROVED")"
[ "$(printf '%s' "$APPROVED" | jget data.status)" = "APPROVED" ] \
  || fail "状态应为 APPROVED，实际 $(printf '%s' "$APPROVED" | jget data.status)"
pass

# ---------------------------------------------------------------- 步骤 8
STEP="8 统计与导出"
# 末日：把 1 号推到下个月再退一天，正好是 NM 的最后一天
LAST_DAY="$(date -v1d -v+2m -v-1d +%Y-%m-%d)"
STATS="$(api GET "/api/stats?from=${NM}-01&to=${LAST_DAY}" "$TOKEN")"
[ "$(printf '%s' "$STATS" | jget code)" = "0" ] || fail "统计失败：$(err_of "$STATS")"

META="$(http_meta GET "/api/stats/export?from=${NM}-01&to=${LAST_DAY}" "$TOKEN")"
[ "${META%% *}" = "200" ] || fail "统计导出 HTTP=${META%% *}"
case "$META" in *spreadsheetml*) ;; *) fail "统计导出 content-type 不是 xlsx：${META}" ;; esac

META="$(http_meta GET "/api/schedules/${NM}/export" "$TOKEN")"
[ "${META%% *}" = "200" ] || fail "排班表导出 HTTP=${META%% *}"
case "$META" in *spreadsheetml*) ;; *) fail "排班表导出 content-type 不是 xlsx：${META}" ;; esac
pass

# ---------------------------------------------------------------- 步骤 9
STEP="9 操作日志与越权拦截"
LOGS="$(api GET '/api/logs?page=0&size=5' "$TOKEN")"
[ "$(printf '%s' "$LOGS" | jget code)" = "0" ] || fail "查询日志失败：$(err_of "$LOGS")"
TOTAL="$(printf '%s' "$LOGS" | jget data.total)"
case "$TOTAL" in '' | *[!0-9]*) fail "data.total 不是数字：${TOTAL}" ;; esac
[ "$TOTAL" -gt 0 ] || fail "data.total=${TOTAL} 应 > 0"

META="$(http_meta GET '/api/logs?page=0&size=5' "$MA")"
[ "${META%% *}" = "403" ] || fail "成员看日志 HTTP=${META%% *}，应为 403"
pass

# ---------------------------------------------------------------- 步骤 10
STEP="10 大屏账号与越权拦截"
SCREEN="$(api POST /api/users "$TOKEN" \
  "{\"username\":\"${SCREEN_USER}\",\"displayName\":\"冒烟大屏\",\"role\":\"SCREEN\"}")"
[ "$(printf '%s' "$SCREEN" | jget code)" = "0" ] || fail "新建大屏账号失败：$(err_of "$SCREEN")"
TMP_S="$(printf '%s' "$SCREEN" | jget data.tempPassword)"
[ -n "$TMP_S" ] || fail "响应里没有 data.tempPassword"

STOKEN="$(login "$SCREEN_USER" "$TMP_S")"
[ -n "$STOKEN" ] || fail "大屏账号登录失败"
SCREEN_DATA="$(api GET "/api/screen?yearMonth=${NM}" "$STOKEN")"
[ "$(printf '%s' "$SCREEN_DATA" | jget code)" = "0" ] || fail "大屏数据失败：$(err_of "$SCREEN_DATA")"

META="$(http_meta GET "/api/screen?yearMonth=${NM}" "$MA")"
[ "${META%% *}" = "403" ] || fail "成员看大屏 HTTP=${META%% *}，应为 403"
pass

echo "ALL PASS"
exit 0
