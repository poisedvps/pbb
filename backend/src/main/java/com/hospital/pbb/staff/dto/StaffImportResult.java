package com.hospital.pbb.staff.dto;

import java.util.List;

/**
 * 批量导入的结果（设计 §9.3）。
 *
 * <p>{@code lines} 与导入文件的数据行一一对应，供【导入结果】xlsx 逐行回显。
 * {@code tempPassword} 只有新增行才有值，且只在这一次响应里出现，后端不存明文，
 * 所以更新行固定是 {@code null}。</p>
 *
 * @param created 新增人数
 * @param updated 更新人数
 * @param lines   每行的结果
 */
public record StaffImportResult(int created, int updated, List<Line> lines) {

    /** result = "新增" 或 "更新"；tempPassword 仅新增行有值 */
    public record Line(String empNo, String name, String result, String tempPassword) {}
}
