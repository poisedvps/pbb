package com.hospital.pbb.swap.dto;

import com.hospital.pbb.swap.SwapStatus;
import com.hospital.pbb.swap.SwapType;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 调班列表里的一条申请（任务单 M3-03，设计 §5 {@code /api/swaps}）。
 *
 * <p>三个 {@code canXxx} 由后端按当前登录人算好，前端只按标志显示按钮，
 * 免得前端自己推状态机推错：</p>
 * <ul>
 *   <li>{@code canConfirm} —— 对方确认：我是对方且还在等对方确认；</li>
 *   <li>{@code canCancel} —— 本人撤销：我是申请人且流程还没走完；</li>
 *   <li>{@code canApprove} —— 科长审批：科长看还在等审批的记录。</li>
 * </ul>
 *
 * @param id              主键
 * @param no              单据号，形如 {@code TB-0003}（id 左补零到 4 位）
 * @param type            SWAP=换班、LEAVE=请假、COVER=替班
 * @param applicantStaffId 申请人人员 id
 * @param applicantName   申请人姓名，人员已删除时为 null
 * @param applicantDate   申请人要调的那一天
 * @param applicantShift  申请人那天的已发布班次代码，查不到为 null
 * @param targetStaffId   对方人员 id，LEAVE 为 null
 * @param targetName      对方姓名，LEAVE 或人员已删除时为 null
 * @param targetDate      对方的那一天，LEAVE 为 null
 * @param targetShift     对方那天的已发布班次代码，只有 SWAP 才需要互换，其余为 null
 * @param reason          申请理由
 * @param status          当前状态
 * @param reviewComment   科长审批意见，未审批为 null
 * @param createdAt       发起时间
 */
public record SwapVO(Long id, String no, SwapType type,
                     Long applicantStaffId, String applicantName, LocalDate applicantDate, String applicantShift,
                     Long targetStaffId, String targetName, LocalDate targetDate, String targetShift,
                     String reason, SwapStatus status, String reviewComment, OffsetDateTime createdAt,
                     boolean canConfirm, boolean canCancel, boolean canApprove) {}
