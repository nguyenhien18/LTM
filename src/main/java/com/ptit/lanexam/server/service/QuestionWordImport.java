package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.QuestionType;
import javax.xml.stream.*;
import java.io.*;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Text-only DOCX import. Limits apply to both the ZIP and its expanded XML. */
public final class QuestionWordImport {
    public static final int MAX_QUESTIONS = 500;
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_XML_BYTES = 4 * 1024 * 1024;
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern LEVEL = Pattern.compile("^(?:MỨC|MUC)\\s+([0-9]+)\\s*[:.]?$", FLAGS);
    private static final Pattern QUESTION = Pattern.compile("^(?:Câu|Cau)\\s+([0-9]+)\\s*[:.)]\\s*(.*)$", FLAGS);
    private static final Pattern OPTION = Pattern.compile("^([A-Z])[.)]\\s*(.*)$", FLAGS);
    private static final Pattern ANSWER = Pattern.compile("^(?:Đáp\\s*án|Dap\\s*an)\\s*:\\s*(.*)$", FLAGS);
    private QuestionWordImport() {}

    public record Item(String number, int line, int difficulty, ExamDraft.QuestionDraft question, List<String> errors) {
        public Item { errors = List.copyOf(errors); }
        public String label() { return "Câu " + number + " · dòng " + line; }
    }
    public record Parsed(List<Item> items, List<String> errors) {
        public Parsed { items = List.copyOf(items); errors = List.copyOf(errors); }
        public boolean hasErrors() { return !errors.isEmpty() || items.stream().anyMatch(q -> !q.errors().isEmpty()); }
    }

