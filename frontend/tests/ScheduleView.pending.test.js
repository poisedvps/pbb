// M4-14 排班表「修改后暂存」验收用例。运行：在 frontend/ 下执行 npm test
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { h, defineComponent } from 'vue'
import ScheduleView from '../src/views/ScheduleView.vue'
import { saveDraft, getSchedule, publishSchedule, generateSchedule } from '../src/api/schedules'
import { useAuthStore } from '../src/stores/auth'

vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal()
  return {
    ...mod,
    ElMessage: { success: vi.fn(), info: vi.fn(), error: vi.fn(), warning: vi.fn() },
    ElMessageBox: { confirm: vi.fn(() => Promise.resolve('confirm')), alert: vi.fn(() => Promise.resolve('confirm')) }
  }
})

vi.mock('../src/api/schedules', () => ({
  getSchedule: vi.fn(),
  generateSchedule: vi.fn(() => Promise.resolve({ generated: 1, skippedManual: 0 })),
  publishSchedule: vi.fn(() => Promise.resolve({ version: 3, count: 10 })),
  saveDraft: vi.fn(() => Promise.resolve({ entries: 0, dutyPhones: 0 })),
  updateEntry: vi.fn()
}))
vi.mock('../src/api/shifts', () => ({
  listShiftTypes: vi.fn(() =>
    Promise.resolve([
      { code: 'D', name: '白班', color: '#1677c8', enabled: true, countsAsWork: true },
      { code: 'N', name: '夜班', color: '#7c3aed', enabled: true, countsAsWork: true },
      { code: 'X', name: '休息', color: '#9ca3af', enabled: true, countsAsWork: false }
    ])
  )
}))
vi.mock('../src/api/download', () => ({ download: vi.fn() }))

const { ElMessageBox, ElMessage } = await import('element-plus')

// vitest 的 jsdom 环境不把 localStorage 透出来，auth store 的 state 要用
if (typeof globalThis.localStorage?.getItem !== 'function') {
  const store = new Map()
  globalThis.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
    removeItem: (k) => store.delete(k)
  }
}

const monthOf = (ym, days) => ({
  yearMonth: ym,
  status: 'DRAFT',
  version: 1,
  publishedAt: null,
  draft: true,
  days,
  rows: [
    {
      staffId: 1,
      empNo: 'A01',
      name: '张三',
      position: '医生',
      cells: {
        [`${ym}-01`]: { shiftCode: 'D', manual: false, remark: null },
        [`${ym}-02`]: { shiftCode: 'X', manual: true, remark: '旧备注' }
      }
    },
    { staffId: 2, empNo: 'A02', name: '李四', position: '医生', cells: {} }
  ],
  cycleTemplateId: 1,
  dutyPhoneColor: '#fde047',
  dutyPhones: []
})

const daysOf = (ym, n) => {
  const [y, m] = ym.split('-').map(Number)
  return Array.from({ length: n }, (_, i) => {
    const date = `${ym}-${String(i + 1).padStart(2, '0')}`
    const weekday = new Date(Date.UTC(y, m - 1, i + 1)).getUTCDay()
    return { date, weekday: weekday === 0 ? 7 : weekday, kind: 'WORKDAY', holidayName: null }
  })
}

const Other = defineComponent({ render: () => h('div', 'other page') })

const buildRouter = () =>
  createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: ScheduleView },
      { path: '/staff', component: Other }
    ]
  })

// getSchedule 支持「吊住某个月不返回」，用来复现切月请求在途时的竞态
const held = new Map()

const mountView = async ({ admin = true, ym = '2026-09', days = 6, holdDays = {} } = {}) => {
  held.clear()
  getSchedule.mockReset().mockImplementation((target) => {
    if (target in holdDays) {
      return new Promise((resolve) => held.set(target, () => resolve(monthOf(target, daysOf(target, holdDays[target])))))
    }
    return Promise.resolve(monthOf(target, daysOf(target, target === ym ? days : 6)))
  })
  saveDraft.mockClear().mockResolvedValue({ entries: 0, dutyPhones: 0 })
  publishSchedule.mockClear()
  generateSchedule.mockClear()
  ElMessageBox.confirm.mockClear().mockResolvedValue('confirm')
  ElMessageBox.alert.mockClear()
  ElMessage.success.mockClear()
  ElMessage.warning.mockClear()
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = { id: 1, username: 'admin', role: admin ? 'ADMIN' : 'MEMBER' }
  const router = buildRouter()
  await router.push('/')
  await router.isReady()
  const wrapper = mount(defineComponent({ render: () => h(RouterView) }), {
    attachTo: document.body,
    global: { plugins: [pinia, router, ElementPlus] }
  })
  await flushPromises()
  // 放下被吊住的那个月，等它落地
  const release = async (target) => {
    held.get(target)?.()
    held.delete(target)
    await flushPromises()
  }
  return { wrapper, router, auth, release }
}

