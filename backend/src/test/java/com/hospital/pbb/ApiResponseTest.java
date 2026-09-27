package com.hospital.pbb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hospital.pbb.common.ApiResponse;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void okHasCodeZero() {
        ApiResponse<String> r = ApiResponse.ok("x");
        assertEquals(0, r.code());
        assertEquals("x", r.data());
    }

    @Test
    void failHasNoData() {
        ApiResponse<Void> r = ApiResponse.fail(401, "未登录");
        assertEquals(401, r.code());
        assertNull(r.data());
    }
}
