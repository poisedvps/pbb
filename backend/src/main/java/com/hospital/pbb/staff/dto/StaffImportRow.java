package com.hospital.pbb.staff.dto;

import com.hospital.pbb.user.Role;

/**
 * 一行人员导入数据（设计 §9.5）。
 *
 * <p>{@code rowNo} 是 Excel 行号，表头算第 1 行，所以第一条数据行是第 2 行。
 * {@code position}、{@code phone}、{@code role} 在文件里为空时存 {@code null}；
 * {@code role == null} 表示“角色”列为空（导入时新人员默认成员，已有人员不改角色）。</p>
 *
 * @param rowNo       Excel 行号（表头是第 1 行）
 * @param empNo       工号
 * @param name        姓名
 * @param position    岗位，空列为 {@code null}
 * @param phone       联系电话，空列为 {@code null}
 * @param schedulable 是否参与排班，空列按“是”处理
 * @param role        角色，空列为 {@code null}，科长＝{@link Role#ADMIN}，成员＝{@link Role#MEMBER}
 */
public record StaffImportRow(int rowNo, String empNo, String name, String position, String phone,
                             boolean schedulable, Role role) {
}