    public static Parsed read(Path file) throws IOException {
        if (file == null || !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".docx"))
            throw new IllegalArgumentException("Chọn tệp Word .docx. Tệp .doc cũ cần lưu lại thành .docx.");
        if (Files.size(file) > MAX_FILE_BYTES) throw new IllegalArgumentException("Tệp Word tối đa 5 MB.");
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.size() > 2048) throw new IllegalArgumentException("Tệp Word có quá nhiều thành phần. Dùng mẫu Word văn bản của ứng dụng.");
            ZipEntry document = zip.getEntry("word/document.xml");
            if (document == null || document.isDirectory()) throw new IllegalArgumentException("Tệp không phải tài liệu Word .docx hợp lệ.");
            byte[] xml;
            try (InputStream input = zip.getInputStream(document)) { xml = input.readNBytes(MAX_XML_BYTES + 1); }
            if (xml.length > MAX_XML_BYTES) throw new IllegalArgumentException("Nội dung Word sau giải nén quá lớn (tối đa 4 MB văn bản XML).");
            return readXml(xml);
        } catch (ZipException ex) { throw new IllegalArgumentException("Không đọc được tệp .docx. Mở bằng Word và lưu lại theo mẫu."); }
    }

    private static Parsed readXml(byte[] xml) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new XMLStreamException("External XML is disabled"); });
        List<String> paragraphs = new ArrayList<>(); Set<String> unsupported = new LinkedHashSet<>();
        StringBuilder paragraph = null; boolean body = false, seenBody = false;
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD) throw new XMLStreamException("DTD is disabled");
                    String tag = reader.hasName() ? reader.getLocalName() : "";
                    String namespace = reader.hasName() ? reader.getNamespaceURI() : "";
                    boolean word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(namespace)
                            || "http://purl.oclc.org/ooxml/wordprocessingml/main".equals(namespace);
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if (word && tag.equals("body")) { body = true; seenBody = true; }
                        if (!body) continue;
                        if (word && tag.equals("p")) paragraph = new StringBuilder();
                        if (word && tag.equals("t") && paragraph != null) paragraph.append(reader.getElementText());
                        if (word && (tag.equals("br") || tag.equals("cr")) && paragraph != null) paragraph.append('\n');
                        if (word && tag.equals("tab") && paragraph != null) paragraph.append(' ');
                        if (word && tag.equals("tbl")) unsupported.add("Có bảng trong Word. Chuyển câu hỏi thành các đoạn văn bản theo mẫu.");
                        if ((word && Set.of("drawing", "pict", "object", "altChunk", "footnoteReference", "endnoteReference").contains(tag)) || tag.equals("oMath"))
                            unsupported.add("Có ảnh, công thức Word hoặc nội dung nhúng chưa được hỗ trợ. Chuyển sang văn bản trước khi nhập.");
                        if (word && tag.equals("numPr")) unsupported.add("Có danh sách đánh số tự động. Dùng nhãn Câu 1:, A., B., C., D. dạng văn bản như file mẫu.");
                        if (word && Set.of("ins", "del", "moveFrom", "moveTo").contains(tag))
                            unsupported.add("Tài liệu có Track Changes. Chấp nhận hoặc từ chối các thay đổi trong Word trước khi nhập.");
                        if (word && Set.of("fldChar", "instrText").contains(tag)) unsupported.add("Có trường tự động trong nội dung. Chuyển sang văn bản theo mẫu trước khi nhập.");
                        if (paragraph != null && paragraph.length() > 60_000) throw new IllegalArgumentException("Một đoạn Word quá dài. Tách câu hỏi và lựa chọn thành các dòng theo mẫu.");
                    } else if (event == XMLStreamConstants.END_ELEMENT && word) {
                        if (tag.equals("p") && paragraph != null) { paragraphs.add(paragraph.toString()); paragraph = null; }
                        if (tag.equals("body")) body = false;
                    }
                }
            } finally { reader.close(); }
        } catch (XMLStreamException ex) { throw new IllegalArgumentException("Nội dung Word không hợp lệ hoặc chứa cấu trúc XML không được hỗ trợ."); }
        if (!seenBody) throw new IllegalArgumentException("Tệp không có nội dung tài liệu Word hợp lệ.");
        Parsed parsed = parseText(String.join("\n", paragraphs));
        List<String> errors = new ArrayList<>(unsupported); errors.addAll(parsed.errors());
        return new Parsed(parsed.items(), errors);
    }

    public static Parsed parseText(String text) {
        if (text == null || text.length() > MAX_XML_BYTES) throw new IllegalArgumentException("Nội dung nhập trống hoặc quá lớn.");
        List<Item> items = new ArrayList<>(); List<String> errors = new ArrayList<>();
        int level = 0; Builder current = null; int line = 0;
        for (String raw : text.split("\\R", -1)) {
            line++; String value = Normalizer.normalize(raw.replace('\u00a0', ' ').strip(), Normalizer.Form.NFC);
            if (value.isBlank()) continue;
            Matcher heading = LEVEL.matcher(value), question = QUESTION.matcher(value), option = OPTION.matcher(value), answer = ANSWER.matcher(value);
            if (heading.matches()) {
                if (current != null) { items.add(current.build()); current = null; }
                try { level = Integer.parseInt(heading.group(1)); } catch (NumberFormatException ex) { level = 0; }
                if (level < 1 || level > 6) { errors.add("Dòng " + line + ": mức phải từ 1 đến 6."); level = 0; }
            } else if (question.matches()) {
                if (current != null) items.add(current.build());
                if (items.size() >= MAX_QUESTIONS) throw new IllegalArgumentException("Mỗi tệp tối đa 500 câu hỏi.");
                current = new Builder(question.group(1), line, level, question.group(2));
            } else if (option.matches()) {
                if (current == null) errors.add("Dòng " + line + ": lựa chọn xuất hiện trước nhãn Câu n:.");
                else current.option(option.group(1).toUpperCase(Locale.ROOT), option.group(2));
            } else if (answer.matches()) {
                if (current == null) errors.add("Dòng " + line + ": đáp án xuất hiện trước câu hỏi.");
                else current.answer(answer.group(1));
            } else if (value.matches("(?iu)^(?:MỨC|MUC)\\b.*")) {
                if (current != null) { items.add(current.build()); current = null; }
                level = 0; errors.add("Dòng " + line + ": dùng tiêu đề MỨC 1 đến MỨC 6.");
            } else if (current != null) current.continuation(value);
            else if (level != 0 || !items.isEmpty()) errors.add("Dòng " + line + ": chưa có nhãn Câu n: cho nội dung này.");
            // A title and instructions may precede the first MỨC heading.
            if (errors.size() > 100) throw new IllegalArgumentException("Quá nhiều dòng sai mẫu. Soạn lại theo file Word mẫu.");
        }
        if (current != null) items.add(current.build());
        if (items.isEmpty()) errors.add("Không tìm thấy câu hỏi. Dùng MỨC 1, Câu 1:, bốn lựa chọn A.–D. và Đáp án: A.");
        return new Parsed(items, errors);
    }

    private static final class Builder {
        final String number; final int line, level;
        final StringBuilder content; final Map<String, StringBuilder> options = new LinkedHashMap<>();
        final List<String> errors = new ArrayList<>(); String activeOption, correct; boolean answered;
        Builder(String number, int line, int level, String content) {
            this.number = number; this.line = line; this.level = level; this.content = new StringBuilder(content);
        }
        void option(String key, String value) {
            if (!Set.of("A", "B", "C", "D").contains(key)) {
                errors.add("Có lựa chọn " + key + ". Chỉ hỗ trợ bốn lựa chọn A–D."); return;
            }
            if (answered) errors.add("Lựa chọn " + key + " nằm sau dòng Đáp án.");
            if (options.containsKey(key)) errors.add("Lựa chọn " + key + " bị lặp.");
            else options.put(key, new StringBuilder(value));
            activeOption = key;
        }
        void answer(String value) {
            if (answered) errors.add("Có nhiều dòng Đáp án trong cùng câu.");
            correct = value.strip().toUpperCase(Locale.ROOT); answered = true;
        }
        void continuation(String value) {
            if (answered) errors.add("Có nội dung thừa sau Đáp án. Câu tiếp theo cần bắt đầu bằng Câu n:.");
            else { StringBuilder target = activeOption == null ? content : options.get(activeOption); if (!target.isEmpty()) target.append('\n'); target.append(value); }
        }
        String option(String key) { return options.containsKey(key) ? options.get(key).toString().strip() : ""; }
        Item build() {
            if (level < 1 || level > 6) errors.add("Thiếu tiêu đề MỨC 1–6 trước câu hỏi.");
            if (number.chars().allMatch(c -> c == '0')) errors.add("Số câu phải lớn hơn 0.");
            for (String key : List.of("A", "B", "C", "D")) if (option(key).isBlank()) errors.add("Thiếu lựa chọn " + key + ".");
            if (correct == null || !Set.of("A", "B", "C", "D").contains(correct)) errors.add("Đáp án phải là đúng một chữ A, B, C hoặc D.");
            var draft = new ExamDraft.QuestionDraft(content.toString().strip(), QuestionType.MULTIPLE_CHOICE, option("A"), option("B"), option("C"), option("D"), correct);
            try { QuestionRules.validate(draft); } catch (IllegalArgumentException ex) { if (!errors.contains(ex.getMessage())) errors.add(ex.getMessage()); }
            return new Item(number, line, level, draft, errors);
        }
    }
}
