package com.hospital.pbb.holiday;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.holiday.dto.HolidayRequest;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 节假日维护（设计 §5）。
 *
 * <p>一条记录是一段连续日期，且必须落在同一年内：跨年的假期（例如 12-31~01-01）拆成两条录。
 * 同一年内任意两条记录的日期不允许重叠，调休上班日同样参与这个判断。</p>
 */
@Service
public class HolidayService {

    private final HolidayRepository repo;
    private final OpLogService opLog;

    public HolidayService(HolidayRepository repo, OpLogService opLog) {
        this.repo = repo;
        this.opLog = opLog;
    }

    /** 按年列出，按开始日期升序 */
    public List<Holiday> list(int year) {
        return repo.findByYearOrderByStartDateAsc(year);
    }

    /** 新增一条节假日，year 取开始日期的年份 */
    @Transactional
    public Holiday create(HolidayRequest req) {
        validate(req, null);
        Holiday holiday = new Holiday();
        apply(holiday, req);
        repo.save(holiday);
        opLog.record(OpAction.CREATE_HOLIDAY, holiday.getName(), detailOf(holiday));
        return holiday;
    }

    /** 修改一条节假日，日期与自身重合是允许的，所以重叠校验要排除自己 */
    @Transactional
    public Holiday update(Long id, HolidayRequest req) {
        Holiday holiday = load(id);
        validate(req, id);
        apply(holiday, req);
        repo.save(holiday);
        opLog.record(OpAction.UPDATE_HOLIDAY, holiday.getName(), detailOf(holiday));
        return holiday;
    }

    @Transactional
    public void delete(Long id) {
        Holiday holiday = load(id);
        repo.delete(holiday);
        opLog.record(OpAction.DELETE_HOLIDAY, holiday.getName(), detailOf(holiday));
    }

    /**
     * 把 fromYear 的节假日整年复制到 toYear，日期整体平移，复制完仍要按当年国务院安排核对。
     *
     * @return 复制的条数
     */
    @Transactional
    public int copy(int fromYear, int toYear) {
        // 先判目标年再判源年份：目标年已有数据时即使源年份是空的也应回答"目标年已有节假日"
        if (!repo.findByYearOrderByStartDateAsc(toYear).isEmpty()) {
            throw new BizException(1404, toYear + " 年已有节假日，不能复制");
        }
        List<Holiday> source = repo.findByYearOrderByStartDateAsc(fromYear);
        if (source.isEmpty()) {
            throw new BizException(1405, fromYear + " 年没有节假日可复制");
        }

        long delta = (long) toYear - fromYear;
        String remark = "从" + fromYear + "年复制，请按当年放假安排核对日期";
        for (Holiday src : source) {
            Holiday holiday = new Holiday();
            holiday.setYear(toYear);
            holiday.setName(src.getName());
            holiday.setStartDate(src.getStartDate().plusYears(delta));
            holiday.setEndDate(src.getEndDate().plusYears(delta));
            holiday.setType(src.getType());
            holiday.setRemark(remark);
            repo.save(holiday);
        }
        opLog.record(OpAction.COPY_HOLIDAY, toYear + "年",
                "从" + fromYear + "年复制" + source.size() + "条");
        return source.size();
    }

    /**
     * 公共校验：日期顺序、不跨年、与已有记录不重叠。
     *
     * @param selfId 修改时传被修改记录自身的 id（自身不算重叠），新增时传 null
     */
    private void validate(HolidayRequest req, Long selfId) {
        if (req.endDate().isBefore(req.startDate())) {
            throw new BizException(1401, "结束日期不能早于开始日期");
        }
        if (req.startDate().getYear() != req.endDate().getYear()) {
            throw new BizException(1402, "节假日不能跨年，请分两条录入");
        }
        for (Holiday exist : repo.findOverlapping(req.startDate(), req.endDate())) {
            if (selfId != null && selfId.equals(exist.getId())) {
                continue;
            }
            throw new BizException(1403, "与已有节假日「" + exist.getName() + "」日期重叠");
        }
    }

    /** 请求字段覆盖到实体上，year 一律按开始日期重算，不接受前端传来的年份 */
    private static void apply(Holiday holiday, HolidayRequest req) {
        holiday.setYear(req.startDate().getYear());
        holiday.setName(req.name());
        holiday.setStartDate(req.startDate());
        holiday.setEndDate(req.endDate());
        holiday.setType(req.type());
        // 前端清空备注框传的是空串，一律存 null
        holiday.setRemark(req.remark() == null || req.remark().isBlank() ? null : req.remark());
    }

    private Holiday load(Long id) {
        return repo.findById(id).orElseThrow(() -> new BizException(1400, "节假日不存在"));
    }

    /** 操作日志的 detail：只写日期区间和类型，不含任何敏感信息 */
    private static String detailOf(Holiday holiday) {
        return holiday.getStartDate() + "~" + holiday.getEndDate() + " " + holiday.getType();
    }
}
