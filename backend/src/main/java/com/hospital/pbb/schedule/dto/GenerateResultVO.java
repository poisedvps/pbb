package com.hospital.pbb.schedule.dto;

/**
 * "按规则生成"整月排班的结果（任务单 M2-04）。
 *
 * @param generated     本次按规则写入的格数
 * @param skippedManual 因为科长手工改过（{@code is_manual}）而保留原样的格数
 */
public record GenerateResultVO(int generated, int skippedManual) {}
