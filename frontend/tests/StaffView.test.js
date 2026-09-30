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

// ── M5-13 批量导出 / 批量导入 ────────────────────────────
// 导出走的是文件流，上传成不成功只看 uploadForFile 的布尔返回值，这里统一打桩
vi.mock('../src/api/download', () => ({
  download: vi.fn(() => Promise.resolve()),
  uploadForFile: vi.fn(() => Promise.resolve(true))
}))

const { download, uploadForFile } = await import('../src/api/download')

const barButton = (text) =>
  [...document.querySelectorAll('.bar button')].find((b) => b.textContent.trim() === text)
const fileInputElement = () => document.querySelector('.bar input[type=file]')
const loadingButtons = () => [...document.querySelectorAll('.bar button.is-loading')].map((b) => b.textContent.trim())
// jsdom 不让直接往 input.files 里塞东西，只能把这个只读属性换成数组
const selectFile = async (file) => {
  const el = fileInputElement()
  Object.defineProperty(el, 'files', { value: file ? [file] : [], configurable: true, writable: true })
  el.dispatchEvent(new Event('change'))
  await flushPromises()
}

const mountBatchView = async () => {
  ElMessageBox.alert = vi.fn(() => Promise.resolve('ok'))
  download.mockClear()
  uploadForFile.mockClear().mockResolvedValue(true)
  await mountView() // 里面已把 listStaff 计数归零，挂载完是 1 次
  ElMessageBox.alert.mockClear()
}

describe('M5-13 批量导出 / 批量导入', () => {
  it('工具栏有【批量导出】【批量导入】和一行格式提示，都在【新增人员】旁边', async () => {
    await mountBatchView()
    const labels = [...document.querySelectorAll('.bar button')].map((b) => b.textContent.trim())
    expect(labels).toEqual(['批量导出', '批量导入', '新增人员'])
    expect(document.querySelector('.bar .tip').textContent).toBe(
      '导入请使用【批量导出】得到的表格格式，一次最多 500 人'
    )
    // 文件框隐藏且只认 xlsx
    const input = fileInputElement()
    expect(input.getAttribute('style')).toContain('display: none')
    expect(input.getAttribute('accept')).toBe('.xlsx')
  })

  it('点「批量导出」：按人员名单.xlsx 下载 /staff/export，不带参数', async () => {
    await mountBatchView()
    await click(barButton('批量导出'))
    expect(download).toHaveBeenCalledTimes(1)
    expect(download).toHaveBeenCalledWith('/staff/export', null, '人员名单.xlsx')
    expect(uploadForFile).not.toHaveBeenCalled()
  })

  it('点「批量导入」只是唤出隐藏文件框，不直接上传', async () => {
    await mountBatchView()
    const clickSpy = vi.spyOn(fileInputElement(), 'click')
    await click(barButton('批量导入'))
    expect(clickSpy).toHaveBeenCalledTimes(1)
    expect(uploadForFile).not.toHaveBeenCalled()
  })

  it('选文件后上传成功：传到 /staff/import，提示一次并刷新列表', async () => {
    await mountBatchView()
    const file = new File(['x'], '人员名单.xlsx')
    await selectFile(file)
    expect(uploadForFile).toHaveBeenCalledTimes(1)
    expect(uploadForFile).toHaveBeenCalledWith('/staff/import', file, '人员导入结果.xlsx')
    expect(ElMessageBox.alert).toHaveBeenCalledTimes(1)
    expect(ElMessageBox.alert.mock.calls[0][0]).toContain('初始密码只出现这一次')
    expect(ElMessageBox.alert.mock.calls[0][1]).toBe('导入完成')
    expect(listStaff).toHaveBeenCalledTimes(2) // 初次 + 导入后刷新
    expect(loadingButtons()).toEqual([]) // 导入完毕后按钮不再转
  })

  it('上传失败（如 1210 整批未导入）：不提示导入完成，也不刷新列表', async () => {
    await mountBatchView()
    uploadForFile.mockResolvedValue(false)
    await selectFile(new File(['x'], '人员名单.xlsx'))
    expect(uploadForFile).toHaveBeenCalledTimes(1)
    expect(ElMessageBox.alert).not.toHaveBeenCalled()
    expect(listStaff).toHaveBeenCalledTimes(1)
    expect(loadingButtons()).toEqual([])
  })

  it('上传成功后把「导入完成」弹窗关掉（alert 被 reject）：已成功导入仍要刷新一次', async () => {
    await mountBatchView()
    ElMessageBox.alert.mockRejectedValue(new Error('cancel')) // 按 Esc / 点关闭
    await selectFile(new File(['x'], '人员名单.xlsx'))
    expect(uploadForFile).toHaveBeenCalledTimes(1)
    expect(ElMessageBox.alert).toHaveBeenCalledTimes(1)
    expect(listStaff).toHaveBeenCalledTimes(2) // 刷新不挂在弹窗确认上
    expect(loadingButtons()).toEqual([])
    expect(fileInputElement().value).toBe('')
  })

  it('上传接口自己抛异常（兜底）：不提示导入完成，不刷新，按钮不卡在转圈', async () => {
    await mountBatchView()
    uploadForFile.mockRejectedValue(new Error('network'))
    await selectFile(new File(['x'], '人员名单.xlsx'))
    expect(ElMessageBox.alert).not.toHaveBeenCalled()
    expect(listStaff).toHaveBeenCalledTimes(1)
    expect(loadingButtons()).toEqual([])
    expect(fileInputElement().value).toBe('')
  })

  it('文件框被取消（没选到文件）：不上传、不提示、不刷新', async () => {
    await mountBatchView()
    await selectFile(null)
    expect(uploadForFile).not.toHaveBeenCalled()
    expect(ElMessageBox.alert).not.toHaveBeenCalled()
    expect(listStaff).toHaveBeenCalledTimes(1)
    expect(loadingButtons()).toEqual([])
  })

  it('同一份文件改完能再选一次：change 后文件框已清空，第二次仍会上传', async () => {
    await mountBatchView()
    const file = new File(['x'], '人员名单.xlsx')
    await selectFile(file)
    expect(fileInputElement().value).toBe('')
    await selectFile(file)
    expect(uploadForFile).toHaveBeenCalledTimes(2)
    expect(ElMessageBox.alert).toHaveBeenCalledTimes(2)
    expect(listStaff).toHaveBeenCalledTimes(3) // 初次 + 两次导入后刷新
  })
})
