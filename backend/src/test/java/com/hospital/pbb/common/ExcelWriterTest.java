package com.hospital.pbb.common;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * xlsx 导出工具（任务单 M3-07 验收标准）：写出去的文件要能用 POI 原样读回来，
 * 所以断言全部落在"重新打开这份字节"上，而不是去猜内部结构。
 */
class ExcelWriterTest {

    private static final int COLUMN_WIDTH = 12 * 256;

    @Test
    void writesHeadersAndRowsReadableBack() throws IOException {
        byte[] xlsx = ExcelWriter.write("统计", List.of("姓名", "工时"),
                List.of(List.<Object>of("张三", 8.5)));

        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = book.getSheetAt(0);
            assertEquals("统计", book.getSheetName(0));
            assertEquals("姓名", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("工时", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("张三", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals(8.5, sheet.getRow(1).getCell(1).getNumericCellValue(), 1e-9);
        }
    }

    @Test
    void writesHeaderRowBold() throws IOException {
        byte[] xlsx = ExcelWriter.write("排班表", List.of("工号", "姓名"), List.of());

        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertTrue(isBold(book, 0, 0));
            assertTrue(isBold(book, 0, 1));
        }
    }

    @Test
    void writesNullAsBlankCellAndNumbersAsNumeric() throws IOException {
        // List.of 不允许 null，空班次这一行只能用 Arrays.asList
        byte[] xlsx = ExcelWriter.write("排班表", List.of("工号", "姓名", "10-01 周四", "工时"),
                List.of(Arrays.asList("E001", "张三", null, new BigDecimal("8.5"))));

        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = book.getSheetAt(0);
            assertEquals(CellType.BLANK, sheet.getRow(1).getCell(2).getCellType());
            // BigDecimal 也是 Number，必须写成数值，否则统计列在 Excel 里求和会得 0
            assertEquals(CellType.NUMERIC, sheet.getRow(1).getCell(3).getCellType());
            assertEquals(8.5, sheet.getRow(1).getCell(3).getNumericCellValue(), 1e-9);
        }
    }

    @Test
    void fixesEveryHeaderColumnWidthWithoutAutoSize() throws IOException {
        byte[] xlsx = ExcelWriter.write("排班表", List.of("工号", "姓名", "10-01 周四"),
                List.of(List.<Object>of("E001", "张三", "白班")));

        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = book.getSheetAt(0);
            for (int i = 0; i < 3; i++) {
                assertEquals(COLUMN_WIDTH, sheet.getColumnWidth(i), "第 " + i + " 列宽度应为 12 个字符");
            }
        }
    }

    @Test
    void responseCarriesXlsxTypeAndUtf8Filename() {
        byte[] body = new byte[]{1};
        ResponseEntity<byte[]> response = ExcelWriter.response(body, "排班表-2026-10.xlsx");

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(body, response.getBody());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                String.valueOf(response.getHeaders().getContentType()));
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.contains("filename*=UTF-8''%E6%8E%92"), "实际值：" + disposition);
        assertTrue(disposition.startsWith("attachment;"), "实际值：" + disposition);
        // URLEncoder 把空格编成 +，必须换成 %20 才符合 RFC 5987
        assertTrue(disposition.endsWith(".xlsx"), "实际值：" + disposition);
    }

    @Test
    void responseEscapesSpaceAsPercent20() {
        String disposition = ExcelWriter.response(new byte[]{1}, "a b.xlsx")
                .getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertEquals("attachment; filename*=UTF-8''a%20b.xlsx", disposition);
    }

    private static boolean isBold(Workbook book, int row, int column) {
        Cell cell = book.getSheetAt(0).getRow(row).getCell(column);
        return book.getFontAt(cell.getCellStyle().getFontIndexAsInt()).getBold();
    }
}
