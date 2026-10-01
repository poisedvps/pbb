import { describe, it, expect } from 'vitest'
import { screenLayout } from '../src/utils/screenLayout'

describe('screenLayout', () => {
  it('31 天 10 人', () => {
    expect(screenLayout({ width: 1850, height: 900, rows: 10, days: 31 })).toEqual({
      nameW: 145, colW: 55, headH: 70, rowH: 83, cellFont: 22, nameFont: 32, headFont: 22
    })
  })

  it('31 天 20 人', () => {
    expect(screenLayout({ width: 1850, height: 900, rows: 20, days: 31 })).toEqual({
      nameW: 145, colW: 55, headH: 80, rowH: 41, cellFont: 21, nameFont: 18, headFont: 22
    })
  })

  it('30 天 15 人', () => {
    expect(screenLayout({ width: 1850, height: 900, rows: 15, days: 30 })).toEqual({
      nameW: 140, colW: 57, headH: 75, rowH: 55, cellFont: 23, nameFont: 25, headFont: 23
    })
  })

  it('28 天 20 人', () => {
    expect(screenLayout({ width: 1850, height: 900, rows: 20, days: 28 })).toEqual({
      nameW: 142, colW: 61, headH: 80, rowH: 41, cellFont: 21, nameFont: 18, headFont: 24
    })
  })

  it('31 天 40 人', () => {
    expect(screenLayout({ width: 1850, height: 900, rows: 40, days: 31 })).toEqual({
      nameW: 145, colW: 55, headH: 100, rowH: 20, cellFont: 12, nameFont: 12, headFont: 22
    })
  })

  it('0 人按 1 人计算', () => {
    expect(screenLayout({ width: 1850, height: 900, rows: 0, days: 31 })).toEqual({
      nameW: 145, colW: 55, headH: 63, rowH: 837, cellFont: 22, nameFont: 32, headFont: 22
    })
  })

  it('铺满不留空', () => {
    for (const rows of [10, 15, 20]) {
      for (const days of [28, 30, 31]) {
        const { nameW, colW, headH, rowH } = screenLayout({ width: 1850, height: 900, rows, days })
        expect(nameW + colW * days).toBe(1850)
        expect(headH + rowH * rows).toBe(900)
      }
    }
  })
})
