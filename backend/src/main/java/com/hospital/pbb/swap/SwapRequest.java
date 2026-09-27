package com.hospital.pbb.swap;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 一条调班申请（设计 §4 swap_request）。
 *
 * <p>申请人 {@code applicantStaffId} 的 {@code applicantDate} 那天想换成 {@code type}：
 * SWAP 时用 {@code targetStaffId} 的 {@code targetDate} 互换，COVER 只要对方替我上，
 * LEAVE 不需要对方（{@code targetStaffId}、{@code targetDate} 为空）。</p>
 */
@Entity
@Table(name = "swap_request")
public class SwapRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 申请类型，取值见 swap_request 表的 CHECK 约束 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SwapType type;

    @Column(name = "applicant_staff_id", nullable = false)
    private Long applicantStaffId;

    @Column(name = "applicant_date", nullable = false)
    private LocalDate applicantDate;

    /** 对方人员，LEAVE 类型可为空 */
    @Column(name = "target_staff_id")
    private Long targetStaffId;

    /** 对方的那一天，LEAVE 类型可为空 */
    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(length = 200)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SwapStatus status;

    /** 对方确认的时间 */
    @Column(name = "peer_confirmed_at")
    private OffsetDateTime peerConfirmedAt;

    /** 审批人（app_user.id） */
    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "review_comment", length = 200)
    private String reviewComment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public SwapType getType() {
        return type;
    }

    public void setType(SwapType type) {
        this.type = type;
    }

    public Long getApplicantStaffId() {
        return applicantStaffId;
    }

    public void setApplicantStaffId(Long applicantStaffId) {
        this.applicantStaffId = applicantStaffId;
    }

    public LocalDate getApplicantDate() {
        return applicantDate;
    }

    public void setApplicantDate(LocalDate applicantDate) {
        this.applicantDate = applicantDate;
    }

    public Long getTargetStaffId() {
        return targetStaffId;
    }

    public void setTargetStaffId(Long targetStaffId) {
        this.targetStaffId = targetStaffId;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public void setTargetDate(LocalDate targetDate) {
        this.targetDate = targetDate;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public SwapStatus getStatus() {
        return status;
    }

    public void setStatus(SwapStatus status) {
        this.status = status;
    }

    public OffsetDateTime getPeerConfirmedAt() {
        return peerConfirmedAt;
    }

    public void setPeerConfirmedAt(OffsetDateTime peerConfirmedAt) {
        this.peerConfirmedAt = peerConfirmedAt;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(Long reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public OffsetDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(OffsetDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public void setReviewComment(String reviewComment) {
        this.reviewComment = reviewComment;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
