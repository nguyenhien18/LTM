package com.ptit.lanexam.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.ptit.lanexam.common.JsonUtil;
import com.ptit.lanexam.common.dto.ExamDto;
import com.ptit.lanexam.common.dto.ExamOffer;
import com.ptit.lanexam.common.dto.ExamHistoryItem;
import com.ptit.lanexam.common.dto.QuestionDto;
import com.ptit.lanexam.common.dto.SubmitExamRequest;
import com.ptit.lanexam.common.dto.SubmitResult;
import com.ptit.lanexam.server.entity.*;
import com.ptit.lanexam.server.persistence.JpaUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Supplier;

public class ExamService {
    private static final TypeReference<Map<Long, String>> ANSWER_TYPE = new TypeReference<>() {};
    private final Supplier<EntityManager> entityManagers;

    public ExamService() { this(JpaUtil::em); }

    public ExamService(Supplier<EntityManager> entityManagers) {
        this.entityManagers = entityManagers;
    }

    public ExamSummary createExam(ExamDraft draft) {
        if (draft == null || draft.title() == null || draft.title().isBlank()
                || draft.title().trim().length() > 200)
            throw new IllegalArgumentException("Tên đề phải có từ 1 đến 200 ký tự.");
        if (draft.durationMinutes() < 1 || draft.durationMinutes() > 360)
            throw new IllegalArgumentException("Thời gian làm bài phải từ 1 đến 360 phút.");
        if (draft.questions() == null || draft.questions().isEmpty())
            throw new IllegalArgumentException("Đề thi cần ít nhất một câu hỏi.");
        for (ExamDraft.QuestionDraft question : draft.questions()) validateQuestion(question);

        EntityManager em = entityManagers.get();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            Exam exam = new Exam(draft.title().trim(), draft.durationMinutes(), false);
            for (ExamDraft.QuestionDraft q : draft.questions()) {
                boolean multipleChoice = q.type() == QuestionType.MULTIPLE_CHOICE;
                exam.addQuestion(new Question(q.content().trim(), q.type(),
                        multipleChoice ? q.optionA().trim() : null,
                        multipleChoice ? q.optionB().trim() : null,
                        multipleChoice ? q.optionC().trim() : null,
                        multipleChoice ? q.optionD().trim() : null,
                        q.correctAnswer().trim()));
            }
            em.persist(exam);
            em.flush();
            ExamSummary result = new ExamSummary(exam.getId(), exam.getTitle(), exam.getDurationMinutes(),
                    exam.getQuestions().size(), false);
            tx.commit();
            return result;
        } catch (RuntimeException e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }
    }

    public List<ExamSummary> listExams() {
        EntityManager em = entityManagers.get();
        try {
            Long currentTemplateId = em.createQuery("select e from Exam e where e.active = true order by e.id desc", Exam.class)
                    .setMaxResults(1).getResultList().stream().findFirst()
                    .map(e -> e.getTemplateExamId() == null ? e.getId() : e.getTemplateExamId())
                    .orElse(null);
            return em.createQuery("select distinct e from Exam e left join fetch e.questions "
                            + "where e.templateExamId is null "
                            + "order by e.id desc", Exam.class)
                    .getResultList().stream()
                    .map(e -> new ExamSummary(e.getId(), e.getTitle(), e.getDurationMinutes(),
                            e.getQuestions().size(), e.getId().equals(currentTemplateId)))
                    .toList();
        } finally {
            em.close();
        }
    }

    public ExamDto activateExam(Long examId) {
        if (examId == null) throw new IllegalArgumentException("Chưa chọn bài thi.");
        EntityManager em = entityManagers.get();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            Exam template = em.find(Exam.class, examId, LockModeType.PESSIMISTIC_WRITE);
            if (template == null || template.getTemplateExamId() != null)
                throw new IllegalArgumentException("Không tìm thấy đề gốc đã chọn.");
            if (template.getQuestions().isEmpty()) throw new IllegalStateException("Bài thi không có câu hỏi.");
            Exam current = em.createQuery("select e from Exam e where e.active = true order by e.id desc", Exam.class)
                    .setMaxResults(1).getResultList().stream().findFirst().orElse(null);
            if (current != null) current = em.find(Exam.class, current.getId(), LockModeType.PESSIMISTIC_WRITE);
            boolean sameTemplate = current != null && (examId.equals(current.getId())
                    || examId.equals(current.getTemplateExamId()));
            long openAttempts = em.createQuery("select count(a) from ExamAttempt a where a.exam.active = true "
                            + "and a.submittedAt is null and a.deadlineAt > :now", Long.class)
                    .setParameter("now", Instant.now()).getSingleResult();
            if (openAttempts > 0 && !sameTemplate)
                throw new IllegalStateException("Còn sinh viên đang làm bài hiện tại. Hãy chờ nộp hoặc hết giờ trước khi đổi bài.");
            if (sameTemplate) {
                long priorAttempts = em.createQuery("select count(a) from ExamAttempt a where a.exam = :exam", Long.class)
                        .setParameter("exam", current).getSingleResult();
                long priorSubmissions = em.createQuery("select count(s) from Submission s where s.exam = :exam", Long.class)
                        .setParameter("exam", current).getSingleResult();
                if (openAttempts > 0 || (priorAttempts == 0 && priorSubmissions == 0)) {
                    ExamDto unchanged = toDto(current);
                    tx.commit();
                    return unchanged;
                }
            }
            em.createQuery("update Exam e set e.active = false where e.active = true").executeUpdate();
            Exam round = new Exam(template.getTitle(), template.getDurationMinutes(), true);
            round.setTemplateExamId(template.getId());
            for (Question question : template.getQuestions())
                round.addQuestion(new Question(question.getContent(), question.getType(),
                        question.getOptionA(), question.getOptionB(), question.getOptionC(),
                        question.getOptionD(), question.getCorrectAnswer()));
            em.persist(round);
            em.flush();
            ExamDto result = toDto(round);
            tx.commit();
            return result;
        } catch (RuntimeException e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }
    }

    private void validateQuestion(ExamDraft.QuestionDraft q) {
        if (q == null || q.type() == null || q.content() == null || q.content().isBlank())
            throw new IllegalArgumentException("Mỗi câu hỏi cần nội dung và loại câu hỏi.");
        if (q.correctAnswer() == null || q.correctAnswer().isBlank() || q.correctAnswer().trim().length() > 500)
            throw new IllegalArgumentException("Mỗi câu hỏi cần đáp án đúng tối đa 500 ký tự.");
        if (q.type() == QuestionType.MULTIPLE_CHOICE) {
            for (String option : new String[]{q.optionA(), q.optionB(), q.optionC(), q.optionD()}) {
                if (option == null || option.isBlank() || option.trim().length() > 500)
                    throw new IllegalArgumentException("Câu trắc nghiệm cần đủ bốn lựa chọn (mỗi lựa chọn tối đa 500 ký tự).");
            }
            if (!Set.of("A", "B", "C", "D").contains(q.correctAnswer().trim().toUpperCase(Locale.ROOT)))
                throw new IllegalArgumentException("Đáp án trắc nghiệm phải là A, B, C hoặc D.");
        }
    }

    public List<ExamResultRow> listResults() {
        EntityManager em = entityManagers.get();
        try {
            return em.createQuery("select s from Submission s order by s.submittedAt desc", Submission.class)
                    .getResultList().stream()
                    .map(s -> new ExamResultRow(s.getExam().getId(), s.getStudent().getStudentCode(), s.getStudent().getFullName(),
                            s.getExam().getTitle(), s.getScore(),
                            (int) s.getAnswers().stream().filter(StudentAnswer::isCorrect).count(),
                            s.getAnswers().size(), s.getSubmittedAt()))
                    .toList();
        } finally {
            em.close();
        }
    }

    public List<ExamHistoryItem> listStudentHistory(String studentCode) {
        EntityManager em = entityManagers.get();
        try {
            return em.createQuery("select s from Submission s where s.student.studentCode = :code "
                            + "order by s.submittedAt desc", Submission.class)
                    .setParameter("code", studentCode).getResultList().stream()
                    .map(s -> new ExamHistoryItem(s.getExam().getId(), s.getExam().getTitle(), s.getScore(),
                            (int) s.getAnswers().stream().filter(StudentAnswer::isCorrect).count(),
                            s.getAnswers().size(), s.getSubmittedAt()))
                    .toList();
        } finally {
            em.close();
        }
    }

    public Optional<ExamOffer> findOffer(String studentCode, Long publishedExamId) {
        EntityManager em = entityManagers.get();
        try {
            Optional<ExamAttempt> open = em.createQuery("select a from ExamAttempt a "
                            + "where a.student.studentCode = :code and a.submittedAt is null "
                            + "and a.deadlineAt > :now order by a.startedAt desc", ExamAttempt.class)
                    .setParameter("code", studentCode).setParameter("now", Instant.now())
                    .setMaxResults(1).getResultList().stream().findFirst();
            if (open.isPresent()) {
                ExamAttempt attempt = open.get();
                Exam exam = attempt.getExam();
                int seconds = (int) Math.min(Integer.MAX_VALUE,
                        Math.max(0, (attempt.getDeadlineAt().toEpochMilli() - Instant.now().toEpochMilli() + 999) / 1000));
                return Optional.of(new ExamOffer(exam.getId(), exam.getTitle(), seconds, true));
            }
            if (publishedExamId == null) return Optional.empty();
            Exam exam = em.find(Exam.class, publishedExamId);
            if (exam == null || !exam.isActive()) return Optional.empty();
            long prior = em.createQuery("select count(a) from ExamAttempt a "
                            + "where a.student.studentCode = :code and a.exam = :exam", Long.class)
                    .setParameter("code", studentCode).setParameter("exam", exam).getSingleResult();
            long submitted = em.createQuery("select count(s) from Submission s "
                            + "where s.student.studentCode = :code and s.exam = :exam", Long.class)
                    .setParameter("code", studentCode).setParameter("exam", exam).getSingleResult();
            if (prior > 0 || submitted > 0) return Optional.empty();
            return Optional.of(new ExamOffer(exam.getId(), exam.getTitle(), exam.getDurationMinutes() * 60, false));
        } finally {
            em.close();
        }
    }
    public ExamDto getActiveExam() {
        EntityManager em = entityManagers.get();
        try {
            Exam exam = em.createQuery(
                            "select distinct e from Exam e left join fetch e.questions where e.active = true order by e.id",
                            Exam.class)
                    .setMaxResults(1)
                    .getResultStream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Chưa có đề thi đang hoạt động."));
            return toDto(exam);
        } finally {
            em.close();
        }
    }

    public SubmitResult submit(String studentCode, SubmitExamRequest request) {
        EntityManager em = entityManagers.get();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            ExamAttempt attempt = requireAttempt(em, studentCode, request);
            Submission previous = findSubmission(em, attempt).orElse(null);
            if (previous != null) {
                if (attempt.getSubmittedAt() == null) attempt.markSubmitted(Instant.now());
                SubmitResult result = toResult(previous);
                tx.commit();
                return result;
            }
            Map<Long, String> answers = attempt.isOpenAt(Instant.now())
                    ? validateAnswers(attempt.getExam(), request.getAnswers()) : readDraft(attempt);
            SubmitResult result = persistSubmission(em, attempt, answers);
            tx.commit();
            return result;
        } catch (RuntimeException e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }
    }

    public Optional<ExamDto> startAttempt(String studentCode, Long examId) {
        EntityManager em = entityManagers.get();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            Student student = em.createQuery("select s from Student s where s.studentCode = :code", Student.class)
                    .setParameter("code", studentCode).getSingleResult();
            Exam exam = em.find(Exam.class, examId, LockModeType.PESSIMISTIC_WRITE);
            if (exam == null || !exam.isActive() || exam.getDurationMinutes() <= 0)
                throw new IllegalStateException("Đề thi không hợp lệ.");
            long priorSubmissions = em.createQuery("select count(s) from Submission s "
                            + "where s.student = :student and s.exam = :exam", Long.class)
                    .setParameter("student", student).setParameter("exam", exam).getSingleResult();
            if (priorSubmissions > 0) {
                tx.commit();
                return Optional.empty();
            }
            ExamAttempt attempt = findAttempt(em, studentCode, examId, LockModeType.PESSIMISTIC_WRITE)
                    .orElseGet(() -> {
                        ExamAttempt created = new ExamAttempt(student, exam, Instant.now());
                        em.persist(created);
                        return created;
                    });
            Optional<ExamDto> result = attempt.isOpenAt(Instant.now())
                    ? Optional.of(toDto(attempt)) : Optional.empty();
            tx.commit();
            return result;
        } catch (RuntimeException e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }
    }

    public Optional<ExamDto> getOpenAttempt(String studentCode) {
        EntityManager em = entityManagers.get();
        try {
            return em.createQuery("select a from ExamAttempt a where a.student.studentCode = :code "
                            + "and a.submittedAt is null and a.deadlineAt > :now order by a.startedAt desc", ExamAttempt.class)
                    .setParameter("code", studentCode).setParameter("now", Instant.now())
                    .setMaxResults(1).getResultStream().findFirst().map(this::toDto);
        } finally {
            em.close();
        }
    }

    public Optional<SubmitResult> getCurrentExamResult(String studentCode) {
        EntityManager em = entityManagers.get();
        try {
            return em.createQuery("select s from Submission s where s.student.studentCode = :code "
                            + "and s.exam.active = true order by s.submittedAt desc", Submission.class)
                    .setParameter("code", studentCode).setMaxResults(1)
                    .getResultStream().findFirst().map(this::toResult);
        } finally {
            em.close();
        }
    }

    public void saveDraft(String studentCode, SubmitExamRequest request) {
        EntityManager em = entityManagers.get();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            ExamAttempt attempt = requireAttempt(em, studentCode, request);
            if (!attempt.isOpenAt(Instant.now())) {
                tx.commit();
                return;
            }
            Map<Long, String> answers = validateAnswers(attempt.getExam(), request.getAnswers());
            attempt.setDraftAnswers(JsonUtil.MAPPER.valueToTree(answers).toString());
            tx.commit();
        } catch (RuntimeException e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }
    }

    public void finalizeExpiredAttempts() {
        EntityManager em = entityManagers.get();
        List<Long> ids;
        try {
            ids = em.createQuery("select a.id from ExamAttempt a where a.submittedAt is null "
                            + "and a.deadlineAt <= :now", Long.class)
                    .setParameter("now", Instant.now()).getResultList();
        } finally {
            em.close();
        }
        for (Long id : ids) finalizeExpiredAttempt(id);
    }

    private void finalizeExpiredAttempt(Long id) {
        EntityManager em = entityManagers.get();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            ExamAttempt attempt = em.find(ExamAttempt.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (attempt != null && attempt.getSubmittedAt() == null && !attempt.isOpenAt(Instant.now())) {
                if (findSubmission(em, attempt).isPresent()) attempt.markSubmitted(Instant.now());
                else persistSubmission(em, attempt, readDraft(attempt));
            }
            tx.commit();
        } catch (RuntimeException e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }
    }

    private SubmitResult persistSubmission(EntityManager em, ExamAttempt attempt, Map<Long, String> answers) {
        Exam exam = attempt.getExam();
        int correct = 0;
        Submission submission = new Submission(attempt.getStudent(), exam, 0, LocalDateTime.now());
        for (Question question : exam.getQuestions()) {
            String answer = answers.get(question.getId());
            boolean right = isCorrect(question, answer);
            if (right) correct++;
            submission.addAnswer(new StudentAnswer(question, answer, right));
        }
        int total = exam.getQuestions().size();
        double score = total == 0 ? 0 : Math.round(correct * 1000.0 / total) / 100.0;
        submission.setScore(score);
        em.persist(submission);
        attempt.markSubmitted(Instant.now());
        return new SubmitResult(score, correct, total, "Nộp bài thành công");
    }

    private Optional<Submission> findSubmission(EntityManager em, ExamAttempt attempt) {
        return em.createQuery("select s from Submission s where s.student = :student and s.exam = :exam", Submission.class)
                .setParameter("student", attempt.getStudent()).setParameter("exam", attempt.getExam())
                .setMaxResults(1).getResultStream().findFirst();
    }

    private SubmitResult toResult(Submission submission) {
        int correct = (int) submission.getAnswers().stream().filter(StudentAnswer::isCorrect).count();
        return new SubmitResult(submission.getScore(), correct, submission.getAnswers().size(), "Bài đã được nộp");
    }

    private ExamAttempt requireAttempt(EntityManager em, String studentCode, SubmitExamRequest request) {
        if (request == null || request.getExamId() == null) throw new IllegalArgumentException("Thiếu mã đề thi.");
        return findAttempt(em, studentCode, request.getExamId(), LockModeType.PESSIMISTIC_WRITE)
                .orElseThrow(() -> new IllegalStateException("Sinh viên chưa tham gia bài thi này."));
    }

    private Optional<ExamAttempt> findAttempt(EntityManager em, String code, Long examId, LockModeType lock) {
        return em.createQuery("select a from ExamAttempt a where a.student.studentCode = :code and a.exam.id = :examId", ExamAttempt.class)
                .setParameter("code", code).setParameter("examId", examId)
                .setLockMode(lock).getResultStream().findFirst();
    }

    private Map<Long, String> validateAnswers(Exam exam, Map<Long, String> answers) {
        if (answers == null) return Map.of();
        Set<Long> questionIds = exam.getQuestions().stream().map(Question::getId).collect(Collectors.toSet());
        if (!questionIds.containsAll(answers.keySet())) throw new IllegalArgumentException("Mã câu hỏi không thuộc đề.");
        for (String answer : answers.values())
            if (answer != null && answer.length() > 1000) throw new IllegalArgumentException("Đáp án quá dài.");
        return answers;
    }

    private Map<Long, String> readDraft(ExamAttempt attempt) {
        try {
            return JsonUtil.MAPPER.readValue(attempt.getDraftAnswers(), ANSWER_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không đọc được bài làm đã lưu.", e);
        }
    }

    private ExamDto toDto(ExamAttempt attempt) {
        ExamDto dto = toDto(attempt.getExam());
        long remainingMillis = Math.max(0, attempt.getDeadlineAt().toEpochMilli() - Instant.now().toEpochMilli());
        dto.setDurationSeconds((int) Math.min(Integer.MAX_VALUE, (remainingMillis + 999) / 1000));
        dto.setDeadlineEpochMillis(attempt.getDeadlineAt().toEpochMilli());
        dto.setSavedAnswers(readDraft(attempt));
        return dto;
    }

    private boolean isCorrect(Question q, String answer) {
        if (answer == null) return false;
        String actual = answer.trim().toUpperCase(Locale.ROOT);
        String expected = q.getCorrectAnswer().trim().toUpperCase(Locale.ROOT);
        return actual.equals(expected);
    }

    private ExamDto toDto(Exam exam) {
        List<QuestionDto> questions = new ArrayList<>();
        for (Question q : exam.getQuestions()) {
            List<String> options = new ArrayList<>();
            if (q.getType() == QuestionType.MULTIPLE_CHOICE) {
                options.add("A. " + q.getOptionA());
                options.add("B. " + q.getOptionB());
                options.add("C. " + q.getOptionC());
                options.add("D. " + q.getOptionD());
            }
            questions.add(new QuestionDto(q.getId(), q.getContent(), q.getType().name(), options));
        }
        return new ExamDto(exam.getId(), exam.getTitle(), exam.getDurationMinutes() * 60, questions);
    }
}
