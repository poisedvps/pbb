package com.hospital.pbb.user;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordUtilTest {

    @Test
    void isStrongAcceptsLetterAndDigit() {
        assertTrue(PasswordUtil.isStrong("abc12345"));
    }

    @Test
    void isStrongRejectsLettersOnly() {
        assertFalse(PasswordUtil.isStrong("abcdefgh"));
    }

    @Test
    void isStrongRejectsDigitsOnly() {
        assertFalse(PasswordUtil.isStrong("12345678"));
    }

    @Test
    void isStrongRejectsTooShort() {
        assertFalse(PasswordUtil.isStrong("ab1"));
    }

    @Test
    void isStrongRejectsSpace() {
        assertFalse(PasswordUtil.isStrong("abc 12345"));
    }

    @Test
    void isStrongRejectsNull() {
        assertFalse(PasswordUtil.isStrong(null));
    }

    @Test
    void randomTempPasswordIsAlwaysStrongAndLength10() {
        for (int i = 0; i < 100; i++) {
            String pw = PasswordUtil.randomTempPassword();
            assertEquals(10, pw.length(), "第 " + i + " 次生成长度应为 10");
            assertTrue(PasswordUtil.isStrong(pw), "第 " + i + " 次生成应满足强度要求");
        }
    }
}
