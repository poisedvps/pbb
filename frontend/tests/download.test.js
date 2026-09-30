// M5-05 上传 / 下载工具验收用例。运行：在 frontend/ 下执行 npx vitest run
import { describe, it, expect, beforeEach, vi } from 'vitest'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { download, uploadForFile } from '../src/api/download'
import { deleteStaff } from '../src/api/staff'
import http from '../src/api/http'

// download.js 用的是独立的 axios 实例（不进 http 拦截器），这里整个打桩掉
vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), info: vi.fn(), warning: vi.fn(), error: vi.fn() }
}))
vi.mock('../src/api/http', () => ({ default: { delete: vi.fn(() => Promise.resolve(null)) } }))

// vitest 的 jsdom 环境不把 localStorage 透出来，download.js 读 token 要用
if (typeof globalThis.localStorage?.getItem !== 'function') {
  const store = new Map()
  globalThis.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
    removeItem: (k) => store.delete(k)
  }
}

// jsdom 25 的 Blob 没有 text()，浏览器里有；补一个真的读文件的实现，
// 让「200 + JSON 里读 message」这条分支在测试里跑的是真代码，而不是把响应体换成字符串
if (typeof Blob.prototype.text !== 'function') {
  Blob.prototype.text = function () {
    return new Promise((resolve, reject) => {
      const fr = new FileReader()
      fr.onload = () => resolve(String(fr.result))
      fr.onerror = () => reject(fr.error)
      fr.readAsText(this)
    })
  }
}

// jsdom 不支持下载，<a download> 的 click 会被当成「导航」打一屏 Not implemented 噪音。
// 拦掉 click，保存动作靠 createObjectURL / revokeObjectURL / click 本身断言
const anchorClick = vi.spyOn(HTMLElement.prototype, 'click').mockImplementation(() => {})

// jsdom 没有 URL.createObjectURL，保存文件那两步用 vi.fn 打桩
const createObjectURL = vi.fn(() => 'blob:mock')
const revokeObjectURL = vi.fn()

const XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
const fileOf = (text = 'x', name = '人员名单.xlsx') => new File([text], name, { type: XLSX })

beforeEach(() => {
  URL.createObjectURL = createObjectURL
  URL.revokeObjectURL = revokeObjectURL
  localStorage.setItem('pbb_token', 'tok')
  vi.clearAllMocks() // 只清调用记录，上面的打桩实现保留
})

describe('M5-05 uploadForFile', () => {
  it('导入成功返回文件：存下来并返回 true，上传的是 multipart 的 file 字段', async () => {
    const file = fileOf()
    axios.post.mockResolvedValue({ headers: { 'content-type': XLSX }, data: new Blob(['x']) })

    const ok = await uploadForFile('/staff/import', file, '人员导入结果.xlsx')

    expect(ok).toBe(true)
    expect(ElMessage.error).not.toHaveBeenCalled()
    expect(createObjectURL).toHaveBeenCalledTimes(1)
    expect(revokeObjectURL).toHaveBeenCalledTimes(1)
    expect(anchorClick).toHaveBeenCalledTimes(1)

    const [url, body, config] = axios.post.mock.calls[0]
    expect(url).toBe('/api/staff/import')
    expect(body).toBeInstanceOf(FormData)
    expect(body.get('file')).toBe(file)
    expect(config.responseType).toBe('blob')
    expect(config.headers.Authorization).toBe('Bearer tok')
  })

  it('导入失败返回 200 + JSON：弹出后端 message 并返回 false，不存文件', async () => {
    const message = '导入失败，没有写入任何数据：第2行：姓名不能为空'
    axios.post.mockResolvedValue({
      headers: { 'content-type': 'application/json' },
      data: new Blob([JSON.stringify({ code: 1210, message })])
    })

    const ok = await uploadForFile('/staff/import', fileOf(), '人员导入结果.xlsx')

    expect(ok).toBe(false)
    expect(ElMessage.error).toHaveBeenCalledTimes(1)
    expect(ElMessage.error).toHaveBeenCalledWith(message)
    expect(createObjectURL).not.toHaveBeenCalled()
  })

  it('请求抛错：返回 false 并提示“上传失败”', async () => {
    axios.post.mockRejectedValue(new Error('Request failed with status code 403'))

    const ok = await uploadForFile('/staff/import', fileOf(), '人员导入结果.xlsx')

    expect(ok).toBe(false)
    expect(ElMessage.error).toHaveBeenCalledWith('上传失败')
    expect(createObjectURL).not.toHaveBeenCalled()
  })

  it('返回 JSON 但读不出 message：仍然提示“上传失败”，不存文件', async () => {
    axios.post.mockResolvedValue({ headers: { 'content-type': 'application/json' }, data: new Blob(['不是 json']) })

    const ok = await uploadForFile('/staff/import', fileOf(), '人员导入结果.xlsx')

    expect(ok).toBe(false)
    expect(ElMessage.error).toHaveBeenCalledWith('上传失败')
    expect(createObjectURL).not.toHaveBeenCalled()
  })
})

describe('M5-05 download 原行为不变', () => {
  it('download 拿到文件：一样是 createObjectURL 一次并触发保存', async () => {
    axios.get.mockResolvedValue({ headers: { 'content-type': XLSX }, data: new Blob(['x']) })

    await download('/x', null, 'a.xlsx')

    expect(createObjectURL).toHaveBeenCalledTimes(1)
    expect(revokeObjectURL).toHaveBeenCalledTimes(1)
    expect(anchorClick).toHaveBeenCalledTimes(1)
    expect(axios.get.mock.calls[0][0]).toBe('/api/x')
    expect(document.querySelector('a[download="a.xlsx"]')).toBeNull() // 临时 <a> 已拆掉
  })
})

describe('M5-05 deleteStaff', () => {
  it('按 id 发 DELETE /staff/{id}', async () => {
    await deleteStaff(7)
    expect(http.delete).toHaveBeenCalledWith('/staff/7')
  })
})
