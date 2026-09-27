package com.hospital.pbb.schedule.dto;

/**
 * 发布整月排班的结果（任务单 M2-05）。
 *
 * @param version 本次发布的版本号，等于 {@code schedule_month.version} 自增后的值
 * @param count   本次复制成快照的格数
 */
public record PublishResultVO(int version, int count) {}
