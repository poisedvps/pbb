package com.hospital.pbb.staff;

import com.hospital.pbb.staff.dto.StaffImportParseResult;
import com.hospital.pbb.staff.dto.StaffImportRow;
import com.hospital.pbb.user.Role;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把批量导入的 xlsx 解析成 {@link StaffImportRow} 并做全部“不查库”的校验（设计 §9.5）。
 *
 * <p>纯函数：不碰数据库、不写日志，任何一行有错都把错误逐条放进 {@code errors} 返回，
 * 由 {@code StaffService}（M5-12）决定整批不导入。错误信息里只出现工号和行号，
 * 不出现手机号。</p>
 */
public final class StaffImportParser {

    /** 导入文件的固定表头，与【批量导出】完全一致（设计 §9.3）。 */
    public static final List<String> HEADERS = List.of("工号", "姓名", "岗位", "联系电话", "参与排班", "角色");

    /** 一次导入的人数上限。 */
    public static final int MAX_ROWS = 500;

    private static final int COLS = 6;
    private static final int MAX_TEXT_LEN = 32;
    private static final Pattern EMP_NO = Pattern.compile("^[A-Za-z0-9_]{3,32}$");

    private static final String ERR_UNREADABLE = "文件无法读取，请使用【批量导出】得到的 xlsx 文件";
    private static final String ERR_HEADER = "表头应为：工号、姓名、岗位、联系电话、参与排班、角色";
    private static final String ERR_EMPTY = "文件里没有人员数据";
    private static final String ERR_TOO_MANY = "一次最多导入 500 人";

    private StaffImportParser() {
    }

    /**
     * 解析并校验导入文件。
     *
     * @param in xlsx 输入流，调用方负责关闭
     * @return {@code errors} 非空时 {@code rows} 不可用
     */
    public static StaffImportParseResult parse(InputStream in) {
        try (Workbook workbook = WorkbookFactory.create(in)) {
            return parseWorkbook(workbook);
        } catch (Exception e) {
            // 不打印 e：文件内容可能含手机号，且错误信息只给固定文案
            return new StaffImportParseResult(List.of(), List.of(ERR_UNREADABLE));
        }
    }

    private static StaffImportParseResult parseWorkbook(Workbook workbook) {
        DataFormatter formatter = new DataFormatter();
        Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);

        List<String> errors = new ArrayList<>();
        for (int col = 0; col < COLS; col++) {
            if (!HEADERS.get(col).equals(text(formatter, sheet == null ? null : sheet.getRow(0), col))) {
                return new StaffImportParseResult(List.of(), List.of(ERR_HEADER));
            }
        }

        List<StaffImportRow> rows = new ArrayList<>();
        Map<String, Integer> firstRowOfEmpNo = new HashMap<>();
        int lastRow = sheet == null ? 0 : sheet.getLastRowNum();
        for (int r = 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            String[] cell = new String[COLS];
            boolean blank = true;
            for (int col = 0; col < COLS; col++) {
                cell[col] = text(formatter, row, col);
                blank = blank && cell[col].isEmpty();
            }
            if (blank) {
                continue; // 完全空行不计人数
            }
            rows.add(parseRow(r + 1, cell, firstRowOfEmpNo, errors));
        }

        if (rows.isEmpty()) {
            errors.add(ERR_EMPTY);
        } else if (rows.size() > MAX_ROWS) {
            errors.add(ERR_TOO_MANY);
        }
        return new StaffImportParseResult(List.copyOf(rows), List.copyOf(errors));
    }

    /** 校验一行（rowNo 为 Excel 行号），同一行可以有多条错误，每条加上行号后 append 到 errors。 */
    private static StaffImportRow parseRow(int rowNo, String[] cell, Map<String, Integer> seenEmpNo,
                                           List<String> errors) {
        String empNo = cell[0];
        String name = cell[1];
        String position = cell[2].isEmpty() ? null : cell[2];
        String phone = cell[3].isEmpty() ? null : cell[3];

        List<String> rowErrors = new ArrayList<>();
        if (!EMP_NO.matcher(empNo).matches()) {
            rowErrors.add("工号应为 3-32 位字母、数字或下划线");
        }
        if (name.isEmpty()) {
            rowErrors.add("姓名不能为空");
        } else if (tooLong(name)) {
            rowErrors.add("姓名不能超过 32 个字");
        }
        if (position != null && tooLong(position)) {
            rowErrors.add("岗位不能超过 32 个字");
        }
        if (phone != null && tooLong(phone)) {
            rowErrors.add("联系电话不能超过 32 个字");
        }

        boolean schedulable;
        if (cell[4].isEmpty() || "是".equals(cell[4])) {
            schedulable = true;
        } else if ("否".equals(cell[4])) {
            schedulable = false;
        } else {
            schedulable = true; // 有错时 rows 不可用，这里只给个确定值
            rowErrors.add("参与排班只能填“是”或“否”");
        }

        Role role;
        if (cell[5].isEmpty()) {
            role = null; // 空＝新人员默认成员、已有人员不改角色
        } else if ("科长".equals(cell[5])) {
            role = Role.ADMIN;
        } else if ("成员".equals(cell[5])) {
            role = Role.MEMBER;
        } else {
            role = null; // 有错时 rows 不可用，这里只给个确定值
            rowErrors.add("角色只能填“科长”或“成员”");
        }

        // 工号本身合法时才查重，否则这一行已经报工号格式错误，不必再重复报一条
        if (EMP_NO.matcher(empNo).matches()) {
            Integer first = seenEmpNo.putIfAbsent(empNo, rowNo);
            if (first != null) {
                rowErrors.add("工号 " + empNo + " 与第" + first + "行重复");
            }
        }

        for (String message : rowErrors) {
            errors.add("第" + rowNo + "行：" + message);
        }
        return new StaffImportRow(rowNo, empNo, name, position, phone, schedulable, role);
    }

    /** 取单元格文本并去首尾空格；单元格不存在或为空都是空串。数字（工号 1001、手机号）走 DataFormatter，不会变科学计数法。 */
    private static String text(DataFormatter formatter, Row row, int col) {
        if (row == null) {
            return "";
        }
        Cell cell = row.getCell(col);
        if (cell == null) {
            return "";
        }
        String value = formatter.formatCellValue(cell);
        return value == null ? "" : value.trim();
    }

    private static boolean tooLong(String value) {
        return value.codePointCount(0, value.length()) > MAX_TEXT_LEN;
    }
}
