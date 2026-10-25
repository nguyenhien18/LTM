package com.ptit.lanexam.server.service;

import com.ptit.lanexam.common.TransferLimits;
import com.ptit.lanexam.common.dto.AssignmentInfo;
import com.ptit.lanexam.common.dto.AssignmentMessages.*;
import com.ptit.lanexam.common.dto.AssignmentSubmissionInfo;
import com.ptit.lanexam.server.entity.Assignment;
import com.ptit.lanexam.server.entity.AssignmentSubmission;
import com.ptit.lanexam.server.entity.Student;
import com.ptit.lanexam.server.persistence.JpaUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class AssignmentService {
    private final Supplier<EntityManager> entityManagers;
    private final Clock clock;
    private final TeacherScope scope;
    private static final String SUBMISSION_FIELDS =
            "s.id, s.student.studentCode, s.student.fullName, s.content, s.fileName, s.fileSize, s.submittedAt";

    public AssignmentService() { this(JpaUtil::em, Clock.systemUTC()); }
    public AssignmentService(Supplier<EntityManager> entityManagers) { this(entityManagers, Clock.systemUTC()); }
    public AssignmentService(Supplier<EntityManager> entityManagers, Clock clock) {
        this(entityManagers, clock, null);
    }
    public AssignmentService(Supplier<EntityManager> entityManagers, Clock clock, Long teacherId) {
        this.entityManagers = entityManagers;
        this.clock = clock;
        this.scope = new TeacherScope(teacherId);
    }

    public Long create(AssignmentDraft draft) {
        if (draft == null || draft.title() == null || draft.title().isBlank() || draft.title().trim().length() > 200)
            throw new IllegalArgumentException("Tên bài tập phải có từ 1 đến 200 ký tự.");
        String description = text(draft.description());
        TransferLimits.validateAttachment(draft.fileName(), draft.fileData());
        if (description.isEmpty() && draft.fileData() == null)
            throw new IllegalArgumentException("Nhập nội dung bài tập hoặc đính kèm đề bài.");
        if (draft.dueAt() != null && !draft.dueAt().isAfter(clock.instant()))
            throw new IllegalArgumentException("Hạn nộp phải ở tương lai.");

        EntityManager em = entityManagers.get();
        try {
            em.getTransaction().begin();
            Assignment assignment = new Assignment(draft.title().trim(), description, clock.instant(),
                    draft.dueAt(), draft.fileData() == null ? null : draft.fileName(), draft.fileData());
            scope.assign(assignment);
            em.persist(assignment);
            em.getTransaction().commit();
            return assignment.getId();
        } catch (RuntimeException ex) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            throw ex;
        } finally { em.close(); }
    }

    public List<AssignmentInfo> listForTeacher() { return list(null); }

    public List<AssignmentInfo> listForStudent(String studentCode) {
        requireStudentCode(studentCode);
        return list(studentCode);
    }

    private List<AssignmentInfo> list(String studentCode) {
        EntityManager em = entityManagers.get();
        try {
            // Project metadata only: listing assignments must not load every attached BLOB.
            List<Object[]> rows = em.createQuery("select a.id, a.title, a.description, a.createdAt, a.dueAt, "
                    + "a.fileName, a.fileSize from Assignment a where " + scope.predicate("a") + " order by a.id desc", Object[].class).getResultList();
            Map<Long, Long> counts = new HashMap<>();
            for (Object[] row : em.createQuery("select s.assignment.id, count(s) from AssignmentSubmission s "
                    + "where " + scope.predicate("s.assignment") + " group by s.assignment.id", Object[].class).getResultList())
                counts.put((Long) row[0], (Long) row[1]);
            Map<Long, AssignmentSubmissionInfo> mine = new HashMap<>();
            if (studentCode != null) {
                for (Object[] row : em.createQuery("select s.assignment.id, " + SUBMISSION_FIELDS
                        + " from AssignmentSubmission s where s.student.studentCode = :code and " + scope.predicate("s.assignment"), Object[].class)
                        .setParameter("code", studentCode).getResultList()) {
                    mine.put((Long) row[0], submissionInfo(row, 1));
                }
            }
            return rows.stream().map(row -> new AssignmentInfo((Long) row[0], (String) row[1], (String) row[2],
                    (Instant) row[3], (Instant) row[4], (String) row[5], (Long) row[6],
                    counts.getOrDefault((Long) row[0], 0L), mine.get((Long) row[0]))).toList();
        } finally { em.close(); }
    }

    public AssignmentSubmissionInfo submit(String studentCode, SubmitRequest request) {
        requireStudentCode(studentCode);
        if (request == null || request.assignmentId() == null)
            throw new IllegalArgumentException("Chưa chọn bài tập.");
        String content = text(request.content());
        TransferLimits.validateAttachment(request.fileName(), request.fileData());
        EntityManager em = entityManagers.get();
        try {
            em.getTransaction().begin();
            // Serialize submissions for this assignment, including the first submission by a student.
            Assignment assignment = em.find(Assignment.class, request.assignmentId(), LockModeType.PESSIMISTIC_WRITE);
            if (assignment == null) throw new IllegalArgumentException("Không tìm thấy bài tập.");
            scope.require(assignment);
            Instant now = clock.instant();
            if (assignment.getDueAt() != null && !now.isBefore(assignment.getDueAt()))
                throw new IllegalStateException("Bài tập đã hết hạn nộp.");
            Student student = em.createQuery("select s from Student s where s.studentCode = :code", Student.class)
                    .setParameter("code", studentCode).getResultStream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy học viên."));
            AssignmentSubmission submission = em.createQuery("select s from AssignmentSubmission s "
                            + "where s.assignment.id = :id and s.student.id = :student", AssignmentSubmission.class)
                    .setParameter("id", assignment.getId()).setParameter("student", student.getId())
                    .getResultStream().findFirst().orElse(null);
            boolean isNew = submission == null;
            if (isNew) submission = new AssignmentSubmission(assignment, student);
            if (request.fileData() != null) submission.setFile(request.fileName(), request.fileData());
            else if (!request.keepExistingFile()) submission.setFile(null, null);
            if (content.isEmpty() && submission.getFileData() == null)
                throw new IllegalArgumentException("Nhập bài làm hoặc chọn tệp để nộp.");
            submission.update(content, now);
            if (isNew) em.persist(submission);
            em.flush();
            AssignmentSubmissionInfo result = toInfo(submission);
            em.getTransaction().commit();
            return result;
        } catch (RuntimeException ex) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            throw ex;
        } finally { em.close(); }
    }

    public List<AssignmentSubmissionInfo> listSubmissions(Long assignmentId) {
        if (assignmentId == null) throw new IllegalArgumentException("Chưa chọn bài tập.");
        EntityManager em = entityManagers.get();
        try {
            return em.createQuery("select " + SUBMISSION_FIELDS + " from AssignmentSubmission s "
                            + "where s.assignment.id = :id and " + scope.predicate("s.assignment") + " order by s.submittedAt desc", Object[].class)
                    .setParameter("id", assignmentId).getResultList().stream()
                    .map(row -> submissionInfo(row, 0)).toList();
        } finally { em.close(); }
    }

    public FileResult downloadForStudent(String studentCode, FileRequest request) {
        requireStudentCode(studentCode);
        if (request == null || request.assignmentId() == null)
            throw new IllegalArgumentException("Chưa chọn bài tập.");
        EntityManager em = entityManagers.get();
        try {
            if (request.submission()) {
                AssignmentSubmission submission = em.createQuery("select s from AssignmentSubmission s "
                                + "where s.assignment.id = :id and s.student.studentCode = :code and " + scope.predicate("s.assignment"), AssignmentSubmission.class)
                        .setParameter("id", request.assignmentId()).setParameter("code", studentCode)
                        .getResultStream().findFirst().orElseThrow(() -> new IllegalArgumentException("Bạn chưa nộp bài tập này."));
                return file(submission.getAssignment().getId(), true, submission.getFileName(), submission.getFileData());
            }
            Assignment assignment = em.find(Assignment.class, request.assignmentId());
            if (assignment == null) throw new IllegalArgumentException("Không tìm thấy bài tập.");
            scope.require(assignment);
            return file(assignment.getId(), false, assignment.getFileName(), assignment.getFileData());
        } finally { em.close(); }
    }

    public FileResult downloadSubmission(Long submissionId) {
        EntityManager em = entityManagers.get();
        try {
            AssignmentSubmission submission = submissionId == null ? null : em.find(AssignmentSubmission.class, submissionId);
            if (submission == null) throw new IllegalArgumentException("Không tìm thấy bài nộp.");
            scope.require(submission.getAssignment());
            return file(submission.getAssignment().getId(), true, submission.getFileName(), submission.getFileData());
        } finally { em.close(); }
    }

    public FileResult downloadAssignment(Long assignmentId) {
        EntityManager em = entityManagers.get();
        try {
            Assignment assignment = assignmentId == null ? null : em.find(Assignment.class, assignmentId);
            if (assignment == null) throw new IllegalArgumentException("Không tìm thấy bài tập.");
            scope.require(assignment);
            return file(assignment.getId(), false, assignment.getFileName(), assignment.getFileData());
        } finally { em.close(); }
    }

    private static FileResult file(Long id, boolean submission, String name, byte[] data) {
        if (data == null) throw new IllegalArgumentException("Không có tệp đính kèm.");
        return new FileResult(id, submission, name, data);
    }

    private static AssignmentSubmissionInfo submissionInfo(Object[] row, int offset) {
        return new AssignmentSubmissionInfo((Long) row[offset], (String) row[offset + 1], (String) row[offset + 2],
                (String) row[offset + 3], (String) row[offset + 4], (Long) row[offset + 5], (Instant) row[offset + 6]);
    }

    private static AssignmentSubmissionInfo toInfo(AssignmentSubmission submission) {
        return new AssignmentSubmissionInfo(submission.getId(), submission.getStudent().getStudentCode(),
                submission.getStudent().getFullName(), submission.getContent(), submission.getFileName(),
                submission.getFileSize(), submission.getSubmittedAt());
    }

    private static String text(String value) {
        if (value != null && value.length() > TransferLimits.MAX_TEXT_LENGTH)
            throw new IllegalArgumentException("Nội dung không được vượt quá 20.000 ký tự.");
        return value == null ? "" : value.trim();
    }

    private static void requireStudentCode(String code) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Bạn chưa đăng nhập.");
    }
}
