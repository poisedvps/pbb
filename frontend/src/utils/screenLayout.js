// 大屏一屏铺满的尺寸计算（设计 §11.4）。width / height 是表格区域的像素尺寸
const clamp = (v, lo, hi) => Math.round(Math.min(hi, Math.max(lo, v)))

export function screenLayout({ width, height, rows, days }) {
  const n = Math.max(1, rows)
  const d = Math.max(1, days)
  const colW = Math.floor((width - Math.round(width * 0.07)) / d)
  // 除不尽的余数给姓名列和表头，保证横向、纵向都不留空
  const nameW = width - colW * d
  const rowH = Math.max(20, Math.floor((height - Math.max(40, Math.round(height * 0.07))) / n))
  const headH = Math.max(40, height - rowH * n)
  return {
    nameW,
    colW,
    headH,
    rowH,
    cellFont: clamp(Math.min(rowH * 0.5, (colW - 8) / 2.1), 12, 40),
    nameFont: clamp(Math.min(rowH * 0.45, (nameW - 16) / 4), 12, 36),
    headFont: clamp(Math.min(headH * 0.36, colW * 0.4), 12, 28)
  }
}
