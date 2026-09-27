package com.hospital.pbb.user;

import java.security.SecureRandom;

public final class PasswordUtil {

    private PasswordUtil() {
    }

    /** 8~64 位可见 ASCII 字符（不含空格），至少 1 个字母和 1 个数字 */
    public static final String STRONG_REGEX = "^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7E]{8,64}$";

    public static boolean isStrong(String password) {
        return password != null && password.matches(STRONG_REGEX);
    }

    private static final String CHARSET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String randomTempPassword() {
        while (true) {
            StringBuilder sb = new StringBuilder(10);
            for (int i = 0; i < 10; i++) {
                sb.append(CHARSET.charAt(RANDOM.nextInt(CHARSET.length())));
            }
            String candidate = sb.toString();
            if (isStrong(candidate)) {
                return candidate;
            }
        }
    }
}
