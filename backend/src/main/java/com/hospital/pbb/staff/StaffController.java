package com.hospital.pbb.staff;

import com.hospital.pbb.common.ApiResponse;
import com.hospital.pbb.common.ExcelWriter;
import com.hospital.pbb.staff.dto.CreateStaffRequest;
import com.hospital.pbb.staff.dto.CreateStaffResult;
import com.hospital.pbb.staff.dto.StaffVO;
import com.hospital.pbb.staff.dto.UpdateStaffRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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

/** 人员管理只有科长（ADMIN）能进，临时密码只在 create 的响应里出现一次。 */
@RestController
@RequestMapping("/api/staff")
@PreAuthorize("hasRole('ADMIN')")
public class StaffController {

    private final StaffService staffService;

    public StaffController(StaffService staffService) {
        this.staffService = staffService;
    }

    @GetMapping
    public ApiResponse<List<StaffVO>> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.ok(staffService.list(includeInactive));
    }

    @PostMapping
    public ApiResponse<CreateStaffResult> create(@Valid @RequestBody CreateStaffRequest req) {
        return ApiResponse.ok(staffService.create(req));
    }

    /** 字面量路径优先于 /{id}，排序接口不会被这里吃掉 */
    @PutMapping("/{id}")
    public ApiResponse<StaffVO> update(@PathVariable Long id, @Valid @RequestBody UpdateStaffRequest req) {
        return ApiResponse.ok(staffService.update(id, req));
    }

    @PutMapping("/order")
    public ApiResponse<Void> saveOrder(@RequestBody List<Long> ids) {
        staffService.saveOrder(ids);
        return ApiResponse.ok(null);
    }

    /**
     * 导出人员名单 xlsx（设计 §9.1 第 3 条），文件格式同时是批量导入的模板。
     *
     * <p>字面量路径优先于 {@code /{id}}，与排序接口同理，不会被其他映射吃掉。</p>
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        return ExcelWriter.response(ExcelWriter.write("人员名单", StaffImportParser.HEADERS, staffService.exportRows()),
                "人员名单.xlsx");
    }

    /** 硬删除人员及其排班、调班、日志；科长（1203）删不了 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        staffService.delete(id);
        return ApiResponse.ok(null);
    }
}
