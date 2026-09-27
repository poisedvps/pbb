package com.hospital.pbb.screen.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 大屏的今日概况（任务单 M2-07）。
 *
 * <p>大屏账号自己不算班次，所以这里只统计人员：白班只报人数，
 * 夜班、值班、请假要念出姓名，顺序一律按人员排序（sort_order、id），
 * 和整月表格里各行的顺序保持一致，大屏上不会今天一个叫 A、明天一个叫 B。</p>
 *
 * @param date     今日日期
 * @param dayCount 今日白班 {@code D} 的人数
 * @param night    今日夜班 {@code N} 的姓名，按人员排序
 * @param duty     今日值班 {@code Z} 的姓名，按人员排序
 * @param leave    今日请假 {@code L} 的姓名，按人员排序
 */
public record TodayVO(LocalDate date, int dayCount, List<String> night, List<String> duty, List<String> leave) {}
