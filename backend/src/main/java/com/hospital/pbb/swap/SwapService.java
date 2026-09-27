package com.hospital.pbb.swap;

import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.schedule.ScheduleQueryService;
import com.hospital.pbb.schedule.ScheduleService;
import com.hospital.pbb.staff.Staff;
import com.hospital.pbb.staff.StaffRepository;
import com.hospital.pbb.swap.dto.SwapVO;
import com.hospital.pbb.user.AuthUser;
import com.hospital.pbb.user.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调班申请（设计 §5.3，任务单 M3-03 只做列表）。
 *
 * <p>同一条记录在不同人眼里的可见范围不一样：科长看得到全科的，成员只关心
 * “我发起的”和“对方是我”的，所以三个 scope 各自走一条仓库查询，而不是查全量再在内存里过滤。</p>
 */
@Service
public class SwapService {

    /** 单据号前缀，{@code no} = 前缀 + id 左补零到 4 位，只用于人眼识别，唯一性仍以 id 为准 */
    private static final String NO_PREFIX = "TB-";

    private final SwapRequestRepository repo;
    private final StaffRepository staffRepo;
    private final ScheduleQueryService query;
    // 下面三项本单用不到：M3-04 发起、M3-05 确认/撤销/审批要回写排班并留痕，先注入好直接拿来用
    private final ScheduleService schedule;
    private final OpLogService opLog;
    private final Clock clock;

    public SwapService(SwapRequestRepository repo, StaffRepository staffRepo, ScheduleQueryService query,
                       ScheduleService schedule, OpLogService opLog, Clock clock) {
        this.repo = repo;
        this.staffRepo = staffRepo;
        this.query = query;
        this.schedule = schedule;
        this.opLog = opLog;
        this.clock = clock;
    }

    /**
     * 调班列表。
     *
     * @param me    当前登录账号
     * @param scope {@code ALL} 全部 / {@code MINE} 我发起的 / {@code TODO} 待我处理，null 视为 ALL
     * @return 按 id 倒序（新申请在前）；账号没关联人员的成员返回空列表
     */
    @Transactional(readOnly = true)
    public List<SwapVO> list(AuthUser me, String scope) {
        List<SwapRequest> requests = requestsOf(me, scope);
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<Long, Staff> staffById = staffById();
        List<SwapVO> list = new ArrayList<>(requests.size());
        for (SwapRequest request : requests) {
            list.add(toVO(request, me, staffById));
        }
        return list;
    }

    /**
     * 一条记录转 VO：姓名查人员表，班次取已发布快照，按钮标志按当前登录人算。
     * 供本类其它方法（M3-04 发起、M3-05 审批后的返回值）复用。
     */
    SwapVO toVO(SwapRequest r, AuthUser me, Map<Long, Staff> staffById) {
        SwapStatus status = r.getStatus();
        String applicantShift = query.publishedShift(r.getApplicantStaffId(), r.getApplicantDate()).orElse(null);
        return new SwapVO(r.getId(), noOf(r.getId()), r.getType(),
                r.getApplicantStaffId(), nameOf(staffById, r.getApplicantStaffId()),
                r.getApplicantDate(), applicantShift,
                r.getTargetStaffId(), nameOf(staffById, r.getTargetStaffId()),
                r.getTargetDate(), targetShiftOf(r),
                r.getReason(), status, r.getReviewComment(), r.getCreatedAt(),
                status == SwapStatus.PENDING_PEER && sameStaff(me.staffId(), r.getTargetStaffId()),
                (status == SwapStatus.PENDING_PEER || status == SwapStatus.PENDING_ADMIN)
                        && sameStaff(me.staffId(), r.getApplicantStaffId()),
                status == SwapStatus.PENDING_ADMIN && me.role() == Role.ADMIN);
    }

    /**
     * 按角色 + scope 选一条仓库查询。
     *
     * <p>科长是审批人，“待我处理”= 等科长审批（PENDING_ADMIN）；成员的“待我处理”= 等对方确认（PENDING_PEER）
     * 且对方是我自己。</p>
     */
    private List<SwapRequest> requestsOf(AuthUser me, String scope) {
        // 只有这三个取值，前端传别的一律按 ALL 处理，列表页不该因为一个拼错的参数白屏
        String sc = scope == null ? "ALL" : scope;
        if (me.role() == Role.ADMIN) {
            return switch (sc) {
                case "MINE" -> repo.findByApplicantStaffIdOrderByIdDesc(me.staffId());
                case "TODO" -> repo.findByStatusOrderByIdDesc(SwapStatus.PENDING_ADMIN);
                default -> repo.findAllByOrderByIdDesc();
            };
        }
        Long staffId = me.staffId();
        if (staffId == null) {
            // 账号没关联人员，既当不了申请人也当不了对方，一条都不属于他，没必要查库
            return List.of();
        }
        return switch (sc) {
            case "MINE" -> repo.findByApplicantStaffIdOrderByIdDesc(staffId);
            case "TODO" -> repo.findByStatusAndTargetStaffIdOrderByIdDesc(SwapStatus.PENDING_PEER, staffId);
            default -> repo.findByApplicantStaffIdOrTargetStaffIdOrderByIdDesc(staffId, staffId);
        };
    }

    /** 姓名：人员表整表建索引（几十条），列表页每行两次 findById 不值当。 */
    private Map<Long, Staff> staffById() {
        Map<Long, Staff> staffById = new HashMap<>();
        for (Staff staff : staffRepo.findAll()) {
            staffById.put(staff.getId(), staff);
        }
        return staffById;
    }

    /** 对方那天的班次：只有换班才是“两头都要看”，请假、替班列表上不显示对方的班次。 */
    private String targetShiftOf(SwapRequest r) {
        if (r.getType() != SwapType.SWAP || r.getTargetStaffId() == null || r.getTargetDate() == null) {
            return null;
        }
        return query.publishedShift(r.getTargetStaffId(), r.getTargetDate()).orElse(null);
    }

    /** 人员查不到（被删了）时姓名为 null，列表显示空，不能让整页报错。 */
    private static String nameOf(Map<Long, Staff> staffById, Long staffId) {
        Staff staff = staffId == null ? null : staffById.get(staffId);
        return staff == null ? null : staff.getName();
    }

    /** 单据号：TB-0003。 */
    private static String noOf(Long id) {
        return id == null ? null : String.format("%s%04d", NO_PREFIX, id);
    }

    /** staffId 为 null 的账号（科长、大屏）不算和任何人相同，否则 LEAVE 的空 target 会被当成“对方是我”。 */
    private static boolean sameStaff(Long left, Long right) {
        return left != null && left.equals(right);
    }
}
