package com.hospital.pbb.shift;

import com.hospital.pbb.common.ApiResponse;
import com.hospital.pbb.shift.dto.DutyPhoneColorVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统设置读写（设计 §5）。
 *
 * <p>底色在“班次设置”页展示给所有人看，只有科长能改：GET 只要求登录（类上不加 {@code @PreAuthorize}），
 * PUT 单独收权，和 {@link ShiftTypeController} 的写法保持一致。</p>
 */
@RestController
@RequestMapping("/api/settings")
public class SettingController {

    private final DutyPhoneColorService dutyPhoneColorService;

    public SettingController(DutyPhoneColorService dutyPhoneColorService) {
        this.dutyPhoneColorService = dutyPhoneColorService;
    }

    @GetMapping("/duty-phone-color")
    public ApiResponse<DutyPhoneColorVO> get() {
        return ApiResponse.ok(dutyPhoneColorService.get());
    }

    @PutMapping("/duty-phone-color")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<DutyPhoneColorVO> update(@RequestBody DutyPhoneColorVO req) {
        return ApiResponse.ok(dutyPhoneColorService.update(req.color()));
    }
}
