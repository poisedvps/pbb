package com.hospital.pbb.screen;

import com.hospital.pbb.common.ApiResponse;
import com.hospital.pbb.screen.dto.ScreenVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 大屏数据接口（任务单 M2-07）。
 *
 * <p>大屏页全屏挂着不给别人看编辑中的排班，所以只有大屏账号和科长能调，成员请走"我的排班"。</p>
 */
@RestController
@RequestMapping("/api/screen")
@PreAuthorize("hasAnyRole('SCREEN','ADMIN')")
public class ScreenController {

    private final ScreenService screenService;

    public ScreenController(ScreenService screenService) {
        this.screenService = screenService;
    }

    /** 整月已发布排班 + 今日概况，{@code yearMonth} 形如 {@code 2026-10}，不传取当前月 */
    @GetMapping("")
    public ApiResponse<ScreenVO> screen(@RequestParam(required = false) String yearMonth) {
        return ApiResponse.ok(screenService.get(yearMonth));
    }
}
