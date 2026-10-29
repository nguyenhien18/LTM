package com.ptit.lanexam.server.service;

import java.util.List;
import java.util.Locale;

public final class ExamResultsCsv {
    private ExamResultsCsv() {}
    public static String format(List<ExamResultRow> rows) {
        StringBuilder csv = new StringBuilder("\uFEFFca_thi,ma_sinh_vien,ho_ten,de_thi,diem,so_cau_dung,tong_so_cau,thoi_gian_nop,ma_de\r\n");
        for (ExamResultRow row : rows) csv.append(row.roundId()).append(',').append(cell(row.studentCode())).append(',')
                .append(cell(row.fullName())).append(',').append(cell(row.examTitle())).append(',')
                .append(String.format(Locale.ROOT, "%.2f", row.score())).append(',').append(row.correct()).append(',')
                .append(row.total()).append(',').append(cell(row.submittedAt().toString())).append(',').append(cell(row.variantCode())).append("\r\n");
        return csv.toString();
    }
    private static String cell(String value) {
        String text = value == null ? "" : value;
        if (!text.stripLeading().isEmpty() && "=+-@".indexOf(text.stripLeading().charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
