package com.ptit.lanexam.server.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExamResultsCsvTest {
    @Test void absentScoreAndSubmissionTimeAreBlankWhileRealZeroIsExported() {
        String csv = ExamResultsCsv.format(List.of(
                new ExamResultRow(7L, "ZERO", "An", "Đề", 0.0, 0, 20, LocalDateTime.of(2026, 10, 29, 8, 30)),
                new ExamResultRow(7L, "ABSENT", "Bình", "Đề", null, null, 20, null, 1L, "101", ExamResultRow.Status.ABSENT)));
        assertTrue(csv.contains(",0.00,0,20,"));
        String[] absent = csv.lines().skip(2).findFirst().orElseThrow().split(",", -1);
        assertEquals("", absent[4]); assertEquals("", absent[5]); assertEquals("\"\"", absent[7]);
        assertEquals("\"Vắng thi\"", absent[9]);
        assertFalse(csv.contains("null"));
    }
    @Test void exportsUtf8QuotedFieldsAndEscapesSpreadsheetFormulas() {
        String csv = ExamResultsCsv.format(List.of(new ExamResultRow(7L, "SV01", "Nguyễn, \"An\"", "=HYPERLINK(\"x\")", 8.5, 17, 20,
                LocalDateTime.of(2026, 10, 29, 8, 30))));
        assertTrue(csv.startsWith("\uFEFFca_thi,"));
        assertTrue(csv.contains("\"Nguyễn, \"\"An\"\"\""));
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"x\"\")\""));
        assertTrue(csv.contains(",8.50,17,20,"));
        assertEquals(2, csv.lines().count());
    }
}
