package com.hospital.pbb.swap.dto;

import jakarta.validation.constraints.Size;

/**
 * 科长审批时的请求体（任务单 M3-05，设计 §5 {@code POST /api/swaps/{id}/approve}、
 * {@code POST /api/swaps/{id}/reject}）。
 *
 * <p>两条接口都可以不带请求体——审批意见是可选的，接口在 {@code body} 缺省时照常工作，
 * 所以 Controller 用 {@code @RequestBody(required = false)} 接。</p>
 *
 * @param comment 审批意见，原样写入 {@code swap_request.review_comment}，长度上限与 {@code reason} 一致
 */
public record ReviewRequest(@Size(max = 200) String comment) {}
