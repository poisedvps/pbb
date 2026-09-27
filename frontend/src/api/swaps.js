import http from './http'

// 调班接口（后端 /api/swaps）。返回体已由 http 拦截器解包，拿到的就是 data
export const listSwaps = (scope) => http.get('/swaps', { params: { scope } }) // → SwapVO[]
export const createSwap = (data) => http.post('/swaps', data) // data: { type, applicantDate, targetStaffId, targetDate, reason } → SwapVO
export const confirmSwap = (id) => http.post(`/swaps/${id}/confirm`)
export const rejectPeerSwap = (id) => http.post(`/swaps/${id}/reject-peer`)
export const cancelSwap = (id) => http.post(`/swaps/${id}/cancel`)
export const approveSwap = (id, comment) => http.post(`/swaps/${id}/approve`, { comment })
export const rejectSwap = (id, comment) => http.post(`/swaps/${id}/reject`, { comment })
