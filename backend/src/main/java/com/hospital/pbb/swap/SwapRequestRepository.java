package com.hospital.pbb.swap;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface SwapRequestRepository extends JpaRepository<SwapRequest, Long> {

    /** 科长看全部列表 */
    List<SwapRequest> findAllByOrderByIdDesc();

    /** 成员看「我申请的 + 对方是我」的列表 */
    List<SwapRequest> findByApplicantStaffIdOrTargetStaffIdOrderByIdDesc(Long applicantStaffId, Long targetStaffId);

    /** 只看我申请的 */
    List<SwapRequest> findByApplicantStaffIdOrderByIdDesc(Long applicantStaffId);

    /** 按状态取，科长审批台用 */
    List<SwapRequest> findByStatusOrderByIdDesc(SwapStatus status);

    /** 按状态取且对方是我，待我确认的用 */
    List<SwapRequest> findByStatusAndTargetStaffIdOrderByIdDesc(SwapStatus status, Long targetStaffId);

    /** 同一天是否已有进行中的申请（防重复发起） */
    boolean existsByApplicantStaffIdAndApplicantDateAndStatusIn(Long staffId, LocalDate date, Collection<SwapStatus> statuses);

    /** 删除人员：删掉此人发起或作为对方的全部申请（§9.4，M5-03） */
    @Modifying
    @Query("delete from SwapRequest r where r.applicantStaffId = :staffId or r.targetStaffId = :staffId")
    int deleteByStaff(@Param("staffId") Long staffId);

    /** 删除账号：审批人字段里的该账号置空，历史单据保留（§9.4，M5-03） */
    @Modifying
    @Query("update SwapRequest r set r.reviewedBy = null where r.reviewedBy = :userId")
    int clearReviewedBy(@Param("userId") Long userId);
}
