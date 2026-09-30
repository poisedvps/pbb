package com.hospital.pbb.staff.dto;

import java.util.List;

/**
 * {@code StaffImportParser} 的解析结果（设计 §9.5）。
 *
 * <p>{@code errors} 非空时 {@code rows} 不可用：调用方只把错误逐条返回给前端，整批不导入。</p>
 *
 * @param rows   解析出的数据行，{@code errors} 非空时无意义
 * @param errors 错误信息，已带 Excel 行号；为空表示整份文件可以导入
 */
public record StaffImportParseResult(List<StaffImportRow> rows, List<String> errors) {
}
