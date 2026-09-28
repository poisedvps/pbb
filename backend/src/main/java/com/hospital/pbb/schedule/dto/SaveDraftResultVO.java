package com.hospital.pbb.schedule.dto;

/**
 * 暂存结果（任务单 M4-09）。
 *
 * @param entries    写入的格数
 * @param dutyPhones 写入的值班电话周数（含清除的周）
 */
public record SaveDraftResultVO(int entries, int dutyPhones) {}
