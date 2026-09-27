package com.hospital.pbb.swap;

/**
 * 一条调班申请的状态（设计 §4 swap_request.status）。
 *
 * <p>PENDING_PEER=等对方确认，PENDING_ADMIN=等科长审批，
 * APPROVED/REJECTED 是科长审批结果，CANCELLED 是申请人自己撤销。</p>
 */
public enum SwapStatus {
    PENDING_PEER,
    PENDING_ADMIN,
    APPROVED,
    REJECTED,
    CANCELLED
}