const btnByText = (text) => [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === text)
const click = async (el) => {
  if (el && typeof el.trigger === 'function') await el.trigger('click')
  else el.click()
  await flushPromises()
}

const cellTds = (wrapper) => wrapper.findAll('td.cell')
// 表体里的日期格（不管能不能点），用来判断屏幕上挂的是哪个月那份数据
const bodyTds = () => [...document.querySelectorAll('tbody tr:not(.cov) td:not(.name)')]
const pendingTds = (wrapper) => wrapper.findAll('td.pending')
const dialogOpen = () => {
  const overlay = document.querySelector('.el-overlay')
  return !!overlay && overlay.style.display !== 'none'
}

let last = null
const mountView2 = async (opts) => {
  const m = await mountView(opts)
  last = m.wrapper
  return m
}
beforeEach(() => {
  document.body.innerHTML = ''
})
afterEach(() => {
  last?.unmount()
  last = null
  document.body.innerHTML = ''
})

describe('M4-14 暂存', () => {
  it('改 3 格：3 个虚线框、按钮显示暂存（3），且没有任何写请求', async () => {
    const { wrapper } = await mountView2()
    const tds = cellTds(wrapper)
    for (const td of [tds[0], tds[1], tds[2]]) {
      await click(td)
      await click(btnByText('确定'))
    }
    expect(pendingTds(wrapper)).toHaveLength(3)
    expect(btnByText('暂存（3）')).toBeTruthy()
    expect(saveDraft).not.toHaveBeenCalled()
    expect(publishSchedule).not.toHaveBeenCalled()
    expect(generateSchedule).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('有 3 处未暂存')
  })

  it('点暂存：一次 saveDraft，entries 3 条，成功后清空并刷新', async () => {
    const { wrapper } = await mountView2()
    const tds = cellTds(wrapper)
    for (const td of [tds[0], tds[1], tds[2]]) {
      await click(td)
      await click(btnByText('确定'))
    }
    saveDraft.mockResolvedValue({ entries: 3, dutyPhones: 0 })
    await click(btnByText('暂存（3）'))
    expect(saveDraft).toHaveBeenCalledTimes(1)
    const [ym, body] = saveDraft.mock.calls[0]
    expect(ym).toBe('2026-09')
    expect(body.entries).toHaveLength(3)
    expect(body.dutyPhones).toEqual([])
    expect(body.entries[0]).toEqual({ staffId: 1, workDate: '2026-09-01', shiftCode: 'D', remark: null })
    expect(body.entries.map((e) => e.workDate)).toEqual(['2026-09-01', '2026-09-02', '2026-09-03'])
    expect(pendingTds(wrapper)).toHaveLength(0)
    expect(ElMessage.success).toHaveBeenCalledWith('已暂存 3 处修改')
    expect(getSchedule).toHaveBeenCalledTimes(2) // 初次 + 暂存后刷新
  })

  it('同格再改一次是覆盖，不重复计数', async () => {
    const { wrapper } = await mountView2()
    const td = cellTds(wrapper)[0]
    await click(td)
    await click(btnByText('确定'))
    await click(td)
    await click(btnByText('夜班'))
    await click(btnByText('确定'))
    expect(pendingTds(wrapper)).toHaveLength(1)
    expect(btnByText('暂存（1）')).toBeTruthy()
    await click(td)
    expect(btnByText('夜班').className).toContain('on')
  })

  it('切月：取消则停留且不切月，确认则切月并丢弃修改', async () => {
    const { wrapper, router } = await mountView2()
    const td = cellTds(wrapper)[0]
    await click(td)
    await click(btnByText('确定'))
    ElMessageBox.confirm.mockRejectedValueOnce('cancel')
    await click(btnByText('›'))
    expect(wrapper.text()).toContain('2026年9月')
    expect(pendingTds(wrapper)).toHaveLength(1)
    expect(router.currentRoute.value.path).toBe('/')

    await click(btnByText('›'))
    expect(ElMessageBox.confirm).toHaveBeenLastCalledWith('有 1 处修改未暂存，切换月份将放弃这些修改，是否继续？', '切换月份', expect.anything())
    expect(wrapper.text()).toContain('2026年10月')
    expect(pendingTds(wrapper)).toHaveLength(0)
    expect(btnByText('暂存（0）')).toBeTruthy()
  })

  it('有未暂存修改时点发布/生成：只弹提示，不发请求', async () => {
    const { wrapper } = await mountView2()
    const td = cellTds(wrapper)[0]
    await click(td)
    await click(btnByText('确定'))
    await click(btnByText('发布排班'))
    expect(ElMessageBox.alert.mock.calls[0][0]).toBe('有 1 处修改未暂存，请先暂存或放弃修改')
    expect(publishSchedule).not.toHaveBeenCalled()
    await click(btnByText('⚡ 按规则生成'))
    expect(generateSchedule).not.toHaveBeenCalled()
    await click(btnByText('放弃修改'))
    await click(btnByText('发布排班'))
    expect(publishSchedule).toHaveBeenCalledTimes(1)
  })

  it('放弃修改：确认后清空', async () => {
    const { wrapper } = await mountView2()
    await click(cellTds(wrapper)[0])
    await click(btnByText('确定'))
    ElMessageBox.confirm.mockRejectedValueOnce('cancel')
    await click(btnByText('放弃修改'))
    expect(pendingTds(wrapper)).toHaveLength(1)
    await click(btnByText('放弃修改'))
    expect(pendingTds(wrapper)).toHaveLength(0)
  })

  it('路由离开：取消则留在本页，确认则放行', async () => {
    const { wrapper, router } = await mountView2()
    await click(cellTds(wrapper)[0])
    await click(btnByText('确定'))
    ElMessageBox.confirm.mockRejectedValueOnce('cancel')
    await router.push('/staff').catch(() => {})
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
    expect(pendingTds(wrapper)).toHaveLength(1)
    await router.push('/staff')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/staff')
  })

  it('刷新浏览器：有未暂存修改时弹原生离开提示', async () => {
    const { wrapper } = await mountView2()
    const fire = () => {
      const e = new Event('beforeunload', { bubbles: true, cancelable: true })
      window.dispatchEvent(e)
      return e
    }
    expect(fire().defaultPrevented).toBe(false)
    await click(cellTds(wrapper)[0])
    await click(btnByText('确定'))
    // returnValue 在 jsdom 里被降级成布尔，只有 defaultPrevented 可信；浏览器里 returnValue='' 才是弹提示的开关
    expect(fire().defaultPrevented).toBe(true)
    await click(btnByText('放弃修改'))
    expect(fire().defaultPrevented).toBe(false)
  })

  it('恢复规则默认：shiftCode=null，色块显示灰字「默认」，暂存时原样送出', async () => {
    const { wrapper } = await mountView2()
    const td = cellTds(wrapper)[0]
    await click(td)
    await click(btnByText('恢复规则默认'))
    await click(btnByText('确定'))
    expect(td.text()).toBe('默认')
    await click(btnByText('暂存（1）'))
    expect(saveDraft.mock.calls[0][1].entries).toEqual([{ staffId: 1, workDate: '2026-09-01', shiftCode: null, remark: null }])
  })

  it('成员登录：看不到暂存 / 放弃按钮，格子不可点', async () => {
    const { wrapper } = await mountView2({ admin: false })
    expect(btnByText('暂存（0）')).toBeFalsy()
    expect(btnByText('放弃修改')).toBeFalsy()
    expect(cellTds(wrapper)).toHaveLength(0)
  })

  it('暂存失败：修改原样留在页面上，也不刷新', async () => {
    const { wrapper } = await mountView2()
    await click(cellTds(wrapper)[0])
    await click(btnByText('确定'))
    saveDraft.mockRejectedValueOnce(new Error('1503 日期不在本月'))
    await click(btnByText('暂存（1）'))
    expect(pendingTds(wrapper)).toHaveLength(1)
    expect(btnByText('暂存（1）')).toBeTruthy()
    expect(getSchedule).toHaveBeenCalledTimes(1) // 只有初次加载
    saveDraft.mockResolvedValue({ entries: 1, dutyPhones: 0 })
    await click(btnByText('暂存（1）'))
    expect(pendingTds(wrapper)).toHaveLength(0)
    expect(getSchedule).toHaveBeenCalledTimes(2)
  })

  it('弹窗：取消不写待暂存；确定后关闭弹窗并带上备注', async () => {
    const { wrapper } = await mountView2()
    const td = cellTds(wrapper)[1]
    await click(td)
    await click(btnByText('取消'))
    expect(pendingTds(wrapper)).toHaveLength(0)
    await click(td)
    const ta = document.querySelector('.remark textarea')
    ta.value = '临时顶班'
    ta.dispatchEvent(new Event('input'))
    await click(btnByText('夜班'))
    await click(btnByText('确定'))
    expect(dialogOpen()).toBe(false)
    expect(td.text()).toBe('夜班')
    await click(btnByText('暂存（1）'))
    expect(saveDraft.mock.calls[0][1].entries).toEqual([
      { staffId: 1, workDate: '2026-09-02', shiftCode: 'N', remark: '临时顶班' }
    ])
  })

  it('按钮初始禁用；有修改后可点', async () => {
    const { wrapper } = await mountView2()
    expect(btnByText('暂存（0）').disabled).toBe(true)
    expect(btnByText('放弃修改').disabled).toBe(true)
    await click(cellTds(wrapper)[0])
    await click(btnByText('确定'))
    expect(btnByText('暂存（1）').disabled).toBe(false)
  })

  // ↓↓ 审核意见：切月 GET 在途时屏幕还是旧月那张表，此时不许开格与确定，
  // 新月数据落地后暂存也不能带上旧月的 workDate
  it('竞态：切月请求在途时旧月格子点不开，新月落地后暂存不带旧月日期', async () => {
    const { wrapper, release } = await mountView2({ holdDays: { '2026-10': 8 } })
    const sepTd = cellTds(wrapper)[0]
    await click(btnByText('›')) // 无未暂存修改 → 不弹确认，直接切月
    // 标题已经翻到 10 月，可屏幕上挂的还是旧月那 6 天的表：这正是能点到旧月格子的一瞬
    expect(wrapper.text()).toContain('2026年10月')
    expect(bodyTds()).toHaveLength(12) // 旧月 6 天 × 2 行还在
    expect(bodyTds().some((td) => td.classList.contains('cell'))).toBe(false) // 但不给点
    // 旧月的格子点不开，也不产生待暂存
    await click(sepTd)
    expect(dialogOpen()).toBe(false)
    expect(pendingTds(wrapper)).toHaveLength(0)
    expect(btnByText('暂存（0）')).toBeTruthy()
    // 新月数据落地
    await release('2026-10')
    expect(wrapper.text()).toContain('2026年10月')
    expect(bodyTds()).toHaveLength(16) // 换成 10 月那 8 天
    expect(pendingTds(wrapper)).toHaveLength(0)
    expect(btnByText('暂存（0）').disabled).toBe(true)
    // 在新月里正常改一格：送出的日期全属新月
    await click(cellTds(wrapper)[0])
    await click(btnByText('确定'))
    await click(btnByText('暂存（1）'))
    expect(saveDraft).toHaveBeenCalledTimes(1)
    expect(saveDraft.mock.calls[0][0]).toBe('2026-10')
    saveDraft.mock.calls[0][1].entries.forEach((e) => expect(e.workDate.startsWith('2026-10')).toBe(true))
  })

  it('竞态：弹窗开着时切月，旧月那一格不会被写进新月', async () => {
    const { wrapper, release } = await mountView2({ holdDays: { '2026-10': 8 } })
    await click(cellTds(wrapper)[0]) // 打开 9 月某格的弹窗
    await click(btnByText('夜班'))
    expect(dialogOpen()).toBe(true)
    await click(btnByText('›')) // 切月，10 月的请求吊住
    expect(dialogOpen()).toBe(false) // 旧弹窗被关掉
    await click(btnByText('确定')) // 就算点到确定，也不能写进待暂存
    expect(pendingTds(wrapper)).toHaveLength(0)
    await release('2026-10')
    await click(btnByText('确定')) // 新月落地后再点一次，同样无效
    expect(pendingTds(wrapper)).toHaveLength(0)
    expect(saveDraft).not.toHaveBeenCalled()
    expect(btnByText('暂存（0）').disabled).toBe(true)
  })

  it('切月确认后旧月那批修改整批作废，新月表上不留虚线与计数', async () => {
    const { wrapper, release } = await mountView2({ holdDays: { '2026-10': 8 } })
    await click(cellTds(wrapper)[0])
    await click(btnByText('确定'))
    expect(pendingTds(wrapper)).toHaveLength(1)
    // 切月确认放行 → 旧月那批整批作废，再让新月数据落地
    await click(btnByText('›'))
    await release('2026-10')
    expect(wrapper.text()).toContain('2026年10月')
    expect(pendingTds(wrapper)).toHaveLength(0)
    expect(wrapper.text()).not.toContain('未暂存')
    await click(btnByText('暂存（0）'))
    expect(saveDraft).not.toHaveBeenCalled()
  })
})
