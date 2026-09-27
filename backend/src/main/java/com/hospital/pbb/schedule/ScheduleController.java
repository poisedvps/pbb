package com.hospital.pbb.schedule;

import com.hospital.pbb.common.ApiResponse;
import com.hospital.pbb.schedule.dto.CellVO;
import com.hospital.pbb.schedule.dto.GenerateResultVO;
import com.hospital.pbb.schedule.dto.MonthScheduleVO;
import com.hospital.pbb.schedule.dto.PublishResultVO;
import com.hospital.pbb.schedule.dto.UpdateEntryRequest;
import com.hospital.pbb.user.AuthUser;
import com.hospital.pbb.user.Role;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 排班接口。
 *
 * <p>类上不加 {@code @PreAuthorize}：排班表、大屏都要读这张表，登录即可（含大屏账号）；
 * 角色差别不在能不能读，而在读哪一份——科长读草稿，其余读已发布快照。
 * 写入（按规则生成、改格子）只有科长可以，逐个方法标注。</p>
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleQueryService queryService;
    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleQueryService queryService, ScheduleService scheduleService) {
        this.queryService = queryService;
        this.scheduleService = scheduleService;
    }

    /** 月视图，{@code yearMonth} 形如 {@code 2026-10}，格式不对返回 code=1500 */
    @GetMapping("/{yearMonth}")
    public ApiResponse<MonthScheduleVO> month(@PathVariable String yearMonth, @AuthenticationPrincipal AuthUser me) {
        boolean draft = me.role() == Role.ADMIN;
        return ApiResponse.ok(queryService.getMonth(yearMonth, draft));
    }

    /** 按规则生成整月默认班次，科长手工改过的格子保留原样 */
    @PostMapping("/{yearMonth}/generate")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<GenerateResultVO> generate(@PathVariable String yearMonth, @AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(scheduleService.generate(yearMonth, me.id()));
    }

    /** 改一个单元格，请求体里的 {@code shiftCode} 传 null 表示恢复规则默认 */
    @PutMapping("/{yearMonth}/entries")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CellVO> updateEntry(@PathVariable String yearMonth, @Valid @RequestBody UpdateEntryRequest req,
                                           @AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(scheduleService.updateEntry(yearMonth, req, me.id()));
    }

    /** 发布整月排班：把当前草稿整体复制成已发布快照，成员与大屏随之看到新版本 */
    @PostMapping("/{yearMonth}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PublishResultVO> publish(@PathVariable String yearMonth, @AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(scheduleService.publish(yearMonth, me.id()));
    }
}
