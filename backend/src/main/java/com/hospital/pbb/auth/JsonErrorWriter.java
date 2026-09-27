package com.hospital.pbb.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.pbb.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 过滤器里手写 JSON 错误响应，格式与统一返回体一致。 */
@Component
public class JsonErrorWriter {

    private final ObjectMapper objectMapper;

    public JsonErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 写出 HTTP status + {"code":code,"message":message,"data":null}，Content-Type: application/json;charset=UTF-8 */
    public void write(HttpServletResponse resp, int httpStatus, int code, String message) throws IOException {
        resp.setStatus(httpStatus);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(code, message)));
        resp.getWriter().flush();
    }
}
