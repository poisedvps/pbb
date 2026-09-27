package com.hospital.pbb.swap;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
