package com.ptit.lanexam.server.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class RosterCsvTest {
    private static final String HEADER = "student_code,full_name,birth_date\r\n";
    @Test void handlesBomVietnameseQuotedCommasAndEscapedQuotes() {
        var rows = RosterCsv.parse("\uFEFF" + HEADER + "sv01,\"Nguyễn Văn An, Nhóm \"\"A\"\"\",2003-05-12\r\n\r\nSV02,Trần Bình,2002-01-01\r\n");
        assertEquals(2, rows.size()); assertEquals("SV01", rows.getFirst().code());
        assertEquals("Nguyễn Văn An, Nhóm \"A\"", rows.getFirst().fullName());
        assertEquals(LocalDate.of(2003, 5, 12), rows.getFirst().birthDate());
    }
    @Test void rejectsDuplicatesInvalidDatesMalformedQuotesAndMissingColumns() {
        for (String csv : new String[]{
                HEADER + "SV01,An,2003-05-12\nsv01,An,2003-05-12",
                HEADER + "SV01,An,2003-02-30",
                HEADER + "SV01,\"An,2003-05-12",
                HEADER + "SV01,A\"n,2003-05-12",
                HEADER + "SV01,An",
                HEADER + "SV 01,An,2003-05-12",
                HEADER, "code,name,date\nSV01,An,2003-05-12"}) {
            assertThrows(IllegalArgumentException.class, () -> RosterCsv.parse(csv), csv);
        }
    }
    @Test void limitsImportSize() {
        assertThrows(IllegalArgumentException.class, () -> RosterCsv.parse(HEADER + "SV01,An,2003-05-12\n".repeat(2001)));
    }
    @Test void readsFiveSchoolColumnsAndInterpretsDatesAsDayMonthYear() {
        var rows = RosterCsv.parse(RosterCsv.sample());
        assertEquals(2, rows.size());
        assertEquals("B23DCCN011", rows.getFirst().code());
        assertEquals("Bùi Thế Anh", rows.getFirst().fullName());
        assertEquals(LocalDate.of(2005, 11, 7), rows.getFirst().birthDate());
        assertEquals("D23CNPM05", rows.getFirst().regularClass());
        assertEquals(1, rows.getFirst().ordinal());
        assertEquals("07/11/2005", RosterCsv.displayDate(rows.getFirst().birthDate()));
    }
    @Test void acceptsVietnameseHeaderAliasesAndExcelSemicolonSeparator() {
        var rows = RosterCsv.parse("\uFEFFSTT;Mã sinh viên;Họ tên;Ngày sinh;Lớp chính quy\r\n;sv001;\"Nguyễn An; Nhóm A\";7/11/2005;d23cnpm05\r\n");
        assertEquals("SV001", rows.getFirst().code());
        assertEquals("Nguyễn An; Nhóm A", rows.getFirst().fullName());
        assertEquals(LocalDate.of(2005, 11, 7), rows.getFirst().birthDate());
        assertEquals("D23CNPM05", rows.getFirst().regularClass()); assertNull(rows.getFirst().ordinal());
        assertEquals(1, RosterCsv.parse("stt,msv,ho va ten,ngay sinh,lop\n1,SV01,An,2005-11-07,D23A").size());
    }
    @Test void rejectsInvalidSchoolRowsWithoutGuessingDatesOrMissingClass() {
        String header = "STT,MSV,Họ và tên,Ngày sinh,Lớp\n";
        for (String row : new String[]{"1,SV01,An,31/02/2005,D23A", "1,SV01,An,29/02/2005,D23A",
                "0,SV01,An,07/11/2005,D23A", "abc,SV01,An,07/11/2005,D23A", "1,SV01,An,07/11/2005,",
                "1,SV01,An,07/11/2005", "1,SV01,An,07/11/2005," + "A".repeat(81)})
            assertThrows(IllegalArgumentException.class, () -> RosterCsv.parse(header + row), row);
    }
}
