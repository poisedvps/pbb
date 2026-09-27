package com.hospital.pbb.common;

/**
 * 业务异常：由 GlobalExceptionHandler 转为 HTTP 200 + ApiResponse(code, message)。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
