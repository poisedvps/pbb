package com.hospital.pbb.swap.dto;

import com.hospital.pbb.swap.SwapType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 发起调班申请的请求体（任务单 M3-04，设计 §5 {@code POST /api/swaps}）。
 *
 * <p>申请人一律取登录态里的人员，请求体里没有 {@code applicantStaffId}，
 * 免得成员替别人发起申请。</p>
 *
 * @param type          SWAP=换班、LEAVE=请假、COVER=替班
 * @param applicantDate 本人要调的那一天，必须是今天及以后
 * @param targetStaffId 对方人员 id，换班、替班必填，请假忽略
 * @param targetDate    对方那一天，只有换班必填（替班只要对方来上本人那天，存 null）
 * @param reason        申请理由，可空
 */
public record CreateSwapRequest(@NotNull SwapType type, @NotNull LocalDate applicantDate,
                                Long targetStaffId, LocalDate targetDate, @Size(max = 200) String reason) {}
