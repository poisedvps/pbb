package com.hospital.pbb.shift;

import com.hospital.pbb.common.ApiResponse;
import com.hospital.pbb.shift.dto.UpdateShiftTypeRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 班次只读 + 修改：排班页、大屏都要读班次，所以查询只要求登录；
 * 修改是科长的事，类上不加 @PreAuthorize，只在 PUT 上收权。
 */
@RestController
@RequestMapping("/api/shift-types")
public class ShiftTypeController {

    private final ShiftTypeService shiftTypeService;

    public ShiftTypeController(ShiftTypeService shiftTypeService) {
        this.shiftTypeService = shiftTypeService;
    }

    @GetMapping("")
    public ApiResponse<List<ShiftType>> list() {
        return ApiResponse.ok(shiftTypeService.list());
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ShiftType> update(@PathVariable String code, @Valid @RequestBody UpdateShiftTypeRequest req) {
        return ApiResponse.ok(shiftTypeService.update(code, req));
    }
}
