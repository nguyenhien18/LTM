package com.ptit.lanexam.server.service;

import com.ptit.lanexam.common.JsonUtil;
import com.ptit.lanexam.common.MessageType;
import com.ptit.lanexam.common.dto.SubmitExamRequest;
import com.ptit.lanexam.server.entity.*;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class QuestionBankIntegrationTest {
    private EntityManagerFactory factory;
    private QuestionBankService bank;
    private ExamService exams;
    private SubjectInfo networks;
    private SubjectInfo databases;

    @BeforeEach
    void setUp() {
        factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:question_bank_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
        bank = new QuestionBankService(factory::createEntityManager);
        exams = new ExamService(factory::createEntityManager);
        networks = bank.createSubject("Lập trình mạng");
        databases = bank.createSubject("Cơ sở dữ liệu");
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            em.persist(new Student("ST001", "Nguyễn An", LocalDate.of(2003, 5, 12)));
            em.getTransaction().commit();
        }
    }

    @AfterEach
    void tearDown() { if (factory != null) factory.close(); }

    @Test
    void savesMultipleChoiceQuestionsAndFiltersBySubjectAndDifficulty() {
        BankQuestionInfo easy = bank.createQuestion(choice(networks.id(), 1, "Giao thức tin cậy?", "a"));
        BankQuestionInfo hard = bank.createQuestion(choice(networks.id(), 6, "Giao thức không kết nối?", "B"));
        bank.createQuestion(choice(databases.id(), 1, "Khóa định danh một dòng?", "B"));
        QuestionBankService reopened = new QuestionBankService(factory::createEntityManager);
        assertEquals(2, reopened.listQuestions(networks.id(), null).size());
        assertEquals(List.of(easy.id()), reopened.listQuestions(networks.id(), 1).stream().map(BankQuestionInfo::id).toList());
        assertEquals(List.of(hard.id()), reopened.listQuestions(networks.id(), 6).stream().map(BankQuestionInfo::id).toList());
        assertTrue(reopened.listQuestions(networks.id(), 3).isEmpty());
        assertEquals(1, reopened.listQuestions(databases.id(), null).size());
        assertEquals("A", reopened.listQuestions(networks.id(), 1).getFirst().correctAnswer());
        assertEquals(2, reopened.listSubjects().stream().filter(s -> s.id().equals(networks.id())).findFirst().orElseThrow().questionCount());
    }

    @Test
    void validatesDifficultySubjectAndQuestionContentsBeforeSaving() {
        assertThrows(IllegalArgumentException.class, () -> bank.createSubject("  LẬP   TRÌNH MẠNG  "));
        assertThrows(IllegalArgumentException.class, () -> bank.createSubject(" "));
        assertThrows(IllegalArgumentException.class, () -> bank.createSubject("x".repeat(121)));
        for (int difficulty : new int[]{0, 7})
            assertThrows(IllegalArgumentException.class, () -> bank.createQuestion(choice(networks.id(), difficulty, "Nội dung", "A")));
        assertThrows(IllegalArgumentException.class, () -> bank.createQuestion(choice(-1L, 1, "Nội dung", "A")));
        assertThrows(IllegalArgumentException.class, () -> bank.createQuestion(choice(null, 1, "Nội dung", "A")));
        assertThrows(IllegalArgumentException.class, () -> bank.createQuestion(choice(networks.id(), 1, "Nội dung", "E")));
        assertThrows(IllegalArgumentException.class, () -> bank.createQuestion(choice(networks.id(), 1, "Nội dung", " ")));
        assertThrows(IllegalArgumentException.class, () -> bank.createQuestion(choice(networks.id(), 1, "x".repeat(10001), "A")));
        assertThrows(IllegalArgumentException.class, () -> bank.createQuestion(new BankQuestionDraft(networks.id(), 1,
                new ExamDraft.QuestionDraft("Câu thiếu lựa chọn", QuestionType.MULTIPLE_CHOICE, "A", null, "C", "D", "A"))));
        assertTrue(bank.listQuestions(networks.id(), null).isEmpty());
    }

    @Test
    void examUsesChosenQuestionOrderSubjectAndDifficulty() {
        BankQuestionInfo first = bank.createQuestion(choice(networks.id(), 2, "Câu thứ nhất", "A"));
        BankQuestionInfo second = bank.createQuestion(choice(networks.id(), 5, "Câu thứ hai", "B"));
        ExamSummary created = exams.createExamFromBank(new BankExamDraft("Đề mạng", 25, networks.id(), List.of(second.id(), first.id())));
        assertEquals("Lập trình mạng", created.subjectName());
        assertEquals(2, created.questionCount());
        try (EntityManager em = factory.createEntityManager()) {
            Exam template = em.find(Exam.class, created.id());
            assertEquals(networks.id(), template.getSubject().getId());
            assertEquals(List.of(second.id(), first.id()), template.getQuestions().stream().map(Question::getBankQuestionId).toList());
            assertEquals(List.of(5, 2), template.getQuestions().stream().map(Question::getDifficulty).toList());
        }
        var round = exams.activateExam(created.id());
        assertEquals("Lập trình mạng", round.getSubjectName());
        assertEquals(List.of("Câu thứ hai", "Câu thứ nhất"), round.getQuestions().stream().map(q -> q.getContent()).toList());
        assertEquals("Lập trình mạng", exams.findOffer("ST001", round.getId()).orElseThrow().subjectName());
        String json = JsonUtil.toJson(JsonUtil.message(MessageType.EXAM_STARTED, round));
        assertFalse(json.contains("correctAnswer"), "Đáp án đúng không được gửi sang Client.");
    }

    @Test
    void rejectsCrossSubjectDuplicateMissingAndEmptySelectionsWithoutCreatingExam() {
        BankQuestionInfo own = bank.createQuestion(choice(networks.id(), 1, "Mạng", "A"));
        BankQuestionInfo other = bank.createQuestion(choice(databases.id(), 2, "CSDL", "B"));
        assertThrows(IllegalArgumentException.class, () -> exams.createExamFromBank(
                new BankExamDraft("Sai môn", 20, networks.id(), List.of(own.id(), other.id()))));
        assertThrows(IllegalArgumentException.class, () -> exams.createExamFromBank(
                new BankExamDraft("Trùng câu", 20, networks.id(), List.of(own.id(), own.id()))));
        assertThrows(IllegalArgumentException.class, () -> exams.createExamFromBank(
                new BankExamDraft("Mã không tồn tại", 20, networks.id(), List.of(-1L))));
        assertThrows(IllegalArgumentException.class, () -> exams.createExamFromBank(
                new BankExamDraft("Rỗng", 20, networks.id(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> exams.createExamFromBank(
                new BankExamDraft("Thiếu môn", 20, null, List.of(own.id()))));
        assertThrows(IllegalArgumentException.class, () -> exams.createExamFromBank(
                new BankExamDraft("Quá nhiều câu", 20, networks.id(), Collections.nCopies(201, own.id()))));
        assertTrue(exams.listExams().isEmpty());
    }

    @Test
    void editingBankDoesNotChangeExistingExamRoundOrGrading() {
        BankQuestionInfo original = bank.createQuestion(choice(networks.id(), 1, "Câu hỏi ban đầu", "A"));
        ExamSummary created = exams.createExamFromBank(new BankExamDraft("Đề đã chốt", 20, networks.id(), List.of(original.id())));
        bank.updateQuestion(original.id(), choice(networks.id(), 6, "Câu hỏi đã sửa", "B"));
        var round = exams.activateExam(created.id());
        assertEquals("Câu hỏi ban đầu", round.getQuestions().getFirst().getContent());
        try (EntityManager em = factory.createEntityManager()) {
            Question snapshot = em.find(Exam.class, round.getId()).getQuestions().getFirst();
            assertEquals(1, snapshot.getDifficulty());
            assertEquals("A", snapshot.getCorrectAnswer());
            assertEquals(original.id(), snapshot.getBankQuestionId());
        }
        exams.startAttempt("ST001", round.getId()).orElseThrow();
        var result = exams.submit("ST001", new SubmitExamRequest(round.getId(), Map.of(round.getQuestions().getFirst().getId(), "A")));
        assertEquals(10.0, result.getScore());
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            em.createQuery("update Exam e set e.deadlineAt = :past where e.id = :id")
                    .setParameter("past", java.time.Instant.now().minusSeconds(1)).setParameter("id", round.getId()).executeUpdate();
            em.getTransaction().commit();
        }
        var reopened = exams.activateExam(created.id());
        assertNotEquals(round.getId(), reopened.getId());
        assertEquals("Câu hỏi ban đầu", reopened.getQuestions().getFirst().getContent());
        ExamSummary newer = exams.createExamFromBank(new BankExamDraft("Đề sau khi sửa", 20, networks.id(), List.of(original.id())));
        try (EntityManager em = factory.createEntityManager()) {
            Question latest = em.find(Exam.class, newer.id()).getQuestions().getFirst();
            assertEquals("B", latest.getCorrectAnswer());
            assertEquals(6, latest.getDifficulty());
        }
    }

    @Test
    void movingQuestionToAnotherSubjectUpdatesBankAndKeepsOldExamIntact() {
        BankQuestionInfo original = bank.createQuestion(choice(networks.id(), 3, "Một câu hỏi", "A"));
        ExamSummary saved = exams.createExamFromBank(new BankExamDraft("Đề mạng cũ", 20, networks.id(), List.of(original.id())));
        bank.updateQuestion(original.id(), choice(databases.id(), 4, "Câu đã phân loại lại", "B"));
        assertTrue(bank.listQuestions(networks.id(), null).isEmpty());
        assertEquals(databases.id(), bank.listQuestions(databases.id(), null).getFirst().subjectId());
        assertThrows(IllegalArgumentException.class, () -> exams.createExamFromBank(
                new BankExamDraft("Không còn đúng môn", 20, networks.id(), List.of(original.id()))));
        var round = exams.activateExam(saved.id());
        assertEquals(networks.name(), round.getSubjectName());
        assertEquals("Một câu hỏi", round.getQuestions().getFirst().getContent());
    }

    @Test
    void multipleExamsCanSelectTheSameQuestionsWithoutConsumingTheBank() {
        List<BankQuestionInfo> questions = new ArrayList<>();
        for (int level = 1; level <= 6; level++)
            questions.add(bank.createQuestion(choice(networks.id(), level, "Câu hỏi mức " + level, "A")));
        List<Long> firstSelection = List.of(questions.get(0).id(), questions.get(1).id(), questions.get(2).id());
        ExamSummary first = exams.createExamFromBank(new BankExamDraft("Đề 1", 20, networks.id(), firstSelection));

        QuestionBankService reopenedBank = new QuestionBankService(factory::createEntityManager);
        assertEquals(6, reopenedBank.listQuestions(networks.id(), null).size());
        List<Long> secondSelection = List.of(questions.get(1).id(), questions.get(2).id(), questions.get(3).id());
        ExamService reopenedExams = new ExamService(factory::createEntityManager);
        ExamSummary second = reopenedExams.createExamFromBank(new BankExamDraft("Đề 2", 30, networks.id(), secondSelection));
        assertNotEquals(first.id(), second.id());
        assertEquals(Set.of(first.id(), second.id()), reopenedExams.listExams().stream().map(ExamSummary::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(6, reopenedBank.listQuestions(networks.id(), null).size());
        assertEquals(6, reopenedBank.listSubjects().stream().filter(s -> s.id().equals(networks.id())).findFirst().orElseThrow().questionCount());
        try (EntityManager em = factory.createEntityManager()) {
            List<Question> firstQuestions = em.find(Exam.class, first.id()).getQuestions();
            List<Question> secondQuestions = em.find(Exam.class, second.id()).getQuestions();
            assertEquals(firstSelection, firstQuestions.stream().map(Question::getBankQuestionId).toList());
            assertEquals(secondSelection, secondQuestions.stream().map(Question::getBankQuestionId).toList());
            assertEquals(firstQuestions.get(1).getBankQuestionId(), secondQuestions.getFirst().getBankQuestionId());
            assertNotEquals(firstQuestions.get(1).getId(), secondQuestions.getFirst().getId());
        }
    }

    @Test
    void rejectsTextQuestionsAndKeepsLegacyDataOutOfNewExams() {
        BankQuestionDraft unsupported = text(networks.id(), 1, "Tên lớp kết nối TCP?", "Socket");
        assertThrows(IllegalArgumentException.class, () -> bank.createQuestion(unsupported));
        BankQuestionInfo supported = bank.createQuestion(choice(networks.id(), 1, "Giao thức tin cậy?", "A"));
        assertThrows(IllegalArgumentException.class, () -> bank.updateQuestion(supported.id(), unsupported));
        assertThrows(IllegalArgumentException.class, () -> exams.createExam(
                new ExamDraft("Đề không hợp lệ", 20, List.of(unsupported.question()))));

        Long legacyQuestionId;
        Long legacyExamId;
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            BankQuestion legacy = new BankQuestion();
            legacy.update(em.find(Subject.class, networks.id()), 6, "Câu điền cũ", QuestionType.TEXT,
                    null, null, null, null, "Socket");
            em.persist(legacy);
            Exam oldExam = new Exam("Đề cũ", 20, false);
            oldExam.addQuestion(new Question("Câu điền cũ", QuestionType.TEXT, null, null, null, null, "Socket"));
            em.persist(oldExam);
            em.getTransaction().commit();
            legacyQuestionId = legacy.getId();
            legacyExamId = oldExam.getId();
        }
        assertEquals(List.of(supported.id()), bank.listQuestions(networks.id(), null).stream().map(BankQuestionInfo::id).toList());
        assertEquals(1, bank.listSubjects().stream().filter(s -> s.id().equals(networks.id())).findFirst().orElseThrow().questionCount());
        assertThrows(IllegalArgumentException.class, () -> exams.createExamFromBank(
                new BankExamDraft("Không dùng câu cũ", 20, networks.id(), List.of(legacyQuestionId))));
        assertTrue(exams.listExams().isEmpty());
        assertThrows(IllegalStateException.class, () -> exams.activateExam(legacyExamId));
        try (EntityManager em = factory.createEntityManager()) {
            assertNotNull(em.find(BankQuestion.class, legacyQuestionId));
            assertEquals(QuestionType.TEXT, em.find(Exam.class, legacyExamId).getQuestions().getFirst().getType());
        }
    }

    private static BankQuestionDraft choice(Long subject, int difficulty, String content, String answer) {
        return new BankQuestionDraft(subject, difficulty, new ExamDraft.QuestionDraft(content, QuestionType.MULTIPLE_CHOICE,
                "TCP", "UDP", "HTTP", "FTP", answer));
    }
    private static BankQuestionDraft text(Long subject, int difficulty, String content, String answer) {
        return new BankQuestionDraft(subject, difficulty, new ExamDraft.QuestionDraft(content, QuestionType.TEXT, null, null, null, null, answer));
    }
}
