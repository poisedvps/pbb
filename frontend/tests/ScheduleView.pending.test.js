// M4-14 排班表「修改后暂存」验收用例。运行：在 frontend/ 下执行 npm test
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { h, defineComponent } from 'vue'
import ScheduleView from '../src/views/ScheduleView.vue'
import { saveDraft, getSchedule, publishSchedule, generateSchedule } from '../src/api/schedules'
import { saveStaffOrder } from '../src/api/staff'
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
// M5-11 拖动排序走的是人员管理那个接口（PUT /api/staff/order）
vi.mock('../src/api/staff', () => ({ saveStaffOrder: vi.fn(() => Promise.resolve()) }))

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
  // 页面默认打开系统当前月，测试数据按 2026-09 准备，这里把日期固定住
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 8, 15, 12, 0, 0))
  document.body.innerHTML = ''
})
afterEach(() => {
  last?.unmount()
  last = null
  document.body.innerHTML = ''
  vi.useRealTimers()
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

  it('暂存按钮初始可点；放弃修改初始禁用', async () => {
    const { wrapper } = await mountView2()
    expect(btnByText('暂存（0）').disabled).toBe(false)
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
    expect(btnByText('暂存（0）').disabled).toBe(false)
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
    expect(btnByText('暂存（0）').disabled).toBe(false)
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

  it('兜底：屏幕月份与格子日期对不上时整批作废，不发暂存', async () => {
    const { wrapper } = await mountView2()
    // yearMonth 是 10 月、days 还是 9 月那 6 天：前三道闸全放行，只剩 workDate 归属检查能拦住
    getSchedule.mockReset().mockResolvedValue({
      ...monthOf('2026-09', daysOf('2026-09', 6)),
      yearMonth: '2026-10'
    })
    await click(btnByText('›'))
    await flushPromises()
    await click(cellTds(wrapper)[0])
    await click(btnByText('确定'))
    expect(pendingTds(wrapper)).toHaveLength(1)
    await click(btnByText('暂存（1）'))
    expect(saveDraft).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('待暂存的修改不属于当前月份，已作废，请重新修改')
    expect(pendingTds(wrapper)).toHaveLength(0)
  })
})

// ============================================================================
// M4-15 值班电话（设计 §8.1 第 3 条、§8.5「标亮」）
// 上面的 helper 用的是 6 天小月且只喂 2026-09，这里要整月数据、跨月周与多个月份，
// 所以另起一套；上面 17 条断言一字未改。
// ============================================================================
const DUTY_YELLOW = 'rgb(253, 224, 71)' // 默认底色 #fde047
const DUTY_ORANGE = 'rgb(249, 115, 22)' // #f97316

// 整月 days/cells：weekday 走 UTC 算，机器时区不同也不会把日期算错一天
const dutyDaysOf = (ym) => {
  const [y, m] = ym.split('-').map(Number)
  const n = new Date(y, m, 0).getDate()
  return Array.from({ length: n }, (_, i) => {
    const wd = new Date(Date.UTC(y, m - 1, i + 1)).getUTCDay()
    return {
      date: `${ym}-${String(i + 1).padStart(2, '0')}`,
      weekday: wd === 0 ? 7 : wd,
      kind: wd >= 6 ? 'WEEKEND' : 'WORKDAY',
      holidayName: null
    }
  })
}
const dutyCellsOf = (ym, code) =>
  Object.fromEntries(dutyDaysOf(ym).map((d) => [d.date, { shiftCode: code, manual: false, remark: null }]))

// 成员看到的 dutyPhones 就是已发布那一份（后端负口径），这里直接只喂已发布的那几条
const dutyMonthOf = (ym, { dutyPhones = [], color = '#fde047', draft = true, status = 'DRAFT' } = {}) => ({
  yearMonth: ym,
  status,
  version: 1,
  publishedAt: null,
  draft,
  days: dutyDaysOf(ym),
  rows: [
    { staffId: 1, empNo: 'A01', name: '张三', position: '医生', cells: dutyCellsOf(ym, 'D') },
    { staffId: 2, empNo: 'A02', name: '李四', position: '医生', cells: dutyCellsOf(ym, 'D') }
  ],
  cycleTemplateId: 1,
  dutyPhoneColor: color,
  dutyPhones
})
const dutyRow = (weekStart, weekEnd, staffId, name) => ({ weekStart, weekEnd, staffId, name })

// 翻到目标月：组件的初始月份是「跑测试这天的当月」，靠‹ › 翻，测试与机器日期无关
const dutyMonthNo = (label) => {
  const m = /(\d{4})年(\d{1,2})月/.exec(label.replace(/\s/g, ''))
  return m ? Number(m[1]) * 12 + Number(m[2]) - 1 : NaN
}
const goDutyMonth = async (wrapper, target) => {
  const want = Number(target.slice(0, 4)) * 12 + Number(target.slice(5, 7)) - 1
  for (let i = 0; i < 48 && dutyMonthNo(wrapper.find('.month').text()) !== want; i++) {
    await click(btnByText(dutyMonthNo(wrapper.find('.month').text()) < want ? '›' : '‹'))
  }
  if (dutyMonthNo(wrapper.find('.month').text()) !== want) throw new Error('翻不到 ' + target)
}

// 吊住某个月不返回，用来复现切月请求在途时的竞态；arm() 才生效，
// 否则前面用 ‹ › 翻页时路过那个月就卡住了
const dutyHeld = new Map()
let lastDuty = null
const mountDuty = async ({ admin = true, ym = '2026-10', months = {} } = {}) => {
  dutyHeld.clear()
  const armed = new Set()
  getSchedule.mockReset().mockImplementation((target) => {
    if (armed.has(target)) {
      return new Promise((resolve) => dutyHeld.set(target, () => resolve(months[target] || dutyMonthOf(target))))
    }
    return Promise.resolve(months[target] || dutyMonthOf(target))
  })
  saveDraft.mockClear().mockResolvedValue({ entries: 0, dutyPhones: 0 })
  publishSchedule.mockReset().mockResolvedValue({ version: 3, count: 10 })
  generateSchedule.mockClear()
  ElMessageBox.confirm.mockClear().mockResolvedValue('confirm')
  ElMessageBox.alert.mockClear()
  ElMessage.success.mockClear()
  ElMessage.warning.mockClear()
  ElMessage.info.mockClear()
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = { id: 1, username: admin ? 'admin' : 'member', role: admin ? 'ADMIN' : 'MEMBER' }
  const router = buildRouter()
  await router.push('/')
  await router.isReady()
  const wrapper = mount(defineComponent({ render: () => h(RouterView) }), {
    attachTo: document.body,
    global: { plugins: [pinia, router, ElementPlus] }
  })
  await flushPromises()
  lastDuty = wrapper
  await goDutyMonth(wrapper, ym)
  return {
    wrapper,
    router,
    auth,
    // 把某个月的 GET 吊住；release() 再放它落地
    arm: (target) => armed.add(target),
    release: async (target) => {
      dutyHeld.get(target)?.()
      dutyHeld.delete(target)
      await flushPromises()
    }
  }
}
afterEach(() => {
  lastDuty?.unmount()
  lastDuty = null
})

// 值班电话一行的周标签，顺序与选择器一致
const dutyWeekLabels = (wrapper) => wrapper.findAll('.duty .wk-no').map((e) => e.text())
const dutySelects = (wrapper) => wrapper.findAllComponents({ name: 'ElSelect' })
// 选人与清空走组件契约：el-select 的弹层 teleport 到 body 且五个下拉共用同一层 DOM，
// 靠定位弹层项去点会隔空命中另一个选择器；选中和 clearable 清空对外发的就是这个事件
const pickDuty = async (select, staffId) => {
  select.vm.$emit('update:modelValue', staffId)
  await flushPromises()
}
// 表体某一行的日期格（去掉姓名列），下标 = 日 - 1
const dutyRowTds = (wrapper, rowIndex) => wrapper.findAll('tbody tr')[rowIndex].findAll('td').slice(1)
const dutyBg = (td) => (td.attributes('style') || '').replace(/\s+/g, ' ')
const dayCol = (day) => day - 1

describe('M4-15 值班电话', () => {
  it('2026-10 的周选择器：5 个，第一个是跨月的 9/28–10/4', async () => {
    const { wrapper } = await mountDuty({ ym: '2026-10' })
    expect(dutyWeekLabels(wrapper)).toEqual(['9/28–10/4', '10/5–10/11', '10/12–10/18', '10/19–10/25', '10/26–11/1'])
    expect(dutySelects(wrapper)).toHaveLength(5)
    for (const s of dutySelects(wrapper)) expect(s.props('modelValue')).toBeNull() // 全部未设置
    // 选项就是当前参与排班的人员，显示姓名
    expect(dutySelects(wrapper)[0].findAllComponents({ name: 'ElOption' }).map((o) => o.props('label'))).toEqual([
      '张三',
      '李四'
    ])
  })

  it('选 9/28–10/4 = 张三：张三本月 4 格立即变黄，班次文字不变，暂存计数 +1', async () => {
    const { wrapper } = await mountDuty({ ym: '2026-10' })
    await pickDuty(dutySelects(wrapper)[0], 1)
    const zhang = dutyRowTds(wrapper, 0)
    for (const i of [0, 1, 2, 3]) expect(dutyBg(zhang[i])).toContain(DUTY_YELLOW) // 10-01~10-04 整格黄底
    expect(dutyBg(zhang[4])).not.toContain(DUTY_YELLOW) // 10-05 属于下一周
    expect(dutyBg(dutyRowTds(wrapper, 1)[0])).not.toContain(DUTY_YELLOW) // 李四不受影响
    expect(zhang[0].text()).toBe('白班') // 班次文字照常显示
    expect(btnByText('暂存（1）')).toBeTruthy()
    expect(saveDraft).not.toHaveBeenCalled() // 选择只写页面，不发请求
  })

  it('同一周重复选同一个人不重复计数；换人就是覆盖', async () => {
    const { wrapper } = await mountDuty({ ym: '2026-10' })
    const s = dutySelects(wrapper)[1] // 10/5–10/11
    await pickDuty(s, 1)
    await pickDuty(s, 1)
    expect(btnByText('暂存（1）')).toBeTruthy()
    await pickDuty(s, 2)
    expect(btnByText('暂存（1）')).toBeTruthy()
    expect(dutyBg(dutyRowTds(wrapper, 1)[dayCol(8)])).toContain(DUTY_YELLOW) // 10-08 起是李四
    expect(dutyBg(dutyRowTds(wrapper, 0)[dayCol(8)])).not.toContain(DUTY_YELLOW)
  })

  it('暂存：PUT 2026-10 带上跨月那一周，不被「不属于本月」拦掉', async () => {
    const { wrapper } = await mountDuty({ ym: '2026-10' })
    await pickDuty(dutySelects(wrapper)[0], 1)
    const before = getSchedule.mock.calls.length
    await click(btnByText('暂存（1）'))
    expect(saveDraft).toHaveBeenCalledTimes(1)
    const [ym, body] = saveDraft.mock.calls[0]
    expect(ym).toBe('2026-10')
    expect(body.entries).toEqual([])
    expect(body.dutyPhones).toEqual([{ weekStart: '2026-09-28', staffId: 1 }])
    // 跨月那一周的周一在上个月，不能走 M4-14 那句「不属于本月」的兜底
    expect(ElMessage.warning).not.toHaveBeenCalled()
    expect(btnByText('暂存（0）')).toBeTruthy()
    expect(getSchedule).toHaveBeenCalledTimes(before + 1) // 暂存成功后刷新本月
  })

  it('清空已有的一周：送 staffId=null，标亮立即消失', async () => {
    const { wrapper } = await mountDuty({
      ym: '2026-10',
      months: {
        '2026-10': dutyMonthOf('2026-10', { dutyPhones: [dutyRow('2026-09-28', '2026-10-04', 1, '张三')] })
      }
    })
    const s = dutySelects(wrapper)[0]
    expect(s.props('modelValue')).toBe(1) // 打开时选择器就是该人
    expect(dutyBg(dutyRowTds(wrapper, 0)[0])).toContain(DUTY_YELLOW)
    await pickDuty(s, '') // clearable 清空传的是 ''
    expect(s.props('modelValue')).toBeNull()
    expect(dutyBg(dutyRowTds(wrapper, 0)[0])).not.toContain(DUTY_YELLOW)
    expect(btnByText('暂存（1）')).toBeTruthy()
    await click(btnByText('暂存（1）'))
    expect(saveDraft.mock.calls[0][1].dutyPhones).toEqual([{ weekStart: '2026-09-28', staffId: null }])
  })

  it('底色取 data.dutyPhoneColor：改成橙色后标亮为橙色，图例末尾有色块', async () => {
    const { wrapper } = await mountDuty({
      ym: '2026-10',
      months: {
        '2026-10': dutyMonthOf('2026-10', {
          dutyPhones: [dutyRow('2026-09-28', '2026-10-04', 1, '张三')],
          color: '#f97316'
        })
      }
    })
    expect(dutyBg(dutyRowTds(wrapper, 0)[dayCol(3)])).toContain(DUTY_ORANGE)
    expect(dutyBg(dutyRowTds(wrapper, 0)[dayCol(3)])).not.toContain(DUTY_YELLOW)
    const legend = wrapper.find('.legend .duty-legend .swatch')
    expect(legend.exists()).toBe(true)
    expect(legend.attributes('style')).toContain(DUTY_ORANGE)
    expect(wrapper.find('.legend .duty-legend').text()).toContain('值班电话')
  })

  it('跨月那一周在 9 月同样显示：选择器是张三，9-28~9-30 黄底', async () => {
    const { wrapper } = await mountDuty({
      ym: '2026-09',
      months: {
        '2026-09': dutyMonthOf('2026-09', { dutyPhones: [dutyRow('2026-09-28', '2026-10-04', 1, '张三')] })
      }
    })
    // 9 月涉及从 8/31 起的 5 周，最后那周正是 9/28–10/4
    expect(dutyWeekLabels(wrapper)).toEqual(['8/31–9/6', '9/7–9/13', '9/14–9/20', '9/21–9/27', '9/28–10/4'])
    expect(dutySelects(wrapper)[4].props('modelValue')).toBe(1)
    const zhang = dutyRowTds(wrapper, 0)
    expect(dutyBg(zhang[dayCol(27)])).not.toContain(DUTY_YELLOW) // 9-27 属于 9/21 那一周
    for (const d of [28, 29, 30]) expect(dutyBg(zhang[dayCol(d)])).toContain(DUTY_YELLOW)
    expect(dutyBg(zhang[dayCol(5)])).not.toContain(DUTY_YELLOW) // 本月自己的那周没安排
  })

  it('格子与值班电话合并计数，放弃修改一起清空', async () => {
    const { wrapper } = await mountDuty({ ym: '2026-10' })
    await click(dutyRowTds(wrapper, 0)[0]) // 改一格
    await click(btnByText('确定'))
    await pickDuty(dutySelects(wrapper)[2], 1) // 再选一周
    expect(btnByText('暂存（2）')).toBeTruthy()
    expect(wrapper.text()).toContain('有 2 处未暂存')
    await click(btnByText('放弃修改'))
    expect(btnByText('暂存（0）')).toBeTruthy()
    expect(dutyBg(dutyRowTds(wrapper, 0)[dayCol(15)])).not.toContain(DUTY_YELLOW)
    expect(pendingTds(wrapper)).toHaveLength(0)
  })

  it('只改了值班电话也能暂存，提示里带上周数', async () => {
    const { wrapper } = await mountDuty({ ym: '2026-10' })
    await pickDuty(dutySelects(wrapper)[3], 2)
    await click(btnByText('暂存（1）'))
    expect(saveDraft).toHaveBeenCalledTimes(1)
    expect(saveDraft.mock.calls[0][1]).toEqual({ entries: [], dutyPhones: [{ weekStart: '2026-10-19', staffId: 2 }] })
    expect(ElMessage.success.mock.calls[0][0]).toContain('值班电话 1 周')
  })

  it('有值班电话未暂存时点发布：只弹提示，不发请求', async () => {
    const { wrapper } = await mountDuty({ ym: '2026-10' })
    await pickDuty(dutySelects(wrapper)[0], 1)
    await click(btnByText('发布排班'))
    expect(ElMessageBox.alert.mock.calls[0][0]).toBe('有 1 处修改未暂存，请先暂存或放弃修改')
    expect(publishSchedule).not.toHaveBeenCalled()
    expect(saveDraft).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('值班电话') // 选择仍留在页面上
  })

  it('选了值班电话未暂存时点 ›：确认切月后清空，计数为 0', async () => {
    const { wrapper } = await mountDuty({ ym: '2026-09' })
    await pickDuty(dutySelects(wrapper)[4], 1) // 9/28–10/4 = 张三
    expect(btnByText('暂存（1）')).toBeTruthy()
    await click(btnByText('›'))
    expect(ElMessageBox.confirm).toHaveBeenLastCalledWith(
      '有 1 处修改未暂存，切换月份将放弃这些修改，是否继续？',
      '切换月份',
      expect.anything()
    )
    expect(wrapper.text()).toContain('2026年10月')
    expect(btnByText('暂存（0）')).toBeTruthy()
    for (const s of dutySelects(wrapper)) expect(s.props('modelValue')).toBeNull()
    expect(dutyBg(dutyRowTds(wrapper, 0)[0])).not.toContain(DUTY_YELLOW)
    expect(saveDraft).not.toHaveBeenCalled()
  })

  it('切月取消则不切月，刚选的值班电话留在页面上', async () => {
    const { wrapper } = await mountDuty({ ym: '2026-09' })
    await pickDuty(dutySelects(wrapper)[0], 2)
    ElMessageBox.confirm.mockRejectedValueOnce('cancel')
    await click(btnByText('›'))
    expect(wrapper.text()).toContain('2026年9月')
    expect(btnByText('暂存（1）')).toBeTruthy()
    expect(dutySelects(wrapper)[0].props('modelValue')).toBe(2)
  })

  it('竞态：切月请求在途时选择器禁用且不写待暂存，新月落地后暂存只带新月那一周', async () => {
    const { wrapper, arm, release } = await mountDuty({ ym: '2026-09' })
    arm('2026-10') // 10 月的 GET 吊住：标题已翻过去，屏幕上还是 9 月那张表
    await click(btnByText('›'))
    expect(wrapper.text()).toContain('2026年10月')
    const s = dutySelects(wrapper)[0]
    expect(s.props('disabled')).toBe(true) // 与格子同开同关
    await pickDuty(s, 1)
    expect(btnByText('暂存（0）')).toBeTruthy()
    await release('2026-10')
    expect(btnByText('暂存（0）')).toBeTruthy()
    expect(saveDraft).not.toHaveBeenCalled()
    // 新月里正常选一周：送出的仍是新月的表
    await pickDuty(dutySelects(wrapper)[1], 1)
    await click(btnByText('暂存（1）'))
    expect(saveDraft.mock.calls[0][0]).toBe('2026-10')
    expect(saveDraft.mock.calls[0][1].dutyPhones).toEqual([{ weekStart: '2026-10-05', staffId: 1 }])
  })

  it('成员登录：只显示文字没有选择器；没排到值班电话的周不显示', async () => {
    const { wrapper } = await mountDuty({
      admin: false,
      ym: '2026-10',
      months: {
        '2026-10': dutyMonthOf('2026-10', {
          dutyPhones: [dutyRow('2026-09-28', '2026-10-04', 1, '张三')],
          draft: false,
          status: 'PUBLISHED'
        })
      }
    })
    expect(wrapper.findAll('.duty .wk')).toHaveLength(1) // 只渲染排到人的那一周
    expect(dutySelects(wrapper)).toHaveLength(0)
    expect(wrapper.find('.duty').text()).toContain('9/28–10/4')
    expect(wrapper.find('.duty').text()).toContain('张三')
    expect(dutyBg(dutyRowTds(wrapper, 0)[0])).toContain(DUTY_YELLOW) // 成员同样看到黄底
    expect(btnByText('暂存（0）')).toBeFalsy() // 成员没有暂存入口
  })

  it('成员登录：没有任何已发布安排时不显示值班电话一行', async () => {
    const { wrapper } = await mountDuty({
      admin: false,
      ym: '2026-10',
      months: { '2026-10': dutyMonthOf('2026-10', { draft: false, status: 'PUBLISHED' }) }
    })
    expect(wrapper.find('.duty').exists()).toBe(false)
  })

  it('整月重写在途时选择器禁用', async () => {
    const { wrapper } = await mountDuty({
      ym: '2026-10',
      months: {
        '2026-10': dutyMonthOf('2026-10', { dutyPhones: [dutyRow('2026-09-28', '2026-10-04', 1, '张三')] })
      }
    })
    publishSchedule.mockImplementation(() => new Promise(() => {})) // 整月重写吊住不返回
    await click(btnByText('发布排班'))
    await flushPromises()
    expect(wrapper.text()).toContain('值班电话')
    expect(dutySelects(wrapper)[0].props('disabled')).toBe(true)
  })
})

// ============================================================================
// M5-06 姓名下方不再显示工号，搜索仍可按工号（需求 §9.1 第 6 条）
// ============================================================================
describe('M5-06 隐藏工号', () => {
  it('姓名列不显示工号，但搜索工号仍能找到', async () => {
    await mountView2()
    expect(document.querySelector('tbody td.name').textContent.trim()).toBe('张三')
    const input = document.querySelector('.kw input')
    input.value = 'A02'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    // 底部的「在岗」汇总行不是人员行（它按整列统计，不受搜索影响），只比人员行
    expect([...document.querySelectorAll('tbody tr:not(.cov) td.name')].map((td) => td.textContent.trim())).toEqual([
      '李四'
    ])
  })
})

// ============================================================================
// M5-08 「暂存」按钮常驻可点（需求 §9.1 第 5 条）
// 表格加载完成后按钮就能点；没有未暂存修改时只提示，不发请求
// ============================================================================
describe('M5-08 暂存按钮常驻可点', () => {
  it('没有未暂存修改时点暂存：只提示，不发 saveDraft', async () => {
    await mountView2()
    ElMessage.info.mockClear()
    const btn = btnByText('暂存（0）')
    expect(btn).toBeTruthy()
    expect(btn.disabled).toBe(false) // 表加载完就能点
    await click(btn)
    expect(saveDraft).not.toHaveBeenCalled()
    expect(ElMessage.info).toHaveBeenCalledTimes(1)
    expect(ElMessage.info).toHaveBeenCalledWith('排班表草稿已是最新，没有需要暂存的修改')
    expect(document.querySelector('.note').textContent.trim()).toBe(
      '点击单元格修改班次，修改后点【暂存】保存；按规则生成的结果会直接存为草稿；底部行 = 每日在岗人数'
    )
  })

  it('成员登录：表加载完了也仍没有暂存按钮', async () => {
    const { wrapper } = await mountView2({ admin: false })
    expect(wrapper.text()).toContain('张三') // 表已加载
    expect(btnByText('暂存（0）')).toBeFalsy()
    expect(saveDraft).not.toHaveBeenCalled()
  })
})

// ============================================================================
// M5-11 拖动姓名调整人员顺序（需求 §9.1 第 7 条，复用 PUT /api/staff/order）
// ============================================================================
describe('M5-11 拖动调整人员顺序', () => {
  // 人员行（底部「在岗」汇总行不算）的姓名列，下标 = 行号
  const nameTds = (wrapper) => wrapper.findAll('tbody tr:not(.cov) td.name')
  const shownNames = () => nameTds(last).map((td) => td.text().trim())
  const typeKeyword = async (value) => {
    const input = document.querySelector('.kw input')
    input.value = value
    input.dispatchEvent(new Event('input'))
    await flushPromises()
  }
  // 从第 from 行拖到第 to 行：drop 前照例先 dragover 一次（真实浏览器必经这一步）
  const dragRow = async (wrapper, from, to) => {
    const tds = nameTds(wrapper)
    await tds[from].trigger('dragstart')
    await tds[to].trigger('dragover')
    await tds[to].trigger('drop')
    await flushPromises()
  }

  beforeEach(() => {
    saveStaffOrder.mockReset().mockResolvedValue(undefined)
    ElMessage.success.mockClear()
  })

  it('把「李四」拖到「张三」上：按新顺序 [2,1] 调一次接口，表体第一行变成李四', async () => {
    const { wrapper } = await mountView2()
    expect(shownNames()).toEqual(['张三', '李四'])
    // 科长才有的拖动态：光标 move + draggable=true
    expect(nameTds(wrapper)[0].classes()).toContain('drag')
    expect(nameTds(wrapper)[0].attributes('draggable')).toBe('true')

    await dragRow(wrapper, 1, 0)
    expect(saveStaffOrder).toHaveBeenCalledTimes(1)
    expect(saveStaffOrder).toHaveBeenCalledWith([2, 1])
    expect(shownNames()).toEqual(['李四', '张三'])
    expect(ElMessage.success).toHaveBeenCalledWith('已调整人员顺序')
    expect(getSchedule).toHaveBeenCalledTimes(1) // 成功不再刷新，顺序以页面为准
  })

  it('接口失败：重新拉本月数据，顺序退回后端那份', async () => {
    const { wrapper } = await mountView2()
    saveStaffOrder.mockRejectedValueOnce(new Error('403'))
    const before = getSchedule.mock.calls.length
    await dragRow(wrapper, 1, 0)
    expect(saveStaffOrder).toHaveBeenCalledTimes(1)
    expect(getSchedule).toHaveBeenCalledTimes(before + 1)
    expect(shownNames()).toEqual(['张三', '李四']) // 重新加载后回到原顺序
    expect(ElMessage.success).not.toHaveBeenCalled()
  })

  it('搜索框有内容时不能拖：表是被过滤过的，顺序写回去会丢掉没显示的人', async () => {
    const { wrapper } = await mountView2()
    await typeKeyword('A0') // 工号 A01 / A02 都命中，两行仍都在表上
    expect(shownNames()).toEqual(['张三', '李四'])
    expect(nameTds(wrapper)[0].classes()).not.toContain('drag')
    expect(nameTds(wrapper)[0].attributes('draggable')).not.toBe('true')
    await dragRow(wrapper, 1, 0)
    expect(saveStaffOrder).not.toHaveBeenCalled()
    expect(shownNames()).toEqual(['张三', '李四'])
  })

  it('成员登录：姓名列不可拖', async () => {
    const { wrapper } = await mountView2({ admin: false })
    expect(wrapper.text()).toContain('张三') // 表已加载
    for (const td of nameTds(wrapper)) {
      expect(td.attributes('draggable')).not.toBe('true')
      expect(td.classes()).not.toContain('drag')
    }
    await dragRow(wrapper, 1, 0)
    expect(saveStaffOrder).not.toHaveBeenCalled()
  })

  it('有 1 处未暂存修改时拖放：排序照常生效，暂存（1）仍在', async () => {
    const { wrapper } = await mountView2()
    await click(cellTds(wrapper)[0]) // 张三 9-01 改一格
    await click(btnByText('确定'))
    expect(btnByText('暂存（1）')).toBeTruthy()

    await dragRow(wrapper, 1, 0)
    expect(saveStaffOrder).toHaveBeenCalledWith([2, 1])
    expect(shownNames()).toEqual(['李四', '张三'])
    expect(btnByText('暂存（1）')).toBeTruthy()
    // 待暂存以 staffId 为键：虚线格跟着张三一起换到第二行，没有算成李四的修改
    expect(pendingTds(wrapper)).toHaveLength(1)
    expect(wrapper.findAll('tbody tr:not(.cov)')[1].findAll('td.pending')).toHaveLength(1)
    await click(btnByText('暂存（1）'))
    expect(saveDraft.mock.calls[0][1].entries).toEqual([{ staffId: 1, workDate: '2026-09-01', shiftCode: 'D', remark: null }])
  })
})

describe('M6-02 弹窗设置值班电话', () => {
  it('只设置值班电话：整周黄底，暂存只发送值班电话', async () => {
    const { wrapper } = await mountDuty({ ym: '2026-10' })
    await click(dutyRowTds(wrapper, 0)[dayCol(8)])
    await click(btnByText('值班电话'))
    await click(btnByText('确定'))
    for (let day = 5; day <= 11; day++) {
      expect(dutyBg(dutyRowTds(wrapper, 0)[dayCol(day)])).toContain(DUTY_YELLOW)
    }
    expect(btnByText('暂存（1）')).toBeTruthy()
    await click(btnByText('暂存（1）'))
    expect(saveDraft.mock.calls[0][1]).toEqual({
      entries: [], dutyPhones: [{ weekStart: '2026-10-05', staffId: 1 }]
    })
  })

  it('替换李四时提示原负责人，并更新黄底', async () => {
    const months = {
      '2026-10': dutyMonthOf('2026-10', { dutyPhones: [dutyRow('2026-10-05', '2026-10-11', 2, '李四')] })
    }
    const { wrapper } = await mountDuty({ ym: '2026-10', months })
    await click(dutyRowTds(wrapper, 0)[dayCol(8)])
    expect(btnByText('值班电话').classList.contains('on')).toBe(false)
    await click(btnByText('值班电话'))
    await click(btnByText('确定'))
    expect(ElMessage.info).toHaveBeenCalledWith('已替换原值班电话负责人 李四')
    expect(dutyBg(dutyRowTds(wrapper, 0)[dayCol(8)])).toContain(DUTY_YELLOW)
    expect(dutyBg(dutyRowTds(wrapper, 1)[dayCol(8)])).not.toContain(DUTY_YELLOW)
  })

  it('取消张三的值班电话，暂存发送 null 且不写格子', async () => {
    const months = {
      '2026-10': dutyMonthOf('2026-10', { dutyPhones: [dutyRow('2026-10-05', '2026-10-11', 1, '张三')] })
    }
    const { wrapper } = await mountDuty({ ym: '2026-10', months })
    await click(dutyRowTds(wrapper, 0)[dayCol(8)])
    expect(btnByText('值班电话').classList.contains('on')).toBe(true)
    await click(btnByText('值班电话'))
    await click(btnByText('确定'))
    await click(btnByText('暂存（1）'))
    expect(saveDraft.mock.calls[0][1]).toEqual({
      entries: [], dutyPhones: [{ weekStart: '2026-10-05', staffId: null }]
    })
  })

  it('同时修改夜班和值班电话，暂存两处', async () => {
    const { wrapper } = await mountDuty({ ym: '2026-10' })
    await click(dutyRowTds(wrapper, 0)[dayCol(8)])
    await click(btnByText('夜班'))
    await click(btnByText('值班电话'))
    await click(btnByText('确定'))
    expect(btnByText('暂存（2）')).toBeTruthy()
    await click(btnByText('暂存（2）'))
    const body = saveDraft.mock.calls[0][1]
    expect(body.entries).toHaveLength(1)
    expect(body.entries[0].shiftCode).toBe('N')
    expect(body.dutyPhones).toEqual([{ weekStart: '2026-10-05', staffId: 1 }])
  })

  it('月初的格子关联跨月周，选择器和暂存保持同步', async () => {
    const { wrapper } = await mountDuty({ ym: '2026-10' })
    await click(dutyRowTds(wrapper, 0)[dayCol(2)])
    await click(btnByText('值班电话'))
    await click(btnByText('确定'))
    expect(dutySelects(wrapper)[0].props('modelValue')).toBe(1)
    await click(btnByText('暂存（1）'))
    expect(saveDraft.mock.calls[0][1].dutyPhones).toEqual([{ weekStart: '2026-09-28', staffId: 1 }])
  })

  it('什么都不改仍暂存一格，不暂存值班电话', async () => {
    const { wrapper } = await mountDuty({ ym: '2026-10' })
    await click(dutyRowTds(wrapper, 0)[dayCol(8)])
    await click(btnByText('确定'))
    expect(btnByText('暂存（1）')).toBeTruthy()
    await click(btnByText('暂存（1）'))
    expect(saveDraft.mock.calls[0][1].entries).toHaveLength(1)
    expect(saveDraft.mock.calls[0][1].dutyPhones).toEqual([])
  })

  it('弹窗提示本格值班电话按整周安排', async () => {
    const { wrapper } = await mountDuty({ ym: '2026-10' })
    await click(dutyRowTds(wrapper, 0)[dayCol(8)])
    expect(wrapper.text()).toContain('值班电话按周安排：10/5–10/11 整周')
  })
})
