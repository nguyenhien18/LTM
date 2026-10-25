package com.ptit.lanexam.server.service;

import com.ptit.lanexam.common.dto.ExamDto;
import com.ptit.lanexam.common.dto.ExamOffer;
import com.ptit.lanexam.common.dto.ExamHistoryItem;
import com.ptit.lanexam.common.dto.SubmitExamRequest;
import com.ptit.lanexam.common.dto.SubmitResult;
import com.ptit.lanexam.server.entity.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ExamServiceIntegrationTest {
    private EntityManagerFactory factory;
    private ExamService service;
    private Long examId;
    private Long questionId;

    @BeforeEach
    void setUp() {
        String database = "exam_test_" + UUID.randomUUID().toString().replace("-", "");
        factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:" + database + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "jakarta.persistence.jdbc.user", "sa",
                "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
        service = new ExamService(factory::createEntityManager);
        EntityManager em = factory.createEntityManager();
        try {
            em.getTransaction().begin();
            em.persist(new Student("ST001", "Test Student", LocalDate.of(2003, 5, 12)));
            Exam exam = new Exam("Test Exam", 1, true);
            exam.addQuestion(new Question("Correct option?", QuestionType.MULTIPLE_CHOICE,
                    "Yes", "No", "Maybe", "Unknown", "A"));
            em.persist(exam);
            em.getTransaction().commit();
            examId = exam.getId();
            questionId = exam.getQuestions().getFirst().getId();
        } finally {
            em.close();
        }
    }

    @AfterEach
    void tearDown() {
        if (factory != null) factory.close();
    }

    @Test
    void requiresPublishedAttemptAndRejectsDuplicateGrading() {
        SubmitExamRequest request = new SubmitExamRequest(examId, Map.of(questionId, "A"));
        assertThrows(IllegalStateException.class, () -> service.submit("ST001", request));
        ExamDto first = service.startAttempt("ST001", examId).orElseThrow();
        ExamDto republished = service.startAttempt("ST001", examId).orElseThrow();
        assertEquals(first.getDeadlineEpochMillis(), republished.getDeadlineEpochMillis());

        SubmitResult result = service.submit("ST001", request);
        SubmitResult repeated = service.submit("ST001", new SubmitExamRequest(examId, Map.of(questionId, "B")));
        assertEquals(10.0, result.getScore());
        assertEquals(result.getScore(), repeated.getScore());
        assertTrue(service.startAttempt("ST001", examId).isEmpty());
        assertTrue(service.getOpenAttempt("ST001").isEmpty());
        assertEquals(1L, submissionCount());
    }

    @Test
    void restoresSavedAnswersOnReconnect() {
        service.startAttempt("ST001", examId).orElseThrow();
        service.saveDraft("ST001", new SubmitExamRequest(examId, Map.of(questionId, "B")));
        ExamDto restored = service.getOpenAttempt("ST001").orElseThrow();
        assertEquals("B", restored.getSavedAnswers().get(questionId));
        assertThrows(IllegalArgumentException.class, () -> service.saveDraft("ST001",
                new SubmitExamRequest(examId, Map.of(-1L, "A"))));
    }

    @Test
    void expiryGradesSavedDraftAndIgnoresLateAnswers() {
        EntityManager em = factory.createEntityManager();
        try {
            em.getTransaction().begin();
            Student student = em.createQuery("select s from Student s where s.studentCode = 'ST001'", Student.class)
                    .getSingleResult();
            Exam exam = em.find(Exam.class, examId);
            ExamAttempt expired = new ExamAttempt(student, exam, Instant.now().minusSeconds(120));
            expired.setDraftAnswers("{\"" + questionId + "\":\"B\"}");
            em.persist(expired);
            em.getTransaction().commit();
        } finally {
            em.close();
        }

        service.finalizeExpiredAttempts();
        service.saveDraft("ST001", new SubmitExamRequest(examId, Map.of(questionId, "A")));
        SubmitResult result = service.submit("ST001", new SubmitExamRequest(examId, Map.of(questionId, "A")));
        assertEquals(0.0, result.getScore());
        assertEquals(0.0, service.getCurrentExamResult("ST001").orElseThrow().getScore());
        assertEquals(1L, submissionCount());
    }

    @Test
    void teacherCreatesExamAndReadsSavedScores() {
        ExamDto ongoing = service.startAttempt("ST001", examId).orElseThrow();
        ExamSummary created = service.createExam(newExamDraft());
        assertFalse(created.active());
        assertNotEquals(examId, created.id());
        assertEquals(2, created.questionCount());
        assertEquals(2, service.listExams().size());
        assertEquals(examId, service.getActiveExam().getId());
        assertThrows(IllegalStateException.class, () -> service.activateExam(created.id()));
        assertEquals(examId, service.getActiveExam().getId());
        service.activateExam(examId);
        assertEquals(ongoing.getDeadlineEpochMillis(),
                service.getOpenAttempt("ST001").orElseThrow().getDeadlineEpochMillis());

        service.submit("ST001", new SubmitExamRequest(examId, Map.of(questionId, "A")));
        ExamDto opened = service.activateExam(created.id());
        assertNotEquals(created.id(), opened.getId());
        assertEquals("Teacher exam", service.getActiveExam().getTitle());
        assertEquals(2, opened.getQuestions().size());
        assertTrue(service.listExams().getFirst().active());
        assertTrue(service.findOffer("ST001", opened.getId()).isPresent());

        List<ExamResultRow> results = service.listResults();
        assertEquals(1, results.size());
        assertEquals(examId, results.getFirst().roundId());
        assertEquals("ST001", results.getFirst().studentCode());
        assertEquals("Test Exam", results.getFirst().examTitle());
        assertEquals(10.0, results.getFirst().score());
        assertEquals(1, results.getFirst().correct());
        assertEquals(1, results.getFirst().total());

        ExamDto reopenedOld = service.activateExam(examId);
        assertNotEquals(examId, reopenedOld.getId());
        assertEquals(reopenedOld.getId(), service.getActiveExam().getId());
        assertEquals("Test Exam", reopenedOld.getTitle());
        assertFalse(service.listExams().getFirst().active());
        assertTrue(service.findOffer("ST001", created.id()).isEmpty());
        assertTrue(service.findOffer("ST001", reopenedOld.getId()).isPresent());
    }

    @Test
    void reopeningCompletedExamCreatesNewRoundAndPreservesHistory() {
        ExamDto first = service.activateExam(examId);
        ExamDto firstAttempt = service.startAttempt("ST001", first.getId()).orElseThrow();
        ExamDto republishedWhileOpen = service.activateExam(examId);
        assertEquals(first.getId(), republishedWhileOpen.getId());
        assertEquals(firstAttempt.getDeadlineEpochMillis(),
                service.startAttempt("ST001", first.getId()).orElseThrow().getDeadlineEpochMillis());

        service.submit("ST001", new SubmitExamRequest(first.getId(), Map.of(questionId, "A")));
        ExamDto second = service.activateExam(examId);
        assertNotEquals(first.getId(), second.getId());
        assertEquals(1, service.listExams().size());
        assertTrue(service.listExams().getFirst().active());
        assertEquals(second.getId(), service.activateExam(examId).getId());
        assertTrue(service.findOffer("ST001", second.getId()).isPresent());

        ExamDto secondAttempt = service.startAttempt("ST001", second.getId()).orElseThrow();
        Long secondQuestionId = secondAttempt.getQuestions().getFirst().getId();
        assertNotEquals(questionId, secondQuestionId);
        service.submit("ST001", new SubmitExamRequest(second.getId(), Map.of(secondQuestionId, "B")));
        List<ExamHistoryItem> history = service.listStudentHistory("ST001");
        assertEquals(2, history.size());
        assertEquals(second.getId(), history.getFirst().examId());
        assertEquals(first.getId(), history.get(1).examId());
        assertEquals(0.0, history.getFirst().score());
        assertEquals(10.0, history.get(1).score());
        assertEquals(2L, submissionCount());
        assertEquals(2, service.listResults().size());
    }

    @Test
    void invalidTeacherExamDoesNotChangeActiveExam() {
        assertThrows(IllegalArgumentException.class, () -> service.createExam(
                new ExamDraft("Bad", 20, List.of(new ExamDraft.QuestionDraft("Q", QuestionType.MULTIPLE_CHOICE,
                        null, "B", "C", "D", "A")))));
        assertEquals(examId, service.getActiveExam().getId());
    }

    @Test
    void studentMustJoinPublishedExamAndSeesOnlyOwnHistory() {
        assertTrue(service.findOffer("ST001", null).isEmpty());
        ExamOffer offer = service.findOffer("ST001", examId).orElseThrow();
        assertEquals(examId, offer.examId());
        assertFalse(offer.resumable());
        assertTrue(service.listStudentHistory("ST001").isEmpty());
        assertEquals(0L, attemptCount());

        service.startAttempt("ST001", examId).orElseThrow();
        assertEquals(1L, attemptCount());
        ExamOffer resumed = service.findOffer("ST001", null).orElseThrow();
        assertTrue(resumed.resumable());
        assertEquals(examId, resumed.examId());

        service.submit("ST001", new SubmitExamRequest(examId, Map.of(questionId, "A")));
        assertTrue(service.findOffer("ST001", examId).isEmpty());
        List<ExamHistoryItem> history = service.listStudentHistory("ST001");
        assertEquals(1, history.size());
        assertEquals("Test Exam", history.getFirst().examTitle());
        assertEquals(10.0, history.getFirst().score());
        assertTrue(service.listStudentHistory("ST002").isEmpty());
    }

    private ExamDraft newExamDraft() {
        return new ExamDraft("Teacher exam", 30, List.of(
                new ExamDraft.QuestionDraft("Choose one", QuestionType.MULTIPLE_CHOICE,
                        "One", "Two", "Three", "Four", "B"),
                new ExamDraft.QuestionDraft("Choose language", QuestionType.MULTIPLE_CHOICE,
                        "Java", "Python", "C", "C++", "A")));
    }

    private long submissionCount() {
        EntityManager em = factory.createEntityManager();
        try {
            return em.createQuery("select count(s) from Submission s", Long.class).getSingleResult();
        } finally {
            em.close();
        }
    }

    private long attemptCount() {
        EntityManager em = factory.createEntityManager();
        try {
            return em.createQuery("select count(a) from ExamAttempt a", Long.class).getSingleResult();
        } finally {
            em.close();
        }
    }

    // Allows the same integration cases to run with cached jars when Maven is unavailable.
    public static void main(String[] args) {
        ExamServiceIntegrationTest test = new ExamServiceIntegrationTest();
        try { test.setUp(); test.requiresPublishedAttemptAndRejectsDuplicateGrading(); }
        finally { test.tearDown(); }
        try { test.setUp(); test.restoresSavedAnswersOnReconnect(); }
        finally { test.tearDown(); }
        try { test.setUp(); test.expiryGradesSavedDraftAndIgnoresLateAnswers(); }
        finally { test.tearDown(); }
        try { test.setUp(); test.teacherCreatesExamAndReadsSavedScores(); }
        finally { test.tearDown(); }
        try { test.setUp(); test.invalidTeacherExamDoesNotChangeActiveExam(); }
        finally { test.tearDown(); }
        try { test.setUp(); test.studentMustJoinPublishedExamAndSeesOnlyOwnHistory(); }
        finally { test.tearDown(); }
        try { test.setUp(); test.reopeningCompletedExamCreatesNewRoundAndPreservesHistory(); }
        finally { test.tearDown(); }
        System.out.println("ExamService integration tests passed");
    }
}
