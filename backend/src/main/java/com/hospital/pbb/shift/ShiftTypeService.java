package com.hospital.pbb.shift;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.shift.dto.UpdateShiftTypeRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * 班次设置（设计 §5）。
 *
 * <p>6 种班次由迁移脚本预置，这里只开放修改：不能新增、不能删除，
 * 因为已有排班数据用 code 引用它们，删掉会让历史单元格变成悬空引用。</p>
 */
@Service
public class ShiftTypeService {

    /** 白班和休息是规则排班每天都要用到的兜底班次，停用会让排班规则算不出来 */
    public static final Set<String> REQUIRED_CODES = Set.of("D", "X");

    private final ShiftTypeRepository shiftRepo;
    private final OpLogService opLog;

    public ShiftTypeService(ShiftTypeRepository shiftRepo, OpLogService opLog) {
        this.shiftRepo = shiftRepo;
        this.opLog = opLog;
    }

    /** 全量返回（含停用班次），大屏和排班页都要能显示历史班次 */
    public List<ShiftType> list() {
        return shiftRepo.findAllByOrderBySortOrderAsc();
    }

    /** 修改班次；code 只用来定位，改名一律由 name 字段决定 */
    @Transactional
    public ShiftType update(String code, UpdateShiftTypeRequest req) {
        ShiftType shift = load(code);
        if (REQUIRED_CODES.contains(code) && !req.enabled()) {
            throw new BizException(1301, "白班和休息为规则排班所需，不能停用");
        }
        LocalTime start = req.startTime();
        LocalTime end = req.endTime();
        if ((start == null) != (end == null)) {
            throw new BizException(1302, "上下班时间需同时填写或同时为空");
        }
        // 跨天班次的下班时间本来就早于上班时间（夜班 17:30~08:00），只有不跨天时才要求递增
        if (!req.crossDay() && start != null && end != null && !end.isAfter(start)) {
            throw new BizException(1303, "非跨天班次下班时间须晚于上班时间");
        }

        String detail = shift.getName() + "→" + req.name() + ", "
                + text(shift.getWorkHours()) + "→" + text(req.workHours());
        shift.setName(req.name());
        shift.setStartTime(start);
        shift.setEndTime(end);
        shift.setCrossDay(req.crossDay());
        shift.setWorkHours(req.workHours());
        shift.setCountsAsWork(req.countsAsWork());
        shift.setColor(req.color());
        shift.setEnabled(req.enabled());
        shiftRepo.save(shift);

        // detail 只写名称和工时，颜色一类前端展示项不进操作日志
        opLog.record(OpAction.UPDATE_SHIFT, code, detail);
        return shift;
    }

    private ShiftType load(String code) {
        return shiftRepo.findById(code).orElseThrow(() -> new BizException(1300, "班次不存在"));
    }

    /** 操作日志里的工时按原样输出，14.5 不写成 14.50 */
    private static String text(BigDecimal workHours) {
        return String.valueOf(workHours);
    }
}
