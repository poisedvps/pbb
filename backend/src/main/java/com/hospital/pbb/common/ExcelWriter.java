package com.hospital.pbb.common;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * xlsx 导出工具（设计 §5 各导出接口共用）。
 *
 * <p>只负责"给一张表 → 返回一个可下载的 xlsx 响应"，不认识排班、统计这些业务，
 * 所以放 common 包，排班表导出（M3-07）和统计导出（M3-08）都调它。</p>
 */
public final class ExcelWriter {

    /** xlsx 的 MIME，浏览器据此判断是下载而不是预览 */
    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    /**
     * 每列固定 12 个字符宽。
     *
     * <p>不用 {@code autoSizeColumn}：它要靠 AWT 字体度量算宽度，容器里没有中文字体会直接抛异常，
     * 导出一份表格不该依赖运行环境装了什么字体。</p>
     */
    private static final int COLUMN_WIDTH_CHARS = 12 * 256;

    private ExcelWriter() {
    }

    /**
     * 生成一份只有一个工作表的 xlsx。
     *
     * @param sheetName 工作表名
     * @param headers   表头，写在第一行并加粗
     * @param rows      数据行，每行的第 i 个值落在第 i 列：null 写空单元格，
     *                  {@link Number} 写数值（Excel 里能求和），其余按 {@code toString()} 写文本
     * @return xlsx 文件字节
     * @throws UncheckedIOException 写内存流失败（理论上不会发生，仍包装成非受检异常，避免污染上层签名）
     */
    public static byte[] write(String sheetName, List<String> headers, List<List<Object>> rows) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet(sheetName);
            writeHeader(book, sheet, headers);
            writeRows(sheet, rows, headers.size());
            for (int i = 0; i < headers.size(); i++) {
                sheet.setColumnWidth(i, COLUMN_WIDTH_CHARS);
            }
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("生成 Excel 失败", e);
        }
    }

    /** 把字节包成下载响应：文件名走 RFC 5987 的 {@code filename*}，中文文件名才不会乱码。 */
    public static ResponseEntity<byte[]> response(byte[] body, String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(body);
    }

    /** 第一行表头，加粗，与工作簿共用一个样式（每个格子新建样式会白白多占几百个样式槽）。 */
    private static void writeHeader(XSSFWorkbook book, Sheet sheet, List<String> headers) {
        Font font = book.createFont();
        font.setBold(true);
        CellStyle bold = book.createCellStyle();
        bold.setFont(font);

        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = row.createCell(i);
            cell.setCellStyle(bold);
            if (headers.get(i) != null) {
                cell.setCellValue(headers.get(i));
            }
        }
    }

    /** 数据行从第二行开始；某一行比表头短就少写几列，多出来的列留空。 */
    private static void writeRows(Sheet sheet, List<List<Object>> rows, int columnCount) {
        int rowIndex = 1;
        for (List<Object> values : rows) {
            Row row = sheet.createRow(rowIndex++);
            for (int i = 0; i < values.size() && i < columnCount; i++) {
                writeCell(row.createCell(i), values.get(i));
            }
        }
    }

    /** null 只留一个空格子（createCell 已经是空的了，不需要再赋值）。 */
    private static void writeCell(Cell cell, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
            return;
        }
        cell.setCellValue(value.toString());
    }
}
