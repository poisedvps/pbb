import axios from 'axios'
import { ElMessage } from 'element-plus'

// 导出类接口返回的是文件流而不是 { code, message, data }，走 api/http.js 的拦截器会被当成
// 业务失败直接 reject 掉，所以这里用一个独立的 axios 调用，不进 http 实例。
// url: /api 之后的路径，如 '/schedules/2026-10/export'；params: query 参数，没有传 null
// 以当前登录 token 下载文件并触发浏览器保存
export async function download(url, params, filename) {
  let resp
  try {
    resp = await axios.get('/api' + url, {
      params,
      responseType: 'blob',
      headers: { Authorization: 'Bearer ' + localStorage.getItem('pbb_token') }
    })
  } catch {
    // 401 / 403 这类 HTTP 层错误，blob 响应里读不到后端的 message，统一提示一次
    ElMessage.error('下载失败')
    return
  }

  // 业务失败（如 1504 本月还没有草稿）后端给的仍是 200 + { code, message }，
  // 不是文件；按 content-type 判出来，弹出后端的 message 而不是存下一个 json 文件
  if (String(resp.headers['content-type'] || '').includes('application/json')) {
    try {
      const body = JSON.parse(await resp.data.text())
      ElMessage.error(body.message || '下载失败')
    } catch {
      ElMessage.error('下载失败')
    }
    return
  }

  const href = URL.createObjectURL(resp.data)
  const a = document.createElement('a')
  a.href = href
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(href)
}
