<template>
  <el-card>
    <template #header>
      <div class="bar">
        <b>操作日志</b>
        <span class="note">谁在什么时间做了什么，按时间倒序，仅科长可见。</span>
      </div>
    </template>

    <div class="filter">
      <span class="label">操作人</span>
      <el-input v-model="filter.username" class="kw" clearable placeholder="账号名，如 admin" @keyup.enter="search" />
      <span class="label">操作</span>
      <el-input v-model="filter.action" class="kw" clearable placeholder="如：发布排班" @keyup.enter="search" />
      <el-button type="primary" :loading="loading" @click="search">查询</el-button>
      <el-button :disabled="loading" @click="reset">重置</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border empty-text="没有符合条件的操作记录">
      <el-table-column label="时间" width="150">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作人" width="120">
        <template #default="{ row }">{{ row.username || '—' }}</template>
      </el-table-column>
      <el-table-column prop="action" label="操作" width="150" />
      <el-table-column label="对象" width="170">
        <template #default="{ row }">{{ row.target || '—' }}</template>
      </el-table-column>
      <el-table-column label="详情" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.detail || '—' }}</template>
      </el-table-column>
      <el-table-column label="IP" width="130">
        <template #default="{ row }">{{ row.ip || '—' }}</template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      class="pager"
      layout="total, prev, pager, next, sizes"
      :total="total"
      :page-sizes="[20, 50, 100]"
    />
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { listLogs } from '../api/logs'

const filter = reactive({ username: '', action: '' })
const list = ref([])
const total = ref(0)
// 页码在页面上从 1 开始，请求时才换成后端的 0 起算页码
const page = ref(1)
const size = ref(20)
const loading = ref(false)

// 后端返回 '2026-09-27T09:37:45+08:00'，去掉 T 和时区，只留「年-月-日 时:分」
const formatTime = (value) => (value ? String(value).replace('T', ' ').slice(0, 16) : '—')

// 连续翻页时会并发发出多个 listLogs，慢的旧响应不能盖掉新的：
// 每次请求领一个递增号，只有仍是「最新」的那一次才允许写列表与关 loading
let reqSeq = 0

const reload = async () => {
  const seq = ++reqSeq
  loading.value = true
  try {
    const data = await listLogs({
      page: page.value - 1,
      size: size.value,
      username: filter.username.trim() || undefined,
      action: filter.action.trim() || undefined
    })
    if (seq !== reqSeq) return // 期间又发起过新的查询，这份响应作废
    list.value = data?.items ?? []
    total.value = data?.total ?? 0
  } catch {
    // 失败提示由 http 拦截器统一弹出；查询失败不动当前列表
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

onMounted(reload)
// 翻页或改每页条数都重新拉取；同一轮里页码和条数一起变化，watch 只会触发一次
watch([page, size], reload)

const search = () => {
  // 换了筛选条件要回到第 1 页；已经在第 1 页时 watch 不会触发，自己拉一次
  if (page.value === 1) reload()
  else page.value = 1
}

const reset = () => {
  filter.username = ''
  filter.action = ''
  search()
}
</script>

<style scoped>
.bar { display: flex; align-items: baseline; gap: 10px; }
.filter { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.filter .label { font-size: 13px; color: #374151; }
.filter .kw { width: 180px; }
.note { font-size: 12px; color: #6b7280; }
.pager { margin-top: 14px; justify-content: flex-end; }
</style>
