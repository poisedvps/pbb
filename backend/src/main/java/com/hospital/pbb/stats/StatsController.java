package com.hospital.pbb.stats;

import com.hospital.pbb.common.ApiResponse;
import com.hospital.pbb.common.ExcelWriter;
import com.hospital.pbb.stats.dto.StatsVO;
import com.hospital.pbb.user.AuthUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 统计报表接口（任务单 M3-06）。
 *
 * <p>大屏账号只负责轮播排班，不给看全科室的工时统计，所以限 ADMIN、MEMBER；
 * MEMBER 只看自己那一行，是谁由登录态决定，不接受前端传 {@code staffId}。</p>
 */
@RestController
@RequestMapping("/api/stats")
@PreAuthorize("hasAnyRole('ADMIN','MEMBER')")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** 区间统计，{@code from}、{@code to} 形如 {@code 2026-10-01}，含首尾两天 */
    @GetMapping("")
    public ApiResponse<StatsVO> stats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(statsService.stats(from, to, me));
    }

    /**
     * 导出统计报表 xlsx（任务单 M3-08），权限、参数校验、行内容完全走 {@code GET /api/stats}：
     * 先算同样的 StatsVO 再摊平成行，避免“屏幕上看到的”和“下载下来的”是两个口径。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthUser me) {
        String name = "统计";
        StatsVO vo = statsService.stats(from, to, me);
        return ExcelWriter.response(ExcelWriter.write(name, statsService.exportHeaders(), statsService.exportRows(vo)),
                name + "-" + from + "至" + to + ".xlsx");
    }
}
