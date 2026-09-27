<template>
  <el-container class="layout">
    <el-header class="header">
      <div class="logo">🗓 信息科排班系统</div>
      <div class="sp"></div>
      <span class="health" :class="health.db === 'ok' ? 'ok' : 'bad'">
        服务：{{ health.app === 'ok' ? '正常' : '异常' }} · 数据库：{{ health.db === 'ok' ? '正常' : '异常' }}
      </span>
      <el-button text class="hbtn" @click="$router.push('/password')">修改密码</el-button>
      <el-button text class="hbtn" @click="$router.push('/login')">退出</el-button>
    </el-header>
    <el-container>
      <el-aside width="190px" class="aside">
        <el-menu :default-active="$route.name" router>
          <el-menu-item-group v-for="g in groups" :key="g.name" :title="g.name">
            <el-menu-item v-for="r in g.items" :key="r.name" :index="r.name" :route="'/' + r.path">
              {{ r.meta.title }}
            </el-menu-item>
          </el-menu-item-group>
        </el-menu>
      </el-aside>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted, reactive } from 'vue'
import { menuRoutes } from '../router'
import { getHealth } from '../api/health'

const groups = computed(() => {
  const map = new Map()
  menuRoutes.forEach((r) => {
    if (!map.has(r.meta.group)) map.set(r.meta.group, [])
    map.get(r.meta.group).push(r)
  })
  return [...map].map(([name, items]) => ({ name, items }))
})

const health = reactive({ app: '', db: '' })
onMounted(async () => {
  try {
    Object.assign(health, await getHealth())
  } catch {
    health.app = 'down'
  }
})
</script>

<style scoped>
.layout { height: 100vh; }
.header { display: flex; align-items: center; gap: 12px; background: #1677c8; color: #fff; }
.logo { font-size: 16px; font-weight: 600; }
.sp { flex: 1; }
.hbtn { color: #fff !important; }
.health { font-size: 12px; padding: 2px 10px; border-radius: 10px; background: rgba(255, 255, 255, .15); }
.health.bad { background: #d64545; }
.aside { background: #fff; border-right: 1px solid #e3e7ee; }
.aside .el-menu { border-right: 0; }
</style>
