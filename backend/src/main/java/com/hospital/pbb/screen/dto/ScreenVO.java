package com.hospital.pbb.screen.dto;

import com.hospital.pbb.schedule.dto.MonthScheduleVO;

/**
 * 大屏一次刷新的全部数据：整月已发布排班 + 今日概况。
 *
 * <p>month 复用 {@link MonthScheduleVO}（{@code draft=false}，即已发布快照），
 * 大屏不必知道排班表内部还有草稿这一份。</p>
 *
 * @param month 整月已发布排班
 * @param today 今日概况
 */
public record ScreenVO(MonthScheduleVO month, TodayVO today) {}
