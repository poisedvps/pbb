package com.hospital.pbb.stats.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 统计报表（设计 §5 {@code GET /api/stats}）：{@code [from, to]} 区间内按人统计。
 *
 * @param from 统计开始日期（含）
 * @param to   统计结束日期（含）
 * @param rows 人员行，按人员 sort_order 排序；成员只有本人一行
 */
public record StatsVO(LocalDate from, LocalDate to, List<StatsRowVO> rows) {}
