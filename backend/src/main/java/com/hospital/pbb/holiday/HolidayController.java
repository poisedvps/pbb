package com.hospital.pbb.holiday;

import com.hospital.pbb.common.ApiResponse;
import com.hospital.pbb.holiday.dto.HolidayRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 节假日接口。
 *
 * <p>查询是排班表和大屏都要用的只读数据，登录即可（含大屏账号）；
 * 写入只有科长可以，类上不统一加 {@code @PreAuthorize}，逐个方法标注。</p>
 */
@RestController
@RequestMapping("/api/holidays")
public class HolidayController {

    private final HolidayService holidayService;

    public HolidayController(HolidayService holidayService) {
        this.holidayService = holidayService;
    }

    @GetMapping("")
    public ApiResponse<List<Holiday>> list(@RequestParam int year) {
        return ApiResponse.ok(holidayService.list(year));
    }

    @PostMapping("")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Holiday> create(@Valid @RequestBody HolidayRequest req) {
        return ApiResponse.ok(holidayService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Holiday> update(@PathVariable Long id, @Valid @RequestBody HolidayRequest req) {
        return ApiResponse.ok(holidayService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        holidayService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/copy")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Integer> copy(@RequestParam int fromYear, @RequestParam int toYear) {
        return ApiResponse.ok(holidayService.copy(fromYear, toYear));
    }
}
