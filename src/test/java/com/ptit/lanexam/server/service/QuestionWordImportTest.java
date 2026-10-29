package com.ptit.lanexam.server.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class QuestionWordImportTest {
    @TempDir Path temp;
    static final String VALID = "MỨC 1\nCâu 1: Giao thức tin cậy?\nA. UDP\nB. TCP\nC. IP\nD. ICMP\nĐáp án: B";

    @Test void bundledTemplateContainsSixLevelsAndImportsWithoutErrors() throws Exception {
        var parsed = QuestionWordImport.read(Path.of("src/main/resources/templates/question-bank-template.docx"));
        assertFalse(parsed.hasErrors(), parsed.toString()); assertEquals(6, parsed.items().size());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), parsed.items().stream().map(QuestionWordImport.Item::difficulty).toList());
    }
    @Test void supportsMultipleQuestionsEachLevelAndMultilineContentOrOptions() {
        var result = QuestionWordImport.parseText("Ngân hàng môn Mạng\n" + VALID.replace("Giao thức tin cậy?", "Đọc tình huống:\nMáy A gửi dữ liệu.")
                .replace("B. TCP", "B. TCP\nTruyền tin cậy") + "\nCâu 2: Câu khác\nA) A\nB) B\nC) C\nD) D\nĐáp án: d\nMỨC 6\nCâu 1: Câu khó\nA. A\nB. B\nC. C\nD. D\nĐáp án: A");
        assertFalse(result.hasErrors(), result.toString()); assertEquals(3, result.items().size());
        assertEquals("Đọc tình huống:\nMáy A gửi dữ liệu.", result.items().getFirst().question().content());
        assertEquals("TCP\nTruyền tin cậy", result.items().getFirst().question().optionB());
        assertEquals("D", result.items().get(1).question().correctAnswer());
        assertEquals(6, result.items().getLast().difficulty());
    }
    @Test void acceptsUnaccentedLabelsLowercaseAndDecomposedVietnamese() {
        assertFalse(QuestionWordImport.parseText(VALID.replace("MỨC", "muc").replace("Câu", "cau").replace("Đáp án", "dap an")).hasErrors());
        assertFalse(QuestionWordImport.parseText(Normalizer.normalize(VALID, Normalizer.Form.NFD)).hasErrors());
    }
    @Test void reportsMissingLevelChoicesAndInvalidOrDuplicateAnswerPerQuestion() {
        var missing = QuestionWordImport.parseText(VALID.replace("MỨC 1\n", "").replace("C. IP\n", "").replace("Đáp án: B", "Đáp án: AB"));
        assertTrue(missing.hasErrors()); String errors = missing.items().getFirst().errors().toString();
        assertTrue(errors.contains("MỨC")); assertTrue(errors.contains("lựa chọn C")); assertTrue(errors.contains("đúng một chữ"));
        assertTrue(QuestionWordImport.parseText(VALID + "\nĐáp án: A").hasErrors());
        assertTrue(QuestionWordImport.parseText(VALID.replace("B. TCP", "B. TCP\nB. Khác")).hasErrors());
        assertTrue(QuestionWordImport.parseText(VALID.replace("MỨC 1", "MỨC 7")).hasErrors());
        assertTrue(QuestionWordImport.parseText(VALID.replace("MỨC 1", "MỨC dễ")).hasErrors());
        assertTrue(QuestionWordImport.parseText(VALID + "\nĐoạn thừa sau câu").hasErrors());
        assertTrue(QuestionWordImport.parseText(VALID.replace("Đáp án: B", "E. Lựa chọn thứ năm\nĐáp án: B")).hasErrors());
    }
    @Test void rejectsTooManyQuestionsAndOversizedQuestionOrOption() {
        assertThrows(IllegalArgumentException.class, () -> QuestionWordImport.parseText((VALID + "\n").repeat(501)));
        assertTrue(QuestionWordImport.parseText(VALID.replace("UDP", "x".repeat(501))).hasErrors());
        assertTrue(QuestionWordImport.parseText(VALID.replace("Giao thức tin cậy?", "x".repeat(10001))).hasErrors());
        assertTrue(QuestionWordImport.parseText("Chỉ có tiêu đề").hasErrors());
    }
    @Test void readsTextAcrossRunsAndShiftEnterBreaksAndStrictNamespace() throws Exception {
        String p = "<w:p><w:r><w:t>MỨ</w:t></w:r><w:r><w:t>C 1</w:t></w:r></w:p><w:p><w:r><w:t>Câu 1: Câu hỏi?</w:t>"
                + "<w:br/><w:t>A. A</w:t><w:br/><w:t>B. B</w:t><w:br/><w:t>C. C</w:t><w:br/><w:t>D. D</w:t><w:br/><w:t>Đáp án: C</w:t></w:r></w:p>";
        Path file = writeXml("split.docx", wrap(p));
        assertFalse(QuestionWordImport.read(file).hasErrors());
        assertFalse(QuestionWordImport.read(writeXml("strict.docx", wrap(p).replace("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "http://purl.oclc.org/ooxml/wordprocessingml/main"))).hasErrors());
    }
    @Test void neverSilentlyDropsTablesPicturesEquationsNumberingOrTrackedEdits() throws Exception {
        for (String unsupported : List.of("<w:tbl/>", "<w:drawing/>", "<w:pict/>", "<w:numPr/>", "<w:ins/>", "<w:altChunk/>", "<w:fldChar/>", "<m:oMath xmlns:m='http://schemas.openxmlformats.org/officeDocument/2006/math'/>")) {
            var parsed = QuestionWordImport.read(writeXml("unsupported.docx", wrap(paragraphs(VALID) + unsupported)));
            assertTrue(parsed.hasErrors(), unsupported); assertFalse(parsed.errors().isEmpty(), unsupported);
        }
    }
    @Test void rejectsBadFileTypesMissingDocumentAndOversizedExpandedXml() throws Exception {
        Path invalid = temp.resolve("old.doc"); Files.writeString(invalid, VALID);
        assertThrows(IllegalArgumentException.class, () -> QuestionWordImport.read(invalid));
        Path notZip = temp.resolve("notzip.docx"); Files.writeString(notZip, VALID);
        assertThrows(IllegalArgumentException.class, () -> QuestionWordImport.read(notZip));
        Path huge = temp.resolve("huge.docx"); Files.write(huge, new byte[QuestionWordImport.MAX_FILE_BYTES + 1]);
        assertThrows(IllegalArgumentException.class, () -> QuestionWordImport.read(huge));
        assertThrows(IllegalArgumentException.class, () -> QuestionWordImport.read(writeXml("expanded.docx", " ".repeat(4 * 1024 * 1024 + 1))));
        assertThrows(IllegalArgumentException.class, () -> QuestionWordImport.read(writeXml("invalidxml.docx", "<unclosed>")));
        Path emptyZip = temp.resolve("empty.docx");
        try (var out = new ZipOutputStream(Files.newOutputStream(emptyZip))) { out.putNextEntry(new ZipEntry("other.xml")); out.write(1); out.closeEntry(); }
        assertThrows(IllegalArgumentException.class, () -> QuestionWordImport.read(emptyZip));
    }
    @Test void rejectsDoctypeAndExternalEntityWithoutReadingExternalContent() throws Exception {
        String malicious = "<!DOCTYPE w:document [<!ENTITY xxe SYSTEM 'file:///C:/Windows/win.ini'>]>" + wrap("<w:p><w:r><w:t>&xxe;</w:t></w:r></w:p>");
        assertThrows(IllegalArgumentException.class, () -> QuestionWordImport.read(writeXml("entity.docx", malicious)));
    }
    private Path writeXml(String name, String xml) throws Exception {
        Path file = temp.resolve(name);
        try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("word/document.xml")); out.write(xml.getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        return file;
    }
    private static String wrap(String text) { return "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body>" + text + "</w:body></w:document>"; }
    private static String paragraphs(String text) { return Arrays.stream(text.split("\n")).map(line -> "<w:p><w:r><w:t>" + line + "</w:t></w:r></w:p>").reduce("", String::concat); }
}
