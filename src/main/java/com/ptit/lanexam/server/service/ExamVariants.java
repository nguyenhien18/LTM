package com.ptit.lanexam.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ptit.lanexam.common.dto.QuestionDto;
import com.ptit.lanexam.server.entity.*;
import java.security.SecureRandom;
import java.util.*;

/** Server-only immutable layouts; clients receive only their ordered question DTOs. */
final class ExamVariants {
    static final int MAX_VARIANTS = 8;
    private static final ObjectMapper JSON = new ObjectMapper();
    record Entry(Long questionId, List<Integer> options) {}
    record Variant(String code, List<Entry> entries) {}
    record Bundle(List<Variant> variants, Map<String, String> assignments) {}
    private ExamVariants() {}

    // Missing legacy metadata is a separate final group; never infer it from a mutable bank question.
    private static int level(Question question) {
        Integer value = question.getDifficulty();
        return value == null || value < 1 || value > 6 ? 7 : value;
    }
    static List<Question> orderedQuestions(Exam exam) {
        return exam.getQuestions().stream().sorted(Comparator.comparingInt(ExamVariants::level)).toList();
    }

    static String create(Exam exam, int count, List<String> candidates) {
        if (count < 1 || count > MAX_VARIANTS) throw new IllegalArgumentException("Số mã đề từ 1 đến 8.");
        if (count == 1) return null; // New rounds are already ordered; existing rounds are never rewritten.
        if (exam.getCourseClass() == null || candidates.isEmpty()) throw new IllegalArgumentException("Chọn danh sách sinh viên để phân mã đề.");
        Map<Integer, List<Question>> groups = new TreeMap<>();
        for (Question question : orderedQuestions(exam)) groups.computeIfAbsent(level(question), ignored -> new ArrayList<>()).add(question);
        if (exam.getQuestions().stream().allMatch(ExamVariants::positionalOptions)) {
            int possible = 1;
            for (List<Question> group : groups.values()) {
                for (int n = 2; n <= group.size() && possible < count; n++) possible = Math.min(count, possible * n);
            }
            if (possible < count) throw new IllegalArgumentException("Giữ nguyên lựa chọn tham chiếu A/B và chỉ đảo câu trong cùng mức. Bộ câu hiện chỉ tạo được " + possible + " mã khác nhau; giảm số mã hoặc thêm câu cùng mức/câu có lựa chọn độc lập.");
        }
        SecureRandom random = new SecureRandom(); List<Variant> variants = new ArrayList<>();
        Set<List<Entry>> used = new HashSet<>();
        for (int i = 0; i < count; i++) {
            List<Entry> entries = null;
            for (int retry = 0; retry < 100; retry++) {
                var next = new ArrayList<Entry>();
                for (List<Question> group : groups.values()) {
                    var entriesInLevel = new ArrayList<Entry>();
                    for (Question q : group) {
                        var options = new ArrayList<>(List.of(0, 1, 2, 3));
                        if (!positionalOptions(q)) Collections.shuffle(options, random);
                        entriesInLevel.add(new Entry(q.getId(), List.copyOf(options)));
                    }
                    Collections.shuffle(entriesInLevel, random);
                    next.addAll(entriesInLevel);
                }
                if (used.add(next)) { entries = List.copyOf(next); break; }
            }
            if (entries == null) throw new IllegalStateException("Chưa tạo đủ mã khác nhau. Hãy thử lại.");
            variants.add(new Variant(Integer.toString(101 + i), entries));
        }
        List<String> assignments = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) assignments.add(variants.get(i % count).code());
        Collections.shuffle(assignments, random);
        Map<String, String> byStudent = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) byStudent.put(candidates.get(i), assignments.get(i));
        try { return JSON.writeValueAsString(new Bundle(variants, byStudent)); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Không lưu được dữ liệu mã đề.", ex); }
    }
    static Bundle read(Exam exam) {
        if (exam.getVariantsJson() == null) return null;
        try { return JSON.readValue(exam.getVariantsJson(), Bundle.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Dữ liệu mã đề không hợp lệ. Không thể cấp đề hoặc chấm bài.", ex); }
    }
    private static boolean positionalOptions(Question q) {
        String text = String.join("\n", q.getContent(), q.getOptionA(), q.getOptionB(), q.getOptionC(), q.getOptionD());
        text = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replace('đ', 'd');
        return text.contains("tat ca") || text.contains("o tren") || text.contains("ben tren") || text.contains("of the above")
                || text.matches("(?s).*(?:dap an|phuong an|lua chon|option|answer|choice)\\s+[a-d]\\b.*")
                || text.matches("(?s).*\\b(?:ca\\s+|both\\s+)?[a-d]\\s*(?:va|hoac|and|or|&|,)\\s*[a-d]\\b.*");
    }
    static String code(Exam exam, String studentCode) {
        Bundle bundle = read(exam);
        return bundle == null ? null : bundle.assignments().get(studentCode);
    }
    static boolean eligible(Exam exam, String studentCode) { return exam.getVariantsJson() == null || code(exam, studentCode) != null; }
    static int count(Exam exam) { Bundle bundle = read(exam); return bundle == null ? 1 : bundle.variants().size(); }
    static Variant variant(ExamAttempt attempt) {
        Bundle bundle = read(attempt.getExam());
        if (bundle == null) return null;
        if (!Objects.equals(attempt.getVariantCode(), bundle.assignments().get(attempt.getStudent().getStudentCode())))
            throw new IllegalStateException("Mã đề của lượt làm không khớp danh sách đã phân.");
        return bundle.variants().stream().filter(v -> v.code().equals(attempt.getVariantCode())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy mã đề của lượt làm."));
    }
    static List<QuestionDto> questions(ExamAttempt attempt) {
        Variant variant = variant(attempt); if (variant == null) return null;
        Map<Long, Question> byId = new HashMap<>(); attempt.getExam().getQuestions().forEach(q -> byId.put(q.getId(), q));
        List<QuestionDto> result = new ArrayList<>();
        for (Entry entry : variant.entries()) {
            Question q = byId.get(entry.questionId());
            List<String> original = List.of(q.getOptionA(), q.getOptionB(), q.getOptionC(), q.getOptionD());
            List<String> displayed = new ArrayList<>();
            for (int i = 0; i < 4; i++) displayed.add((char) ('A' + i) + ". " + original.get(entry.options().get(i)));
            result.add(new QuestionDto(q.getId(), q.getContent(), q.getType().name(), displayed));
        }
        return result;
    }
    static String originalAnswer(Variant variant, Long questionId, String displayed) {
        if (variant == null || displayed == null || displayed.isBlank()) return displayed;
        String normalized = displayed.strip().toUpperCase(Locale.ROOT);
        if (normalized.length() != 1 || normalized.charAt(0) < 'A' || normalized.charAt(0) > 'D') return null;
        Entry entry = variant.entries().stream().filter(e -> e.questionId().equals(questionId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Câu hỏi không thuộc mã đề."));
        return String.valueOf((char) ('A' + entry.options().get(normalized.charAt(0) - 'A')));
    }
    static String title(String title, String code) { return code == null ? title : title + " · Mã đề " + code; }
}
