package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.*;
import com.ptit.lanexam.server.persistence.JpaUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static BankQuestionInfo info(BankQuestion question) {
        return new BankQuestionInfo(question.getId(), question.getSubject().getId(), question.getSubject().getName(),
                question.getDifficulty(), question.getContent(), question.getType(), question.getOptionA(), question.getOptionB(),
                question.getOptionC(), question.getOptionD(), question.getCorrectAnswer());
    }
}
