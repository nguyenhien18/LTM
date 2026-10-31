package com.ptit.lanexam.server.service;

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
import java.time.Clock;
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
    public static final int MAX_BANK_EXAM_QUESTIONS = 200;
    private final Supplier<EntityManager> entityManagers;
    private final TeacherScope scope;
    private final Clock clock;
    private final ExamEventService events;
    public ExamEventService events() { return events; }

    private final Object receiptLock = new Object();
    private final Set<RequestReceipt> pendingRequests = new java.util.HashSet<>();

    public Instant currentTime() { return clock.instant(); }

    // Register immediately after a full frame is read, before parsing or waiting
    // for a database slot. The timestamp is always supplied by the Server.
    public RequestReceipt receiveRequest() {
        synchronized (receiptLock) {
            RequestReceipt receipt = new RequestReceipt(clock.instant());
            pendingRequests.add(receipt);
            return receipt;
        }
    }

    public final class RequestReceipt implements AutoCloseable {
        private final Instant receivedAt;
        private String studentCode;
        private Long examId;
        private RequestReceipt(Instant receivedAt) { this.receivedAt = receivedAt; }
        public Instant receivedAt() { return receivedAt; }
        public void bind(String code, Long id) {
            synchronized (receiptLock) {
                studentCode = code; examId = id;
                if (id == null) pendingRequests.remove(this);
            }
        }
        @Override public void close() {
            synchronized (receiptLock) { pendingRequests.remove(this); }
        }
    }

    private boolean hasPendingAnswers(ExamAttempt attempt) {
        synchronized (receiptLock) {
            return pendingRequests.stream().anyMatch(receipt -> receipt.receivedAt.isBefore(attempt.getDeadlineAt())
                    && (receipt.examId == null || receipt.studentCode == null || (receipt.examId.equals(attempt.getExam().getId())
                    && java.util.Objects.equals(receipt.studentCode, attempt.getStudent().getStudentCode()))));
        }
    }

    public void ensureCanStop() {
        try (EntityManager em = entityManagers.get()) {
            Instant deadline = em.createQuery("select max(e.deadlineAt) from Exam e where e.active = true and " + scope.predicate("e"), Instant.class)
                    .getSingleResult();
            if (deadline != null && clock.instant().isBefore(deadline)) {
                int seconds = remainingSeconds(deadline);
                throw new IllegalStateException("Ca thi đang diễn ra, còn " + (seconds / 60) + " phút " + (seconds % 60)
                        + " giây. Kết thúc ca bằng Dừng bài thi trước khi đóng cửa sổ hoặc đăng xuất.");
            }
        }
        if (hasOpenAttempts()) throw new IllegalStateException("Còn sinh viên đang làm bài, kể cả sinh viên đã mất kết nối.");
        finalizeExpiredAttempts();
        try (EntityManager em = entityManagers.get()) {
            long pending = em.createQuery("select count(a) from ExamAttempt a where a.submittedAt is null and " + scope.predicate("a.exam"), Long.class)
                    .getSingleResult();
            if (pending > 0) throw new IllegalStateException("Đang xử lý đáp án nhận trước hạn và lưu điểm. Hãy chờ hoàn tất trước khi dừng Server.");
        }
    }

    public ExamService() { this(JpaUtil::em); }

    public ExamService(Supplier<EntityManager> entityManagers) {
        this(entityManagers, null);
    }
    public ExamService(Supplier<EntityManager> entityManagers, Long teacherId) {
        this(entityManagers, teacherId, Clock.systemUTC());
    }
    public ExamService(Supplier<EntityManager> entityManagers, Long teacherId, Clock clock) {
        events = new ExamEventService(entityManagers, teacherId, clock);
        this.entityManagers = entityManagers;
        this.scope = new TeacherScope(teacherId);
        this.clock = clock;
    }
    public boolean hasOpenAttempts() {
        try (EntityManager em = entityManagers.get()) {
            return em.createQuery("select count(a) from ExamAttempt a where " + scope.predicate("a.exam")
                            + " and a.submittedAt is null and a.deadlineAt > :now", Long.class)
                    .setParameter("now", clock.instant()).getSingleResult() > 0;
        }
    }

    public ExamSummary createExamFromBank(BankExamDraft draft) {
        if (draft == null || draft.title() == null || draft.title().isBlank() || draft.title().trim().length() > 200)
            throw new IllegalArgumentException("Tên đề phải có từ 1 đến 200 ký tự.");
        if (draft.durationMinutes() < 1 || draft.durationMinutes() > 360)
            throw new IllegalArgumentException("Thời gian làm bài phải từ 1 đến 360 phút.");
        if (draft.subjectId() == null) throw new IllegalArgumentException("Chọn môn thi.");
        if (draft.questionIds() == null || draft.questionIds().isEmpty() || draft.questionIds().size() > MAX_BANK_EXAM_QUESTIONS)
            throw new IllegalArgumentException("Chọn từ 1 đến " + MAX_BANK_EXAM_QUESTIONS + " câu hỏi.");
        if (draft.questionIds().stream().anyMatch(java.util.Objects::isNull)
                || draft.questionIds().stream().distinct().count() != draft.questionIds().size())
            throw new IllegalArgumentException("Danh sách có câu hỏi trùng hoặc không hợp lệ.");

        EntityManager em = entityManagers.get();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            Subject subject = em.find(Subject.class, draft.subjectId());
            if (subject == null) throw new IllegalArgumentException("Môn thi không tồn tại.");
            scope.require(subject);
            Map<Long, BankQuestion> available = em.createQuery("select q from BankQuestion q join fetch q.subject "
                            + "where q.id in :ids", BankQuestion.class).setParameter("ids", draft.questionIds())
                    .getResultList().stream().collect(Collectors.toMap(BankQuestion::getId, q -> q));
            Exam exam = new Exam(draft.title().trim(), draft.durationMinutes(), false);
            scope.assign(exam);
            exam.setSubject(subject);
            for (Long id : draft.questionIds()) {
                BankQuestion source = available.get(id);
                if (source == null) throw new IllegalArgumentException("Câu hỏi #" + id + " không còn tồn tại. Hãy làm mới ngân hàng.");
                scope.require(source);
                if (!source.getSubject().getId().equals(subject.getId()))
                    throw new IllegalArgumentException("Mọi câu hỏi trong đề phải thuộc môn thi đã chọn.");
                if (source.getType() != QuestionType.MULTIPLE_CHOICE)
                    throw new IllegalArgumentException("Chỉ chọn câu trắc nghiệm để tạo đề.");
                exam.addQuestion(Question.fromBank(source));
            }
            em.persist(exam);
            em.flush();
            ExamSummary result = new ExamSummary(exam.getId(), exam.getTitle(), exam.getDurationMinutes(),
                    exam.getQuestions().size(), false, subject.getName());
            tx.commit();
            return result;
        } catch (RuntimeException ex) {
            if (tx.isActive()) tx.rollback();
            throw ex;
        } finally { em.close(); }
    }

    public ExamSummary createExam(ExamDraft draft) {
        if (draft == null || draft.title() == null || draft.title().isBlank()
                || draft.title().trim().length() > 200)
            throw new IllegalArgumentException("Tên đề phải có từ 1 đến 200 ký tự.");
        if (draft.durationMinutes() < 1 || draft.durationMinutes() > 360)
            throw new IllegalArgumentException("Thời gian làm bài phải từ 1 đến 360 phút.");
        if (draft.questions() == null || draft.questions().isEmpty())
            throw new IllegalArgumentException("Đề thi cần ít nhất một câu hỏi.");
        for (ExamDraft.QuestionDraft question : draft.questions()) QuestionRules.validate(question);

        EntityManager em = entityManagers.get();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            Exam exam = new Exam(draft.title().trim(), draft.durationMinutes(), false);
            scope.assign(exam);
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

    /** Restore publication without reopening or changing the saved round. */
    public Optional<ExamDto> recoverPublishedExam() {
        try (EntityManager em = entityManagers.get()) {
            return em.createQuery("select e from Exam e where e.active = true and e.deadlineAt > :now and "
                            + scope.predicate("e") + " order by e.id desc", Exam.class)
                    .setParameter("now", clock.instant()).setMaxResults(1).getResultStream().findFirst().map(this::toDto);
        }
    }

    public record RoundProgress(Long roundId, Instant deadline, Map<String, String> states) {
        public RoundProgress { states = Map.copyOf(states); }
        public String state(String code, Instant now) {
            String saved = states.get(code);
            if ("Đã nộp".equals(saved)) return saved;
            if (!now.isBefore(deadline)) return saved == null ? "Vắng thi" : "Đang chấm";
            return saved == null ? "Chưa vào bài" : "Đang làm";
        }
    }

    /** Progress belongs to a class/round, independently of socket sessions. */
    public Optional<RoundProgress> roundProgress(Long classId, Long shiftId) {
        try (EntityManager em = entityManagers.get()) {
            ClassService.requireClass(em, classId, scope);
            if (shiftId != null) ExamShiftService.require(em, classId, shiftId, scope);
            var query = em.createQuery("select e from Exam e where e.courseClass.id = :class and e.templateExamId is not null and "
                    + scope.predicate("e") + (shiftId == null ? " and e.shift is null" : " and e.shift.id = :shift")
                    + " order by e.id desc", Exam.class).setParameter("class", classId).setMaxResults(1);
            if (shiftId != null) query.setParameter("shift", shiftId);
            Exam round = query.getResultStream().findFirst().orElse(null);
            if (round == null || round.getDeadlineAt() == null) return Optional.empty();
            Map<String, String> states = new java.util.HashMap<>();
            em.createQuery("select a.student.studentCode, a.submittedAt from ExamAttempt a where a.exam = :exam", Object[].class)
                    .setParameter("exam", round).getResultList().forEach(row -> states.put((String) row[0], row[1] == null ? "Đang làm" : "Đã nộp"));
            em.createQuery("select s.student.studentCode from Submission s where s.exam = :exam", String.class)
                    .setParameter("exam", round).getResultList().forEach(code -> states.put(code, "Đã nộp"));
            return Optional.of(new RoundProgress(round.getId(), round.getDeadlineAt(), states));
        }
    }

    public List<ClassService.RosterStudent> rosterForRound(Long roundId) {
        try (EntityManager em = entityManagers.get()) {
            Exam round = em.find(Exam.class, roundId);
            scope.require(round);
            var saved = RoundRoster.read(round);
            return saved != null ? saved : RoundRoster.candidates(em, round.getCourseClass(), round.getShift());
        }
    }

    public List<ExamSummary> listExams() {
        EntityManager em = entityManagers.get();
        try {
            Long currentTemplateId = em.createQuery("select e from Exam e where e.active = true and " + scope.predicate("e") + " order by e.id desc", Exam.class)
                    .setMaxResults(1).getResultList().stream().findFirst()
                    .map(e -> e.getTemplateExamId() == null ? e.getId() : e.getTemplateExamId())
                    .orElse(null);
            return em.createQuery("select distinct e from Exam e left join fetch e.questions left join fetch e.subject "
                            + "where " + scope.predicate("e") + " and e.templateExamId is null and not exists "
                            + "(select q.id from Question q where q.exam = e and q.type <> :choice) "
                            + "order by e.id desc", Exam.class)
                    .setParameter("choice", QuestionType.MULTIPLE_CHOICE)
                    .getResultList().stream()
                    .map(e -> new ExamSummary(e.getId(), e.getTitle(), e.getDurationMinutes(),
                            e.getQuestions().size(), e.getId().equals(currentTemplateId), e.getSubjectName()))
                    .toList();
        } finally {
            em.close();
        }
    }

    public ExamDto activateExam(Long examId) {
        return activateExam(examId, null);
    }


    public ExamDto activateExam(Long examId, Long classId) {
        return activateExam(examId, classId, null);
    }

    public ExamDto activateExam(Long examId, Long classId, Long shiftId) {
        return activateExam(examId, classId, shiftId, 1);
    }

    public ExamDto activateExam(Long examId, Long classId, Long shiftId, int variantCount) {
        return activateExam(examId, classId, shiftId, variantCount, null);
    }

    public ExamDto activateExam(Long examId, Long classId, Long shiftId, int variantCount,
                               List<ClassService.RosterStudent> preparedRoster) {
        if (variantCount < 1 || variantCount > ExamVariants.MAX_VARIANTS) throw new IllegalArgumentException("Số mã đề từ 1 đến 8.");
        if (examId == null) throw new IllegalArgumentException("Chưa chọn bài thi.");
        finalizeExpiredAttempts();
        EntityManager em = entityManagers.get();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            if (scope.teacherId() != null) em.find(Teacher.class, scope.teacherId(), LockModeType.PESSIMISTIC_WRITE);
            Exam template = em.find(Exam.class, examId, LockModeType.PESSIMISTIC_WRITE);
            scope.require(template);
            CourseClass courseClass = classId == null ? null : ClassService.requireClass(em, classId, scope);
            if (courseClass != null) em.lock(courseClass, LockModeType.PESSIMISTIC_WRITE);
            ExamShift shift = shiftId == null ? null : ExamShiftService.require(em, classId, shiftId, scope);
            if (courseClass != null) {
                var latestShifts = ExamShiftService.latest(em, classId);
                if (!latestShifts.isEmpty() && (shift == null || latestShifts.stream().noneMatch(s -> s.getId().equals(shiftId))))
                    throw new IllegalArgumentException("Chọn một ca trong danh sách đã chia của lớp.");
            }
            if (courseClass == null && scope.teacherId() != null) throw new IllegalArgumentException("Chọn lớp tham gia ca thi.");
            if (courseClass != null && template.getSubject() != null && !courseClass.getSubject().getId().equals(template.getSubject().getId()))
                throw new IllegalArgumentException("Môn của đề thi phải trùng với môn của lớp.");
            if (template == null || template.getTemplateExamId() != null)
                throw new IllegalArgumentException("Không tìm thấy đề gốc đã chọn.");
            if (template.getQuestions().isEmpty()) throw new IllegalStateException("Bài thi không có câu hỏi.");
            if (template.getQuestions().stream().anyMatch(q -> q.getType() != QuestionType.MULTIPLE_CHOICE))
                throw new IllegalStateException("Đề cũ có câu điền đáp án. Hãy tạo đề mới chỉ gồm câu trắc nghiệm.");
            Exam current = em.createQuery("select e from Exam e where e.active = true and " + scope.predicate("e") + " order by e.id desc", Exam.class)
                    .setMaxResults(1).getResultList().stream().findFirst().orElse(null);
            if (current != null) current = em.find(Exam.class, current.getId(), LockModeType.PESSIMISTIC_WRITE);
            // Older databases did not store the opening time. Preserve the
            // earliest existing start when this legacy round is republished.
            if (current != null && current.getDeadlineAt() == null) {
                Instant firstStart = em.createQuery("select min(a.startedAt) from ExamAttempt a where a.exam = :exam", Instant.class)
                        .setParameter("exam", current).getSingleResult();
                current.openAt(firstStart == null ? clock.instant() : firstStart);
                em.createQuery("update ExamAttempt a set a.deadlineAt = :deadline where a.exam = :exam and a.submittedAt is null")
                        .setParameter("deadline", current.getDeadlineAt()).setParameter("exam", current).executeUpdate();
            }
            boolean sameTemplate = current != null && (examId.equals(current.getId())
                    || examId.equals(current.getTemplateExamId())) && java.util.Objects.equals(classId,
                    current.getCourseClass() == null ? null : current.getCourseClass().getId())
                    && java.util.Objects.equals(shiftId, current.getShift() == null ? null : current.getShift().getId());
            long openAttempts = em.createQuery("select count(a) from ExamAttempt a where a.exam.active = true "
                            + "and " + scope.predicate("a.exam") + " and a.submittedAt is null and a.deadlineAt > :now", Long.class)
                    .setParameter("now", clock.instant()).getSingleResult();
            if (openAttempts > 0 && !sameTemplate)
                throw new IllegalStateException("Còn sinh viên đang làm bài hiện tại. Hãy chờ nộp hoặc hết giờ trước khi đổi bài.");
            if (sameTemplate && clock.instant().isBefore(current.getDeadlineAt())) {
                if (ExamVariants.count(current) != variantCount) throw new IllegalStateException("Ca đã mở với số mã đề khác. Không thể đổi mã trong khi thi.");
                ExamDto unchanged = toDto(current);
                tx.commit();
                return unchanged;
            }
            if (shift != null && em.createQuery("select count(e) from Exam e where e.shift = :shift", Long.class)
                    .setParameter("shift", shift).getSingleResult() > 0)
                throw new IllegalStateException("Ca này đã mở thi. Chọn ca tiếp theo; ca đã thi giữ nguyên đề, danh sách và kết quả.");
            if (current != null && current.getDeadlineAt() != null && clock.instant().isBefore(current.getDeadlineAt()))
                throw new IllegalStateException("Ca thi đang diễn ra. Chờ hết giờ hoặc dùng Dừng bài thi và chờ chấm xong trước khi mở đề khác.");
            long unfinished = em.createQuery("select count(a) from ExamAttempt a where a.submittedAt is null and " + scope.predicate("a.exam"), Long.class)
                    .getSingleResult();
            if (unfinished > 0) throw new IllegalStateException("Đang chấm các bài của ca cũ. Hãy chờ hoàn tất trước khi mở ca mới.");
            List<ClassService.RosterStudent> candidates = courseClass == null ? List.of() : RoundRoster.candidates(em, courseClass, shift);
            if (courseClass != null && candidates.isEmpty()) throw new IllegalStateException("Danh sách thi trống. Nhập sinh viên rồi chuẩn bị lại ca thi.");
            if (preparedRoster != null && !RoundRoster.same(preparedRoster, candidates))
                throw new IllegalStateException("Danh sách thi đã thay đổi. Bấm Chuẩn bị ca thi để kiểm tra lại trước khi bắt đầu.");
            em.createQuery("update Exam e set e.active = false where e.active = true and " + scope.predicate("e")).executeUpdate();
            Exam round = new Exam(template.getTitle(), template.getDurationMinutes(), true);
            round.openAt(clock.instant());
            scope.assign(round);
            round.setSubject(template.getSubject());
            round.setTemplateExamId(template.getId());
            round.setCourseClass(courseClass);
            round.setShift(shift);
            if (courseClass != null) round.setRosterJson(RoundRoster.write(candidates));
            for (Question question : template.getQuestions())
                round.addQuestion(question.copyForRound());
            em.persist(round);
            em.flush();
            if (variantCount > 1) {
                round.setVariantsJson(ExamVariants.create(round, variantCount, candidates.stream().map(ClassService.RosterStudent::code).toList()));
            }
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

    public List<ExamResultRow> listResults() {
        return results(null);
    }
    private List<ExamResultRow> results(Long classId) {
        EntityManager em = entityManagers.get();
        try {
            var query = em.createQuery("select s from Submission s where " + scope.predicate("s.exam")
                    + (classId == null ? "" : " and s.exam.courseClass.id = :group") + " order by s.submittedAt desc", Submission.class);
            if (classId != null) query.setParameter("group", classId);
            return query.getResultList().stream()
                    .map(s -> new ExamResultRow(s.getExam().getId(), s.getStudent().getStudentCode(), s.getStudent().getFullName(),
                            classId == null ? resultTitle(s.getExam()) : displayTitle(s.getExam()), s.getScore(),
                            (int) s.getAnswers().stream().filter(StudentAnswer::isCorrect).count(),
                            s.getAnswers().size(), s.getSubmittedAt(), s.getExam().getCourseClass() == null ? null : s.getExam().getCourseClass().getId(), s.getVariantCode(),
                            s.isAutomaticSubmission() ? ExamResultRow.Status.EXPIRED : ExamResultRow.Status.SUBMITTED))
                    .toList();
        } finally {
            em.close();
        }
    }

    /** Attendance is read-only: absent students never get synthetic attempts or zero-point submissions. */
    public List<ExamResultRow> listRoundResults() {
        try (EntityManager em = entityManagers.get()) {
            var rounds = em.createQuery("select e from Exam e where e.templateExamId is not null and " + scope.predicate("e") + " order by e.id desc", Exam.class).getResultList();
            var submissions = em.createQuery("select s from Submission s where " + scope.predicate("s.exam") + " order by s.submittedAt desc", Submission.class).getResultList();
            record Key(Long round, String student) {}
            Map<Key, Submission> submitted = new java.util.LinkedHashMap<>();
            submissions.forEach(s -> submitted.put(new Key(s.getExam().getId(), s.getStudent().getStudentCode()), s));
            Map<Key, ExamAttempt> attempts = new java.util.HashMap<>();
            if (!rounds.isEmpty()) em.createQuery("select a from ExamAttempt a where a.exam in :rounds", ExamAttempt.class)
                    .setParameter("rounds", rounds).getResultList()
                    .forEach(a -> attempts.put(new Key(a.getExam().getId(), a.getStudent().getStudentCode()), a));
            List<ExamResultRow> rows = new ArrayList<>();
            Instant now = clock.instant();
            for (Exam round : rounds) {
                var roster = RoundRoster.read(round);
                if (roster == null && round.getShift() != null) roster = RoundRoster.candidates(em, round.getCourseClass(), round.getShift());
                // Historical full-class rounds cannot reconstruct an opening roster that was never saved.
                if (roster == null) continue;
                for (var student : roster) {
                    Submission submission = submitted.remove(new Key(round.getId(), student.code()));
                    ExamAttempt attempt = attempts.get(new Key(round.getId(), student.code()));
                    ExamResultRow.Status state = submission != null
                            ? submission.isAutomaticSubmission() ? ExamResultRow.Status.EXPIRED : ExamResultRow.Status.SUBMITTED
                            : round.getDeadlineAt() != null && !now.isBefore(round.getDeadlineAt())
                            ? attempt == null ? ExamResultRow.Status.ABSENT : ExamResultRow.Status.GRADING
                            : attempt == null ? ExamResultRow.Status.NOT_STARTED : ExamResultRow.Status.DOING;
                    rows.add(new ExamResultRow(round.getId(), student.code(), student.fullName(), resultTitle(round),
                            submission == null ? null : submission.getScore(),
                            submission == null ? null : (int) submission.getAnswers().stream().filter(StudentAnswer::isCorrect).count(),
                            round.getQuestions().size(), submission == null ? null : submission.getSubmittedAt(),
                            round.getCourseClass() == null ? null : round.getCourseClass().getId(),
                            submission == null ? ExamVariants.code(round, student.code()) : submission.getVariantCode(), state));
                }
            }
            for (Submission s : submitted.values()) rows.add(new ExamResultRow(s.getExam().getId(), s.getStudent().getStudentCode(), s.getStudent().getFullName(),
                    resultTitle(s.getExam()), s.getScore(), (int) s.getAnswers().stream().filter(StudentAnswer::isCorrect).count(), s.getAnswers().size(),
                    s.getSubmittedAt(), s.getExam().getCourseClass() == null ? null : s.getExam().getCourseClass().getId(), s.getVariantCode(),
                    s.isAutomaticSubmission() ? ExamResultRow.Status.EXPIRED : ExamResultRow.Status.SUBMITTED));
            return List.copyOf(rows);
        }
    }

    public List<ExamHistoryItem> listStudentHistory(String studentCode) {
        EntityManager em = entityManagers.get();
        try {
            return em.createQuery("select s from Submission s where s.student.studentCode = :code "
                            + "and " + scope.predicate("s.exam") + " "
                            + "order by s.submittedAt desc", Submission.class)
                    .setParameter("code", studentCode).getResultList().stream()
                    .map(s -> new ExamHistoryItem(s.getExam().getId(), ExamVariants.title(displayTitle(s.getExam()), s.getVariantCode()), s.getScore(),
                            (int) s.getAnswers().stream().filter(StudentAnswer::isCorrect).count(),
                            s.getAnswers().size(), s.getSubmittedAt(), s.getExam().getCourseClass() == null ? null : s.getExam().getCourseClass().getId()))
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
                            + "and " + scope.predicate("a.exam") + " and a.deadlineAt > :now order by a.startedAt desc", ExamAttempt.class)
                    .setParameter("code", studentCode).setParameter("now", clock.instant())
                    .setMaxResults(1).getResultList().stream().findFirst();
            if (open.isPresent()) {
                ExamAttempt attempt = open.get();
                Exam exam = attempt.getExam();
                int seconds = (int) Math.min(Integer.MAX_VALUE,
                        Math.max(0, (attempt.getDeadlineAt().toEpochMilli() - clock.instant().toEpochMilli() + 999) / 1000));
                return Optional.of(new ExamOffer(exam.getId(), ExamVariants.title(displayTitle(exam), attempt.getVariantCode()), seconds, true, exam.getSubjectName(),
                        attempt.getDeadlineAt().toEpochMilli(), clock.instant().toEpochMilli(),
                        exam.getCourseClass() == null ? null : exam.getCourseClass().displayName(),
                        exam.getCourseClass() == null ? null : exam.getCourseClass().getId()));
            }
            if (publishedExamId == null) return Optional.empty();
            Exam exam = em.find(Exam.class, publishedExamId);
            if (exam == null || !exam.isActive() || exam.getDeadlineAt() == null
                    || !clock.instant().isBefore(exam.getDeadlineAt())) return Optional.empty();
            scope.require(exam);
            if (!RoundRoster.eligible(exam, studentCode)) return Optional.empty();
            if (!ClassService.eligible(em, exam.getCourseClass() == null ? null : exam.getCourseClass().getId(), studentCode, scope)) return Optional.empty();
            if (!ExamShiftService.eligible(em, exam, studentCode)) return Optional.empty();
            if (!ExamVariants.eligible(exam, studentCode)) return Optional.empty();
            long prior = em.createQuery("select count(a) from ExamAttempt a "
                            + "where a.student.studentCode = :code and a.exam = :exam", Long.class)
                    .setParameter("code", studentCode).setParameter("exam", exam).getSingleResult();
            long submitted = em.createQuery("select count(s) from Submission s "
                            + "where s.student.studentCode = :code and s.exam = :exam", Long.class)
                    .setParameter("code", studentCode).setParameter("exam", exam).getSingleResult();
            if (prior > 0 || submitted > 0) return Optional.empty();
            return Optional.of(new ExamOffer(exam.getId(), ExamVariants.title(displayTitle(exam), ExamVariants.code(exam, studentCode)), remainingSeconds(exam.getDeadlineAt()), false, exam.getSubjectName(),
                    exam.getDeadlineAt().toEpochMilli(), clock.instant().toEpochMilli(),
                    exam.getCourseClass() == null ? null : exam.getCourseClass().displayName(),
                    exam.getCourseClass() == null ? null : exam.getCourseClass().getId()));
        } finally {
            em.close();
        }
    }
    public ExamDto getActiveExam() {
        EntityManager em = entityManagers.get();
        try {
            Exam exam = em.createQuery(
                            "select distinct e from Exam e left join fetch e.questions where e.active = true and " + scope.predicate("e") + " order by e.id",
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
        return submit(studentCode, request, clock.instant());
    }

    public SubmitResult submit(String studentCode, SubmitExamRequest request, Instant receivedAt) {
        EntityManager em = entityManagers.get();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            ExamAttempt attempt = requireAttempt(em, studentCode, request);
            Submission previous = findSubmission(em, attempt).orElse(null);
            if (previous != null) {
                if (attempt.getSubmittedAt() == null) attempt.markSubmitted(clock.instant());
                SubmitResult result = toResult(previous);
                tx.commit();
                return result;
            }
            if (!receivedAt.isBefore(attempt.getDeadlineAt()) && hasPendingAnswers(attempt))
                throw new IllegalStateException("Đang xử lý đáp án nhận trước hạn; Server sẽ gửi kết quả khi chấm xong.");
            Map<Long, String> answers = attempt.isOpenAt(receivedAt)
                    ? validateAnswers(attempt.getExam(), request.getAnswers()) : savedAnswers(attempt);
            SubmitResult result = persistSubmission(em, attempt, answers, !receivedAt.isBefore(attempt.getDeadlineAt()));
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
                    .setParameter("code", studentCode).setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult();
            Exam exam = em.find(Exam.class, examId, LockModeType.PESSIMISTIC_WRITE);
            scope.require(exam);
            if (!RoundRoster.eligible(exam, studentCode))
                throw new IllegalArgumentException("Bạn không có tên trong danh sách đã chốt của ca thi này. Hãy chờ lượt thi sau.");
            if (!ExamShiftService.eligible(em, exam, studentCode))
                throw new IllegalArgumentException("Bạn không có tên trong ca thi này. Hãy chờ đúng ca được phân.");
            if (!ExamVariants.eligible(exam, studentCode)) throw new IllegalArgumentException("Bạn không có trong danh sách đã phân mã đề của ca thi này.");
            if (!(exam.getCourseClass() == null && findAttempt(em, studentCode, examId, LockModeType.NONE).isPresent()))
                ClassService.requireEnrollment(em, exam.getCourseClass() == null ? null : exam.getCourseClass().getId(), studentCode, scope);
            if (exam == null || !exam.isActive() || exam.getDurationMinutes() <= 0)
                throw new IllegalStateException("Đề thi không hợp lệ.");
            if (exam.getDeadlineAt() == null || !clock.instant().isBefore(exam.getDeadlineAt())) {
                tx.commit();
                return Optional.empty();
            }
            long priorSubmissions = em.createQuery("select count(s) from Submission s "
                            + "where s.student = :student and s.exam = :exam", Long.class)
                    .setParameter("student", student).setParameter("exam", exam).getSingleResult();
            if (priorSubmissions > 0) {
                tx.commit();
                return Optional.empty();
            }
            ExamAttempt attempt = findAttempt(em, studentCode, examId, LockModeType.PESSIMISTIC_WRITE)
                    .orElseGet(() -> {
                        ExamAttempt created = new ExamAttempt(student, exam, clock.instant());
                        created.setVariantCode(ExamVariants.code(exam, studentCode));
                        em.persist(created);
                        return created;
                    });
            Optional<ExamDto> result = attempt.isOpenAt(clock.instant())
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
                            + "and " + scope.predicate("a.exam") + " and a.submittedAt is null and a.deadlineAt > :now order by a.startedAt desc", ExamAttempt.class)
                    .setParameter("code", studentCode).setParameter("now", clock.instant())
                    .setMaxResults(1).getResultStream().findFirst().map(this::toDto);
        } finally {
            em.close();
        }
    }

    public Optional<SubmitResult> getCurrentExamResult(String studentCode) {
        EntityManager em = entityManagers.get();
        try {
            return em.createQuery("select s from Submission s where s.student.studentCode = :code "
                            + "and " + scope.predicate("s.exam") + " and s.exam.active = true order by s.submittedAt desc", Submission.class)
                    .setParameter("code", studentCode).setMaxResults(1)
                    .getResultStream().findFirst().map(this::toResult);
        } finally {
            em.close();
        }
    }

    public Optional<SubmitResult> getExamResult(String studentCode, Long examId) {
        EntityManager em = entityManagers.get();
        try {
            return em.createQuery("select s from Submission s where s.student.studentCode = :code and s.exam.id = :exam and " + scope.predicate("s.exam"), Submission.class)
                    .setParameter("code", studentCode).setParameter("exam", examId)
                    .getResultStream().findFirst().map(this::toResult);
        } finally { em.close(); }
    }

    public void updateAnswers(String studentCode, SubmitExamRequest request) {
        updateAnswers(studentCode, request, clock.instant());
    }

    public void updateAnswers(String studentCode, SubmitExamRequest request, Instant receivedAt) {
        EntityManager em = entityManagers.get();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            ExamAttempt attempt = requireAttempt(em, studentCode, request);
            if (!attempt.isOpenAt(receivedAt)) {
                tx.commit();
                return;
            }
            Map<Long, String> answers = validateAnswers(attempt.getExam(), request.getAnswers());
            attempt.setSavedAnswersJson(writeAnswers(answers));
            tx.commit();
        } catch (RuntimeException e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }
    }

    /** End only this teacher's selected round; keep its roster, variants and saved answers. */
    public void endExam(Long roundId) {
        if (roundId == null) throw new IllegalArgumentException("Chưa chọn ca đang thi.");
        try (EntityManager em = entityManagers.get()) {
            EntityTransaction tx = em.getTransaction();
            try {
                tx.begin();
                if (scope.teacherId() != null) em.find(Teacher.class, scope.teacherId(), LockModeType.PESSIMISTIC_WRITE);
                Exam round = em.find(Exam.class, roundId, LockModeType.PESSIMISTIC_WRITE);
                scope.require(round);
                if (round.getTemplateExamId() == null || !round.isActive() || round.getDeadlineAt() == null)
                    throw new IllegalStateException("Ca thi này không đang mở.");
                round.endAt(clock.instant());
                em.createQuery("update ExamAttempt a set a.deadlineAt = :deadline where a.exam = :round and a.submittedAt is null and a.deadlineAt > :deadline")
                        .setParameter("deadline", round.getDeadlineAt()).setParameter("round", round).executeUpdate();
                tx.commit();
            } catch (RuntimeException ex) {
                if (tx.isActive()) tx.rollback();
                throw ex;
            }
        }
    }

    public void finalizeExpiredAttempts() {
        EntityManager em = entityManagers.get();
        List<Long> ids;
        try {
            ids = em.createQuery("select a.id from ExamAttempt a where a.submittedAt is null "
                            + "and a.deadlineAt <= :now", Long.class)
                    .setParameter("now", clock.instant()).getResultList();
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
            if (attempt != null && hasPendingAnswers(attempt)) {
                tx.commit();
                return;
            }
            if (attempt != null && attempt.getSubmittedAt() == null && !attempt.isOpenAt(clock.instant())) {
                if (findSubmission(em, attempt).isPresent()) attempt.markSubmitted(clock.instant());
                else persistSubmission(em, attempt, savedAnswers(attempt), true);
            }
            tx.commit();

        } catch (RuntimeException e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }
    }

    private SubmitResult persistSubmission(EntityManager em, ExamAttempt attempt, Map<Long, String> answers, boolean automatic) {
        Exam exam = attempt.getExam();
        int correct = 0;
        Submission submission = new Submission(attempt.getStudent(), exam, 0, LocalDateTime.ofInstant(clock.instant(), java.time.ZoneId.systemDefault()));
        submission.setVariantCode(attempt.getVariantCode());
        submission.setAutomaticSubmission(automatic);
        var variant = ExamVariants.variant(attempt);
        for (Question question : exam.getQuestions()) {
            String answer = answers.get(question.getId());
            boolean right = isCorrect(question, ExamVariants.originalAnswer(variant, question.getId(), answer));
            if (right) correct++;
            submission.addAnswer(new StudentAnswer(question, answer, right));
        }
        int total = exam.getQuestions().size();
        double score = total == 0 ? 0 : Math.round(correct * 1000.0 / total) / 100.0;
        submission.setScore(score);
        em.persist(submission);
        attempt.markSubmitted(clock.instant());
        attempt.setSavedAnswersJson(null);
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

    private static String resultTitle(Exam exam) {
        return exam.getTitle() + (exam.getCourseClass() == null ? "" : " · " + exam.getCourseClass().displayName())
                + (exam.getShift() == null ? "" : " · Ca " + exam.getShift().getNumber());
    }
    private static String displayTitle(Exam exam) {
        return exam.getTitle() + (exam.getShift() == null ? "" : " · Ca " + exam.getShift().getNumber());
    }

    private ExamAttempt requireAttempt(EntityManager em, String studentCode, SubmitExamRequest request) {
        if (request == null || request.getExamId() == null) throw new IllegalArgumentException("Thiếu mã đề thi.");
        return findAttempt(em, studentCode, request.getExamId(), LockModeType.PESSIMISTIC_WRITE)
                .orElseThrow(() -> new IllegalStateException("Sinh viên chưa tham gia bài thi này."));
    }

    private Optional<ExamAttempt> findAttempt(EntityManager em, String code, Long examId, LockModeType lock) {
        return em.createQuery("select a from ExamAttempt a where a.student.studentCode = :code and a.exam.id = :examId and " + scope.predicate("a.exam"), ExamAttempt.class)
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

    private int remainingSeconds(Instant deadline) {
        return (int) Math.min(Integer.MAX_VALUE,
                Math.max(0, (deadline.toEpochMilli() - clock.instant().toEpochMilli() + 999) / 1000));
    }

    private ExamDto toDto(ExamAttempt attempt) {
        ExamDto dto = toDto(attempt.getExam());
        var ordered = ExamVariants.questions(attempt);
        if (ordered != null) dto.setQuestions(ordered);
        dto.setTitle(ExamVariants.title(dto.getTitle(), attempt.getVariantCode()));
        dto.setSavedAnswers(savedAnswers(attempt));
        long remainingMillis = Math.max(0, attempt.getDeadlineAt().toEpochMilli() - clock.instant().toEpochMilli());
        dto.setDurationSeconds((int) Math.min(Integer.MAX_VALUE, (remainingMillis + 999) / 1000));
        dto.setDeadlineEpochMillis(attempt.getDeadlineAt().toEpochMilli());
        dto.setServerTimeEpochMillis(clock.instant().toEpochMilli());
        return dto;
    }

    private static String writeAnswers(Map<Long, String> answers) {
        try { return com.ptit.lanexam.common.JsonUtil.MAPPER.writeValueAsString(answers); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Không lưu được đáp án.", ex); }
    }
    private static Map<Long, String> savedAnswers(ExamAttempt attempt) {
        if (attempt.getSavedAnswersJson() == null || attempt.getSavedAnswersJson().isBlank()) return Map.of();
        try { return com.ptit.lanexam.common.JsonUtil.MAPPER.readValue(attempt.getSavedAnswersJson(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<Long, String>>() {}); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Không đọc được đáp án đã lưu. Liên hệ giảng viên.", ex); }
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
        ExamDto dto = new ExamDto(exam.getId(), displayTitle(exam),
                exam.getDeadlineAt() == null ? exam.getDurationMinutes() * 60 : remainingSeconds(exam.getDeadlineAt()), questions);
        if (exam.getDeadlineAt() != null) dto.setDeadlineEpochMillis(exam.getDeadlineAt().toEpochMilli());
        dto.setServerTimeEpochMillis(clock.instant().toEpochMilli());
        dto.setSubjectName(exam.getSubjectName());
        return dto;
    }
}
