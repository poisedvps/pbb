// M5-10 人员管理页「停用」改「删除」验收用例。运行：在 frontend/ 下执行 npx vitest run
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import StaffView from '../src/views/StaffView.vue'
import { listStaff, deleteStaff } from '../src/api/staff'

vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal()
  return {
    ...mod,
    ElMessage: { success: vi.fn(), info: vi.fn(), error: vi.fn(), warning: vi.fn() },
    ElMessageBox: {
      prompt: vi.fn(() => Promise.resolve({ value: '' })),
      confirm: vi.fn(() => Promise.resolve('confirm'))
    }
  }
})

vi.mock('../src/api/staff', () => ({
  listStaff: vi.fn(() =>
    Promise.resolve([
      { id: 1, empNo: 'A01', name: '张三', role: 'ADMIN', active: true, schedulable: true },
      { id: 2, empNo: 'A02', name: '李四', role: 'MEMBER', active: true, schedulable: true }
    ])
  ),
  createStaff: vi.fn(),
  updateStaff: vi.fn(),
  saveStaffOrder: vi.fn(() => Promise.resolve()),
  deleteStaff: vi.fn(() => Promise.resolve())
}))

const { ElMessageBox, ElMessage } = await import('element-plus')

const rowByText = (text) => [...document.querySelectorAll('tbody tr')].find((tr) => tr.textContent.includes(text))
const rowButtons = (text) => [...(rowByText(text)?.querySelectorAll('button') || [])].map((b) => b.textContent.trim())
const btnInRow = (rowText, btnText) =>
  [...(rowByText(rowText)?.querySelectorAll('button') || [])].find((b) => b.textContent.trim() === btnText)
const click = async (el) => {
  await el.click()
  await flushPromises()
}

let wrapper = null
const mountView = async () => {
  listStaff.mockClear()
  deleteStaff.mockClear().mockResolvedValue(undefined)
  ElMessageBox.prompt.mockClear().mockResolvedValue({ value: '李四' })
  ElMessage.success.mockClear()
  wrapper = mount(StaffView, { attachTo: document.body, global: { plugins: [ElementPlus] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  document.body.innerHTML = ''
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('M5-10 删除人员', () => {
  it('科长那一行没有删除按钮，成员那一行有；页面上不再有停用 / 启用按钮', async () => {
    await mountView()
    expect(listStaff).toHaveBeenCalledTimes(1)
    expect(rowButtons('李四')).toContain('删除')
    expect(rowButtons('张三')).not.toContain('删除')
    const buttons = [...document.querySelectorAll('button')].map((b) => b.textContent.trim())
    expect(buttons).not.toContain('停用')
    expect(buttons).not.toContain('启用')
  })

  it('输入姓名确认后删除：deleteStaff(2) 调一次，提示已删除，列表刷新一次', async () => {
    await mountView()
    await click(btnInRow('李四', '删除'))
    expect(ElMessageBox.prompt).toHaveBeenCalledTimes(1)
    expect(ElMessageBox.prompt.mock.calls[0][0]).toContain('李四')
    expect(ElMessageBox.prompt.mock.calls[0][0]).toContain('无法恢复')
    expect(ElMessageBox.prompt.mock.calls[0][1]).toBe('删除人员')
    expect(deleteStaff).toHaveBeenCalledTimes(1)
    expect(deleteStaff).toHaveBeenCalledWith(2)
    expect(ElMessage.success).toHaveBeenCalledWith('已删除')
    expect(listStaff).toHaveBeenCalledTimes(2) // 初次 + 删除后刷新
  })

  it('弹窗取消：不调 deleteStaff，也不刷新列表', async () => {
    await mountView()
    ElMessageBox.prompt.mockRejectedValueOnce('cancel')
    await click(btnInRow('李四', '删除'))
    expect(ElMessageBox.prompt).toHaveBeenCalledTimes(1)
    expect(deleteStaff).not.toHaveBeenCalled()
    expect(ElMessage.success).not.toHaveBeenCalled()
    expect(listStaff).toHaveBeenCalledTimes(1)
  })

  it('确认框的 inputValidator：姓名一致放行，不一致报「姓名不一致」', async () => {
    await mountView()
    await click(btnInRow('李四', '删除'))
    const opts = ElMessageBox.prompt.mock.calls[0][2]
    expect(opts.confirmButtonText).toBe('删除')
    expect(opts.cancelButtonText).toBe('取消')
    expect(opts.type).toBe('warning')
    expect(opts.inputValidator('李四')).toBe(true)
    expect(opts.inputValidator('张三')).toBe('姓名不一致')
  })

  it('删除失败：不提示成功，也不刷新列表', async () => {
    await mountView()
    deleteStaff.mockRejectedValueOnce(new Error('1202 该人员存在排班数据'))
    await click(btnInRow('李四', '删除'))
    expect(deleteStaff).toHaveBeenCalledTimes(1)
    expect(ElMessage.success).not.toHaveBeenCalled()
    expect(listStaff).toHaveBeenCalledTimes(1)
  })

  it('编辑弹窗不再有「在职」开关，保存时仍带上当前行的 active', async () => {
    const { updateStaff } = await import('../src/api/staff')
    await mountView()
    await click(btnInRow('李四', '编辑'))
    // 状态列也有「在职」文字，只看表单 label
    expect([...document.querySelectorAll('label')].map((l) => l.textContent.trim())).not.toContain('在职')
    const save = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === '保存')
    await click(save)
    expect(updateStaff).toHaveBeenCalledTimes(1)
    expect(updateStaff.mock.calls[0]).toEqual([2, { name: '李四', position: '', phone: '', schedulable: true, active: true }])
  })

  it('「显示已停用」勾选框与状态列保留', async () => {
    await mountView()
    expect(document.body.textContent).toContain('显示已停用')
    // 状态列仍在：在职的人显示「在职」标签
    expect(rowByText('李四').textContent).toContain('在职')
  })
})
