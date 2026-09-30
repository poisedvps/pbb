package com.hospital.pbb.staff;

import com.hospital.pbb.staff.dto.StaffImportParseResult;
import com.hospital.pbb.staff.dto.StaffImportRow;
import com.hospital.pbb.user.Role;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 内存造 xlsx（设计 §9.5）：只验解析与校验，不查库。 */
class StaffImportParserTest {

    /** 一行六个单元格写成 xlsx 再解析。 */
    private static StaffImportParseResult parse(String[]... dataRows) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("人员名单");
            Row header = sheet.createRow(0);
            for (int col = 0; col < StaffImportParser.HEADERS.size(); col++) {
                header.createCell(col).setCellValue(StaffImportParser.HEADERS.get(col));
            }
            int rowNo = 1;
            for (String[] cells : dataRows) {
                Row row = sheet.createRow(rowNo++);
                for (int col = 0; col < cells.length; col++) {
                    row.createCell(col).setCellValue(cells[col]);
                }
            }
            book.write(out);
            return StaffImportParser.parse(new ByteArrayInputStream(out.toByteArray()));
        }
    }

    private static String joined(StaffImportParseResult result) {
        return String.join("|", result.errors());
    }

    @Test
    void parsesTwoValidRows() throws IOException {
        StaffImportParseResult result = parse(
                new String[] {"A01", "张三", "医生", "13800000000", "是", "成员"},
                new String[] {"A02", "李四", "", "", "否", ""});

        assertTrue(result.errors().isEmpty(), joined(result));
        assertEquals(2, result.rows().size());

        StaffImportRow first = result.rows().get(0);
        assertEquals(2, first.rowNo());
        assertEquals("A01", first.empNo());
        assertEquals("张三", first.name());
        assertEquals("医生", first.position());
        assertEquals("13800000000", first.phone());
        assertTrue(first.schedulable());
        assertEquals(Role.MEMBER, first.role());

        StaffImportRow second = result.rows().get(1);
        assertEquals(3, second.rowNo());
        assertNull(second.position());
        assertNull(second.phone());
        assertFalse(second.schedulable());
        assertNull(second.role());
    }

    @Test
    void numericEmpNoBecomesPlainText() throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet();
            Row header = sheet.createRow(0);
            for (int col = 0; col < StaffImportParser.HEADERS.size(); col++) {
                header.createCell(col).setCellValue(StaffImportParser.HEADERS.get(col));
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(1001);
            row.createCell(1).setCellValue("王五");
            row.createCell(3).setCellValue(13800000000d);
            book.write(out);

            StaffImportParseResult result =
                    StaffImportParser.parse(new ByteArrayInputStream(out.toByteArray()));

            assertTrue(result.errors().isEmpty(), joined(result));
            assertEquals("1001", result.rows().get(0).empNo());
            assertEquals("13800000000", result.rows().get(0).phone());
            assertTrue(result.rows().get(0).schedulable());
        }
    }

    @Test
    void rejectsWrongHeader() throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet();
            Row header = sheet.createRow(0);
            List<String> headers = List.of("工号", "姓名", "职务", "联系电话", "参与排班", "角色");
            for (int col = 0; col < headers.size(); col++) {
                header.createCell(col).setCellValue(headers.get(col));
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("A01");
            row.createCell(1).setCellValue("张三");
            book.write(out);

            StaffImportParseResult result =
                    StaffImportParser.parse(new ByteArrayInputStream(out.toByteArray()));

            assertEquals(List.of("表头应为：工号、姓名、岗位、联系电话、参与排班、角色"), result.errors());
        }
    }

    @Test
    void rejectsEmptyName() throws IOException {
        StaffImportParseResult result = parse(new String[] {"A01", "", "医生", "", "是", "成员"});

        assertTrue(result.errors().contains("第2行：姓名不能为空"), joined(result));
    }

    @Test
    void rejectsDuplicateEmpNoInFile() throws IOException {
        StaffImportParseResult result = parse(
                new String[] {"A01", "张三", "", "", "", ""},
                new String[] {"A02", "李四", "", "", "", ""},
                new String[] {"A01", "王五", "", "", "", ""});

        assertTrue(result.errors().contains("第4行：工号 A01 与第2行重复"), joined(result));
    }

    @Test
    void rejectsBadSchedulableAndTooLongTexts() throws IOException {
        StaffImportParseResult result = parse(
                new String[] {"A01", "张三", "医生", "13800000000", "Y", "成员"},
                new String[] {"A02", "名".repeat(33), "岗".repeat(33), "1".repeat(33), "是", "成员"});

        assertTrue(result.errors().contains("第2行：参与排班只能填“是”或“否”"), joined(result));
        assertTrue(result.errors().contains("第3行：姓名不能超过 32 个字"), joined(result));
        assertTrue(result.errors().contains("第3行：岗位不能超过 32 个字"), joined(result));
        assertTrue(result.errors().contains("第3行：联系电话不能超过 32 个字"), joined(result));
    }

    @Test
    void rejectsBadEmpNoAndBadRole() throws IOException {
        StaffImportParseResult result = parse(new String[] {"A1", "张三", "", "", "", "主任"});

        assertTrue(result.errors().contains("第2行：工号应为 3-32 位字母、数字或下划线"), joined(result));
        assertTrue(result.errors().contains("第2行：角色只能填“科长”或“成员”"), joined(result));
    }

    @Test
    void skipsFullyEmptyRows() throws IOException {
        StaffImportParseResult result = parse(
                new String[] {"", "", "", "", "", ""},
                new String[] {"A01", "张三", "", "", "", "科长"},
                new String[] {"", "", "", "", "", ""});

        assertTrue(result.errors().isEmpty(), joined(result));
        assertEquals(1, result.rows().size());
        assertEquals(Role.ADMIN, result.rows().get(0).role());
    }

    @Test
    void rejectsFileWithoutAnyStaff() throws IOException {
        StaffImportParseResult result = parse(new String[] {"", "", "", "", "", ""});

        assertEquals(List.of("文件里没有人员数据"), result.errors());
    }

    @Test
    void rejectsMoreThanMaxRows() throws IOException {
        String[][] rows = new String[StaffImportParser.MAX_ROWS + 1][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new String[] {"A" + (1000 + i), "姓名" + i, "", "", "", ""};
        }

        StaffImportParseResult result = parse(rows);

        assertTrue(result.errors().contains("一次最多导入 500 人"), joined(result));
    }

    @Test
    void rejectsNotAnExcelFile() {
        StaffImportParseResult result = StaffImportParser.parse(new ByteArrayInputStream("not excel".getBytes()));

        assertEquals(List.of("文件无法读取，请使用【批量导出】得到的 xlsx 文件"), result.errors());
    }

    @Test
    void errorsNeverContainPhone() throws IOException {
        StaffImportParseResult result = parse(new String[] {"A01", "张三", "医生", "13800000000", "是", "主任"});

        assertFalse(joined(result).contains("13800000000"), joined(result));
        assertFalse(result.errors().isEmpty());
    }
}
