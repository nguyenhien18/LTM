package com.ptit.lanexam.server.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;

/** UTF-8 CSV with RFC-style quoting; a whole import is validated before any write. */
public final class RosterCsv {
    private RosterCsv() {}
    public static final int MAX_ROWS = 2000;
    private static final DateTimeFormatter VIETNAMESE_DATE = DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT);
    public record StudentRow(int line, String code, String fullName, LocalDate birthDate, String regularClass, Integer ordinal) {
        public StudentRow(int line, String code, String fullName, LocalDate birthDate) { this(line, code, fullName, birthDate, null, null); }
        public StudentRow(int line, String code, String fullName, LocalDate birthDate, String regularClass) { this(line, code, fullName, birthDate, regularClass, null); }
    }
    public static String displayDate(LocalDate date) { return date.format(DateTimeFormatter.ofPattern("dd/MM/uuuu")); }
    public static String sample() {
        return "\uFEFFSTT,MSV,Họ và tên,Ngày sinh,Lớp\r\n"
                + "1,B23DCCN011,Bùi Thế Anh,07/11/2005,D23CNPM05\r\n"
                + "2,B23DCCN019,Hoàng Đức Anh,17/03/2005,D23CNPM03\r\n";
    }
    public static List<StudentRow> read(Path path) throws IOException {
        if (Files.size(path) > 2 * 1024 * 1024) throw new IllegalArgumentException("CSV tối đa 2 MB.");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(2 * 1024 * 1024 + 1); }
        if (bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("CSV tối đa 2 MB.");
        String content;
        try { content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException ex) { throw new IllegalArgumentException("Lưu tệp ở định dạng CSV UTF-8 để giữ dấu tiếng Việt."); }
        return parse(content);
    }

    public static List<StudentRow> parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("CSV trống.");
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> records = records(text, delimiter(text));
        List<String> header = records.isEmpty() ? List.of() : records.getFirst().stream().map(RosterCsv::headerKey).toList();
        boolean legacy = header.equals(List.of("studentcode", "fullname", "birthdate"));
        boolean schoolRoster = header.size() == 5 && Set.of("stt", "sothutu").contains(header.get(0))
                && Set.of("msv", "masinhvien", "studentcode").contains(header.get(1))
                && Set.of("hovaten", "hoten", "fullname").contains(header.get(2))
                && Set.of("ngaysinh", "birthdate").contains(header.get(3))
                && Set.of("lop", "lopchinhquy", "regularclass").contains(header.get(4));
        if (!legacy && !schoolRoster)
            throw new IllegalArgumentException("Dòng đầu cần 5 cột: STT,MSV,Họ và tên,Ngày sinh,Lớp. Mẫu 3 cột cũ vẫn được hỗ trợ.");
        if (records.size() > MAX_ROWS + 1) throw new IllegalArgumentException("Mỗi lần nhập tối đa 2.000 sinh viên.");
        List<StudentRow> rows = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> cells = records.get(i);
            int line = i + 1;
            int offset = schoolRoster ? 1 : 0;
            if (cells.size() != header.size()) throw new IllegalArgumentException("Dòng dữ liệu " + line + ": cần đúng " + header.size() + " cột.");
            Integer ordinal = null;
            if (schoolRoster && !cells.get(0).isBlank()) {
                try { ordinal = Integer.valueOf(cells.get(0)); if (ordinal <= 0) throw new NumberFormatException(); }
                catch (NumberFormatException ex) { throw new IllegalArgumentException("Dòng dữ liệu " + line + ": STT phải là số nguyên dương hoặc để trống."); }
            }
            String code = cells.get(offset).strip().toUpperCase(Locale.ROOT);
            String name = cells.get(offset + 1).strip().replaceAll("\\s+", " ");
            if (!code.matches("[A-Z0-9][A-Z0-9._-]{0,29}"))
                throw new IllegalArgumentException("Dòng dữ liệu " + line + ": mã sinh viên gồm 1–30 chữ không dấu, số, . _ -");
            if (name.isBlank() || name.length() > 120 || name.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Dòng dữ liệu " + line + ": họ tên từ 1–120 ký tự.");
            LocalDate dob;
            String date = cells.get(offset + 2).strip();
            try { dob = date.contains("/") ? LocalDate.parse(date, VIETNAMESE_DATE) : LocalDate.parse(date); }
            catch (java.time.DateTimeException ex) { throw new IllegalArgumentException("Dòng dữ liệu " + line + ": ngày sinh phải là ngày hợp lệ dạng dd/MM/yyyy (hoặc yyyy-MM-dd)."); }
            if (dob.isAfter(LocalDate.now()) || dob.isBefore(LocalDate.of(1900, 1, 1)))
                throw new IllegalArgumentException("Dòng dữ liệu " + line + ": ngày sinh không hợp lệ.");
            if (!codes.add(code)) throw new IllegalArgumentException("Dòng dữ liệu " + line + ": trùng mã " + code + " trong CSV.");
            String regularClass = schoolRoster ? cells.get(4).strip().toUpperCase(Locale.ROOT) : null;
            if (schoolRoster && (regularClass.isBlank() || regularClass.length() > 80 || regularClass.chars().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException("Dòng dữ liệu " + line + ": lớp chính quy phải có từ 1–80 ký tự.");
            rows.add(new StudentRow(line, code, name, dob, regularClass, ordinal));
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("CSV chưa có sinh viên.");
        return List.copyOf(rows);
    }

    private static String headerKey(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replace('đ', 'd').replaceAll("[^a-z0-9]", "");
    }
    private static char delimiter(String text) {
        int commas = 0, semicolons = 0; boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') quoted = !quoted;
            if (!quoted) { if (c == '\r' || c == '\n') break; if (c == ',') commas++; if (c == ';') semicolons++; }
        }
        return semicolons > commas ? ';' : ',';
    }
    private static List<List<String>> records(String text, char separator) {
        List<List<String>> result = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i <= text.length(); i++) {
            char c = i == text.length() ? '\n' : text.charAt(i);
            if (quoted) {
                if (i == text.length()) throw new IllegalArgumentException("CSV thiếu dấu đóng ngoặc kép.");
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else cell.append(c);
            } else if (c == separator || c == '\r' || c == '\n') {
                row.add(cell.toString().strip()); cell.setLength(0); closed = false;
                if (c != separator) {
                    if (row.size() != 1 || !row.getFirst().isBlank()) result.add(List.copyOf(row));
                    row.clear();
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    if (result.size() > MAX_ROWS + 1) throw new IllegalArgumentException("Mỗi lần nhập tối đa 2.000 sinh viên.");
                }
            } else if (c == '"' && cell.isEmpty() && !closed) quoted = true;
            else {
                if (closed || c == '"') throw new IllegalArgumentException("CSV có dấu ngoặc kép sai vị trí.");
                cell.append(c);
            }
        }
        return result;
    }
}
