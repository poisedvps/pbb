import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../layouts/MainLayout.vue'
import { useAuthStore } from '../stores/auth'

// meta.admin = true 的页面仅科长可见（菜单隐藏 + 后端鉴权）
export const menuRoutes = [
  { path: 'schedule', name: 'schedule', component: () => import('../views/ScheduleView.vue'), meta: { title: '排班表', group: '排班' } },
  { path: 'mine', name: 'mine', component: () => import('../views/MineView.vue'), meta: { title: '我的排班', group: '排班' } },
  { path: 'swap', name: 'swap', component: () => import('../views/SwapView.vue'), meta: { title: '调班申请', group: '排班' } },
  { path: 'stats', name: 'stats', component: () => import('../views/StatsView.vue'), meta: { title: '统计报表', group: '排班' } },
  { path: 'staff', name: 'staff', component: () => import('../views/StaffView.vue'), meta: { title: '人员管理', group: '基础设置', admin: true } },
  { path: 'holiday', name: 'holiday', component: () => import('../views/HolidayView.vue'), meta: { title: '节假日', group: '基础设置', admin: true } },
  { path: 'shifts', name: 'shifts', component: () => import('../views/ShiftsView.vue'), meta: { title: '班次设置', group: '基础设置', admin: true } },
  { path: 'users', name: 'users', component: () => import('../views/UsersView.vue'), meta: { title: '账号管理', group: '系统', admin: true } },
  { path: 'logs', name: 'logs', component: () => import('../views/LogsView.vue'), meta: { title: '操作日志', group: '系统', admin: true } }
]

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
    { path: '/screen', name: 'screen', component: () => import('../views/ScreenView.vue') },
    {
      path: '/',
      component: MainLayout,
      redirect: '/schedule',
      children: [
        ...menuRoutes,
        { path: 'password', name: 'password', component: () => import('../views/PasswordView.vue'), meta: { title: '修改密码' } }
      ]
    },
    { path: '/:pathMatch(.*)*', redirect: '/schedule' }
  ]
})

// 登录守卫：未登录回登录页；首次登录强制改密；大屏账号只能看大屏；管理页仅科长可进
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.name === 'login') return true
  if (!auth.token) return '/login'
  if (!auth.user) {
    try {
      await auth.fetchMe()
    } catch {
      await auth.logout()
      return '/login'
    }
  }
  if (auth.user?.mustChangePassword && to.name !== 'password') return '/password'
  if (auth.role === 'SCREEN' && to.name !== 'screen') return '/screen'
  if (to.meta.admin && auth.role !== 'ADMIN') return '/schedule'
  return true
})

export default router
