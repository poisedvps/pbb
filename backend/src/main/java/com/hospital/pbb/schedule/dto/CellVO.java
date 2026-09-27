package com.hospital.pbb.schedule.dto;

/**
 * 一个单元格：某人在某一天排了什么班。
 *
 * @param shiftCode 班次代号，取值见 shift_type.code
 * @param manual    是否科长手工改过。<b>只有草稿才有意义</b>，已发布快照里一律为 false
 * @param remark    备注，没有为 null
 */
public record CellVO(String shiftCode, boolean manual, String remark) {}
