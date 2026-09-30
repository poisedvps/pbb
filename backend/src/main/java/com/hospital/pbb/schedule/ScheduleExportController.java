package com.hospital.pbb.schedule;

import com.hospital.pbb.common.ExcelWriter;
import com.hospital.pbb.schedule.dto.CellVO;
import com.hospital.pbb.schedule.dto.DayVO;
import com.hospital.pbb.schedule.dto.MonthScheduleVO;
import com.hospital.pbb.schedule.dto.StaffRowVO;
import com.hospital.pbb.shift.ShiftType;
import com.hospital.pbb.shift.ShiftTypeRepository;
import com.hospital.pbb.user.AuthUser;
import com.hospital.pbb.user.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 排班表导出 xlsx（设计 §5 {@code GET /schedules/{yearMonth}/export}）。
 *
 * <p>单独一个类而不是塞进 {@link ScheduleController}：它的返回体是文件流而不是
 * {@code ApiResponse}，和类里其他接口的处理方式完全不同。</p>
 *
 * <p>读哪一份数据与月视图接口完全一致——科长导出草稿，其余角色导出已发布快照，
 * 这样"屏幕上看到的"和"下载下来的"永远是一张表；格式不对的月份同样由
 * {@link ScheduleMonths#parse} 抛 code=1500。</p>
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleExportController {

    /** weekday（1=周一 … 7=周日）→ 中文，导出表头用"10-01 周四"这种形式 */
    private static final String[] WEEKDAY_CN = {"一", "二", "三", "四", "五", "六", "日"};
    private static final DateTimeFormatter DAY_HEADER = DateTimeFormatter.ofPattern("MM-dd");
    /** 一个月份只有一列一人一格，工作表名固定，不带月份，避免"排班表-2026-10"这种又长又重复的名字 */
    private static final String SHEET_NAME = "排班表";

    private final ScheduleQueryService query;
    private final ShiftTypeRepository shiftRepo;

    public ScheduleExportController(ScheduleQueryService query, ShiftTypeRepository shiftRepo) {
        this.query = query;
        this.shiftRepo = shiftRepo;
    }

    /** 导出整月排班表，登录即可（含大屏账号），角色只决定导出草稿还是已发布。 */
    @GetMapping("/{yearMonth}/export")
    public ResponseEntity<byte[]> export(@PathVariable String yearMonth, @AuthenticationPrincipal AuthUser me) {
        MonthScheduleVO month = query.getMonth(yearMonth, me.role() == Role.ADMIN);
        Map<String, String> shiftNames = shiftNames();

        List<String> headers = new ArrayList<>(month.days().size() + 1);
        headers.add("姓名");
        for (DayVO day : month.days()) {
            headers.add(dayHeader(day));
        }

        List<List<Object>> rows = new ArrayList<>(month.rows().size());
        for (StaffRowVO staff : month.rows()) {
            List<Object> row = new ArrayList<>(headers.size());
            row.add(staff.name());
            for (DayVO day : month.days()) {
                row.add(shiftNameOf(staff, day, shiftNames));
            }
            rows.add(row);
        }

        return ExcelWriter.response(ExcelWriter.write(SHEET_NAME, headers, rows),
                SHEET_NAME + "-" + yearMonth + ".xlsx");
    }

    /** code → 名称：格子里存的是代号，表上给人看的是名称。 */
    private Map<String, String> shiftNames() {
        Map<String, String> names = new HashMap<>();
        for (ShiftType shift : shiftRepo.findAll()) {
            names.put(shift.getCode(), shift.getName());
        }
        return names;
    }

    private static String dayHeader(DayVO day) {
        int weekday = day.weekday();
        String cn = weekday >= 1 && weekday <= 7 ? WEEKDAY_CN[weekday - 1] : String.valueOf(weekday);
        return day.date().format(DAY_HEADER) + " 周" + cn;
    }

    /** 没排班的日期、以及代号已被删掉查不到名称的格子，都导出成空单元格。 */
    private static String shiftNameOf(StaffRowVO staff, DayVO day, Map<String, String> shiftNames) {
        CellVO cell = staff.cells().get(day.date().toString());
        return cell == null ? null : shiftNames.get(cell.shiftCode());
    }
}
