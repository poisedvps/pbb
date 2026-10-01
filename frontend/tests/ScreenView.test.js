import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ScreenView from '../src/views/ScreenView.vue'
import { useAuthStore } from '../src/stores/auth'
import { getScreen } from '../src/api/screen'
import { listShiftTypes } from '../src/api/shifts'

vi.mock('../src/api/screen', () => ({ getScreen: vi.fn() }))
vi.mock('../src/api/shifts', () => ({ listShiftTypes: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

const days = Array.from({ length: 31 }, (_, i) => ({
  date: `2026-10-${String(i + 1).padStart(2, '0')}`, weekday: 1, kind: 'WORKDAY'
}))
const makeMonth = (n) => ({
  yearMonth: '2026-10', version: 1, dutyPhoneColor: '#fde047', dutyPhones: [], days,
  rows: Array.from({ length: n }, (_, i) => ({
    staffId: i + 1, name: `人员${i + 1}`,
    cells: Object.fromEntries(days.map(({ date }) => [date, { shiftCode: i === 0 && date === '2026-10-01' ? 'H' : 'D' }]))
  }))
})

if (typeof globalThis.localStorage?.getItem !== 'function') {
  const store = new Map()
  globalThis.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
    removeItem: (k) => store.delete(k)
  }
}

let wrapper
const mountView = async (n = 20) => {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().user = { id: 9, username: 'screen', role: 'SCREEN' }
  getScreen.mockResolvedValue({ month: makeMonth(n), today: null })
  listShiftTypes.mockResolvedValue([
    { code: 'D', name: '白班', color: '#1d4ed8', enabled: true },
    { code: 'H', name: '假日值班', color: '#0f766e', enabled: true },
    { code: 'B', name: '备班', color: '#92400e', enabled: false }
  ])
  wrapper = mount(ScreenView, { attachTo: document.body, global: { plugins: [pinia] } })
  await flushPromises()
  return wrapper
}

beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', { configurable: true, get: () => 1850 })
  Object.defineProperty(HTMLElement.prototype, 'clientHeight', { configurable: true, get: () => 900 })
})
afterAll(() => {
  delete HTMLElement.prototype.clientWidth
  delete HTMLElement.prototype.clientHeight
})
beforeEach(() => { document.body.innerHTML = '' })
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  delete document.fullscreenElement
  delete document.documentElement.requestFullscreen
  delete document.exitFullscreen
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

describe('M7-03 大屏一屏铺满', () => {
  it('20 人全部展示，没有卡片和翻页提示', async () => {
    const view = await mountView()
    expect(view.findAll('tbody tr')).toHaveLength(20)
    expect(view.find('.cards').exists()).toBe(false)
    expect(view.text()).not.toContain('自动翻页')
    expect(view.text()).not.toContain('本周值班电话')
  })

  it('20 人使用对应的行高、字号和 32 列', async () => {
    const view = await mountView()
    expect(view.find('table.grid').attributes('data-row-h')).toBe('41')
    expect(view.find('table.grid').attributes('data-cell-font')).toBe('21')
    expect(view.findAll('colgroup col')).toHaveLength(32)
  })

  it('10 人放大行高和姓名字号', async () => {
    const view = await mountView(10)
    expect(view.find('table.grid').attributes('data-row-h')).toBe('83')
    expect(view.find('table.grid').attributes('data-name-font')).toBe('32')
  })

  it('长班次名在格子里截断，图例只展示启用的班次', async () => {
    const view = await mountView()
    expect(view.find('tbody tr td:nth-child(2) .chip').text()).toBe('假日')
    const foot = view.find('.foot').text()
    expect(foot).toContain('白班')
    expect(foot).toContain('假日值班')
    expect(foot).toContain('值班电话')
    expect(foot).not.toContain('备班')
  })

  it('点击全屏调用浏览器全屏 API', async () => {
    const view = await mountView()
    document.documentElement.requestFullscreen = vi.fn(() => Promise.resolve())
    await view.find('.btns button').trigger('click')
    expect(document.documentElement.requestFullscreen).toHaveBeenCalledTimes(1)
  })

  it('浏览器全屏状态变化后可退出全屏', async () => {
    const view = await mountView()
    Object.defineProperty(document, 'fullscreenElement', { configurable: true, get: () => document.documentElement })
    document.dispatchEvent(new Event('fullscreenchange'))
    await flushPromises()
    expect(view.find('.btns button').text()).toBe('退出全屏')
    document.exitFullscreen = vi.fn(() => Promise.resolve())
    await view.find('.btns button').trigger('click')
    expect(document.exitFullscreen).toHaveBeenCalledTimes(1)
  })
})
