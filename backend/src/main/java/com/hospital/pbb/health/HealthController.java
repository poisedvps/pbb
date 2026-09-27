package com.hospital.pbb.health;

import com.hospital.pbb.common.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("app", "ok");
        try {
            Integer shiftCount = jdbc.queryForObject("select count(*) from shift_type", Integer.class);
            String dbVersion = jdbc.queryForObject("select max(version) from flyway_schema_history where success", String.class);
            data.put("db", "ok");
            data.put("schemaVersion", dbVersion);
            data.put("shiftTypes", shiftCount);
        } catch (Exception e) {
            data.put("db", "down");
        }
        return ApiResponse.ok(data);
    }
}
