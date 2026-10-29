package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.*;
import com.ptit.lanexam.server.persistence.JpaUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

public class QuestionBankService {
    private final Supplier<EntityManager> entityManagers;
    private final TeacherScope scope;

    public QuestionBankService() { this(JpaUtil::em); }
    public QuestionBankService(Supplier<EntityManager> entityManagers) { this(entityManagers, null); }
    public QuestionBankService(Supplier<EntityManager> entityManagers, Long teacherId) {
        this.entityManagers = entityManagers; this.scope = new TeacherScope(teacherId);
    }

    public SubjectInfo createSubject(String rawName) {
        String name = rawName == null ? "" : rawName.strip().replaceAll("\\s+", " ");
        if (name.isBlank() || name.length() > 120)
            throw new IllegalArgumentException("Tên môn học phải có từ 1 đến 120 ký tự.");
        String key = name.toLowerCase(Locale.ROOT);
        if (scope.teacherId() != null) key = "gv" + scope.teacherId() + ":" + key;
        EntityManager em = entityManagers.get();
        try {
            em.getTransaction().begin();
            if (!em.createQuery("select s.id from Subject s where s.normalizedName = :key", Long.class)
                    .setParameter("key", key).getResultList().isEmpty())
                throw new IllegalArgumentException("Môn học này đã tồn tại.");
            Subject subject = new Subject(name, key);
            scope.assign(subject);
            em.persist(subject);
            em.getTransaction().commit();
            return new SubjectInfo(subject.getId(), subject.getName(), 0);
        } catch (RuntimeException ex) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            throw ex;
        } finally { em.close(); }
    }

    public List<SubjectInfo> listSubjects() {
        EntityManager em = entityManagers.get();
        try {
            Map<Long, Long> counts = new HashMap<>();
            for (Object[] row : em.createQuery("select q.subject.id, count(q) from BankQuestion q "
                    + "where " + scope.predicate("q") + " and q.type = :choice group by q.subject.id", Object[].class)
                    .setParameter("choice", QuestionType.MULTIPLE_CHOICE).getResultList())
                counts.put((Long) row[0], (Long) row[1]);
            return em.createQuery("select s from Subject s where " + scope.predicate("s") + " order by s.name, s.id", Subject.class).getResultList().stream()
                    .map(subject -> new SubjectInfo(subject.getId(), subject.getName(), counts.getOrDefault(subject.getId(), 0L))).toList();
        } finally { em.close(); }
    }

    public BankQuestionInfo createQuestion(BankQuestionDraft draft) { return save(null, draft); }
    public BankQuestionInfo updateQuestion(Long questionId, BankQuestionDraft draft) {
        if (questionId == null) throw new IllegalArgumentException("Chưa chọn câu hỏi.");
        return save(questionId, draft);
    }

    public static void validateDraft(BankQuestionDraft draft) {
        if (draft == null || draft.subjectId() == null) throw new IllegalArgumentException("Chọn môn học cho câu hỏi.");
        if (draft.difficulty() < 1 || draft.difficulty() > 6)
            throw new IllegalArgumentException("Độ khó phải từ 1 đến 6.");
        QuestionRules.validate(draft.question());
    }

    public enum WordStatus {
        NEW("Sẽ nhập"), EXISTS("Đã có · bỏ qua"), DUPLICATE("Lặp trong tệp · bỏ qua"), INVALID("Cần sửa");
        private final String label;
        WordStatus(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public record WordRow(QuestionWordImport.Item item, WordStatus status, String detail) {}
    public record WordPreview(List<WordRow> rows, List<String> errors) {
        public WordPreview { rows = List.copyOf(rows); errors = List.copyOf(errors); }
        public boolean hasErrors() { return !errors.isEmpty() || rows.stream().anyMatch(r -> r.status() == WordStatus.INVALID); }
        public long count(WordStatus status) { return rows.stream().filter(r -> r.status() == status).count(); }
        public String summary() { return count(WordStatus.NEW) + " câu mới · " + (count(WordStatus.EXISTS) + count(WordStatus.DUPLICATE))
                + " câu trùng bỏ qua · " + count(WordStatus.INVALID) + " câu cần sửa"; }
    }
    public record WordImportResult(int imported, int skipped) {}

    public WordPreview previewWord(Long subjectId, QuestionWordImport.Parsed parsed) {
        validateWordSize(parsed);
        try (EntityManager em = entityManagers.get()) {
            Subject subject = subjectId == null ? null : em.find(Subject.class, subjectId);
            scope.require(subject); return inspectWord(em, subject, parsed);
        }
    }
    public WordImportResult importWord(Long subjectId, QuestionWordImport.Parsed parsed) {
        validateWordSize(parsed);
        try (EntityManager em = entityManagers.get()) {
            try {
                em.getTransaction().begin();
                Subject subject = subjectId == null ? null : em.find(Subject.class, subjectId, LockModeType.PESSIMISTIC_WRITE);
                scope.require(subject);
                WordPreview preview = inspectWord(em, subject, parsed);
                if (preview.hasErrors()) throw new IllegalArgumentException("Tệp Word còn lỗi. Sửa các câu được báo và xem trước lại; chưa nhập câu nào.");
                int imported = 0;
                for (WordRow row : preview.rows()) {
                    if (row.status() != WordStatus.NEW) continue;
                    var q = row.item().question(); BankQuestion question = new BankQuestion(); scope.assign(question);
                    question.update(subject, row.item().difficulty(), q.content(), q.type(), q.optionA(), q.optionB(), q.optionC(), q.optionD(), q.correctAnswer());
                    em.persist(question); imported++;
                }
                em.flush(); em.getTransaction().commit();
                return new WordImportResult(imported, preview.rows().size() - imported);
            } catch (RuntimeException ex) { if (em.getTransaction().isActive()) em.getTransaction().rollback(); throw ex; }
        }
    }
    private WordPreview inspectWord(EntityManager em, Subject subject, QuestionWordImport.Parsed parsed) {
        Set<List<Object>> existing = new HashSet<>(); Set<List<Object>> seen = new HashSet<>();
        em.createQuery("select q from BankQuestion q where q.subject = :subject and " + scope.predicate("q"), BankQuestion.class)
                .setParameter("subject", subject).getResultList().forEach(q -> existing.add(wordKey(q.getDifficulty(),
                        new ExamDraft.QuestionDraft(q.getContent(), q.getType(), q.getOptionA(), q.getOptionB(), q.getOptionC(), q.getOptionD(), q.getCorrectAnswer()))));
        List<WordRow> rows = new ArrayList<>();
        for (var item : parsed.items()) {
            if (item == null) throw new IllegalArgumentException("Câu hỏi nhập không hợp lệ.");
            List<String> errors = new ArrayList<>(item.errors());
            try { validateDraft(new BankQuestionDraft(subject.getId(), item.difficulty(), item.question())); }
            catch (IllegalArgumentException ex) { if (!errors.contains(ex.getMessage())) errors.add(ex.getMessage()); }
            if (!errors.isEmpty()) { rows.add(new WordRow(item, WordStatus.INVALID, String.join(" ", errors))); continue; }
            var key = wordKey(item.difficulty(), item.question());
            WordStatus status = !seen.add(key) ? WordStatus.DUPLICATE : existing.contains(key) ? WordStatus.EXISTS : WordStatus.NEW;
            rows.add(new WordRow(item, status, status.toString()));
        }
        return new WordPreview(rows, parsed.errors());
    }
    private static void validateWordSize(QuestionWordImport.Parsed parsed) {
        if (parsed != null && parsed.items().isEmpty() && !parsed.errors().isEmpty())
            throw new IllegalArgumentException(String.join("\n", parsed.errors()));
        if (parsed == null || parsed.items().isEmpty() || parsed.items().size() > QuestionWordImport.MAX_QUESTIONS)
            throw new IllegalArgumentException("Tệp phải có 1–500 câu hỏi theo mẫu Word.");
    }
    private static List<Object> wordKey(int level, ExamDraft.QuestionDraft q) {
        return List.of(level, q.type(), wordText(q.content()), wordText(q.optionA()), wordText(q.optionB()),
                wordText(q.optionC()), wordText(q.optionD()), wordText(q.correctAnswer()).toUpperCase(Locale.ROOT));
    }
    private static String wordText(String value) {
        return value == null ? "" : java.text.Normalizer.normalize(value.strip().replaceAll("\\s+", " "), java.text.Normalizer.Form.NFC);
    }

    private BankQuestionInfo save(Long questionId, BankQuestionDraft draft) {
        validateDraft(draft);
        EntityManager em = entityManagers.get();
        try {
            em.getTransaction().begin();
            Subject subject = em.find(Subject.class, draft.subjectId());
            if (subject == null) throw new IllegalArgumentException("Môn học không tồn tại.");
            scope.require(subject);
            BankQuestion question = questionId == null ? new BankQuestion()
                    : em.find(BankQuestion.class, questionId, LockModeType.PESSIMISTIC_WRITE);
            if (question == null) throw new IllegalArgumentException("Không tìm thấy câu hỏi.");
            if (questionId == null) scope.assign(question); else scope.require(question);
            var q = draft.question();
            question.update(subject, draft.difficulty(), q.content(), q.type(), q.optionA(), q.optionB(),
                    q.optionC(), q.optionD(), q.correctAnswer());
            if (questionId == null) em.persist(question);
            em.flush();
            BankQuestionInfo result = info(question);
            em.getTransaction().commit();
            return result;
        } catch (RuntimeException ex) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            throw ex;
        } finally { em.close(); }
    }

    public List<BankQuestionInfo> listQuestions(Long subjectId, Integer difficulty) {
        if (subjectId == null) return List.of();
        if (difficulty != null && (difficulty < 1 || difficulty > 6))
            throw new IllegalArgumentException("Độ khó phải từ 1 đến 6.");
        EntityManager em = entityManagers.get();
        try {
            String hql = "select q from BankQuestion q join fetch q.subject where q.subject.id = :subject and q.type = :choice"
                    + " and " + scope.predicate("q")
                    + (difficulty == null ? "" : " and q.difficulty = :difficulty")
                    + " order by q.id desc";
            var query = em.createQuery(hql, BankQuestion.class).setParameter("subject", subjectId)
                    .setParameter("choice", QuestionType.MULTIPLE_CHOICE);
            if (difficulty != null) query.setParameter("difficulty", difficulty);
            return query.getResultList().stream().map(QuestionBankService::info).toList();
        } finally { em.close(); }
    }

    /** Draw without replacement; this only previews a selection and never writes. */
    public List<BankQuestionInfo> drawQuestions(Long subjectId, Map<Integer, Integer> counts, Set<Long> excluded) {
        if (counts == null || counts.isEmpty()) throw new IllegalArgumentException("Nhập số câu cho các mức 1–6.");
        long total = 0;
        for (var entry : counts.entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 1 || entry.getKey() > 6 || entry.getValue() == null || entry.getValue() < 0)
                throw new IllegalArgumentException("Mức từ 1–6, số câu phải là số nguyên không âm.");
            total += entry.getValue();
        }
        if (total < 1 || total > ExamService.MAX_BANK_EXAM_QUESTIONS) throw new IllegalArgumentException("Tổng số câu từ 1 đến 200.");
        try (EntityManager em = entityManagers.get()) { scope.require(subjectId == null ? null : em.find(Subject.class, subjectId)); }
        List<BankQuestionInfo> available = listQuestions(subjectId, null);
        var random = new java.security.SecureRandom(); List<BankQuestionInfo> selected = new ArrayList<>();
        for (int level = 1; level <= 6; level++) {
            int wanted = counts.getOrDefault(level, 0); if (wanted == 0) continue;
            int currentLevel = level;
            var pool = new ArrayList<>(available.stream().filter(q -> q.difficulty() == currentLevel && (excluded == null || !excluded.contains(q.id()))).toList());
            if (pool.size() < wanted) throw new IllegalArgumentException("Mức " + level + " cần " + wanted + " câu, chỉ còn " + pool.size() + " câu phù hợp chưa chọn.");
            java.util.Collections.shuffle(pool, random); selected.addAll(pool.subList(0, wanted));
        }
        return List.copyOf(selected);
    }

    private static BankQuestionInfo info(BankQuestion question) {
        return new BankQuestionInfo(question.getId(), question.getSubject().getId(), question.getSubject().getName(),
                question.getDifficulty(), question.getContent(), question.getType(), question.getOptionA(), question.getOptionB(),
                question.getOptionC(), question.getOptionD(), question.getCorrectAnswer());
    }
}
