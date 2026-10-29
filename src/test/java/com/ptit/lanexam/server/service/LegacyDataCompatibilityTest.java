package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.*;
import com.ptit.lanexam.server.legacy.entity.*;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Old data remains readable even though the practical workflow has been retired. */
class LegacyDataCompatibilityTest {
    @Test void teacherRegistrationPreservesOldPracticalFilesGradesAndTextQuestions() {
        try (var factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:legacy_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            Long submissionId, examId;
            try (var em = factory.createEntityManager()) {
                em.getTransaction().begin();
                var student = new Student("OLD001", "Sinh viên cũ", LocalDate.of(2003, 5, 12)); em.persist(student);
                var task = new Assignment("Bài cũ", "Nội dung cũ", Instant.now(), null, "de.txt", new byte[]{1, 2}); em.persist(task);
                var submission = new AssignmentSubmission(task, student);
                submission.update("Bài đã nộp", Instant.now()); submission.setFile("bai.zip", new byte[]{3, 4});
                submission.grade(8.5, "Đã chấm", Instant.now()); em.persist(submission);
                var exam = new Exam("Đề điền cũ", 20, false);
                exam.addQuestion(new Question("Điền giao thức", QuestionType.TEXT, null, null, null, null, "TCP"));
                em.persist(exam); em.flush(); submissionId = submission.getId(); examId = exam.getId(); em.getTransaction().commit();
            }
            var teacher = new TeacherAccountService(factory::createEntityManager).create("gvnew", "Giảng viên mới", "Matkhau123!");
            assertTrue(new ExamService(factory::createEntityManager, teacher.id()).listExams().isEmpty());
            try (var em = factory.createEntityManager()) {
                var saved = em.find(AssignmentSubmission.class, submissionId);
                assertEquals("Bài đã nộp", saved.getContent()); assertEquals(8.5, saved.getScore());
                assertArrayEquals(new byte[]{3, 4}, saved.getFileData());
                assertArrayEquals(new byte[]{1, 2}, saved.getAssignment().getFileData());
                assertEquals(QuestionType.TEXT, em.find(Exam.class, examId).getQuestions().getFirst().getType());
            }
        }
    }
}
