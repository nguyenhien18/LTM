package com.ptit.lanexam.server.service;

import com.ptit.lanexam.client.network.LanClient;
import com.ptit.lanexam.common.*;
import com.ptit.lanexam.common.dto.*;
import com.ptit.lanexam.common.dto.AssignmentMessages.*;
import com.ptit.lanexam.server.entity.*;
import com.ptit.lanexam.server.network.*;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class TeacherAccountIntegrationTest {
    private EntityManagerFactory factory;
    private TeacherAccountService accounts;
    private final List<ExamServer> servers = new ArrayList<>();
    private final List<Peer> clients = new ArrayList<>();
    private static final String PASSWORD = "Matkhau123!";

    @BeforeEach void setUp() {
        factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:teachers_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
        accounts = new TeacherAccountService(factory::createEntityManager);
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin(); em.persist(new Student("ST001", "Nguyễn An", LocalDate.of(2003, 5, 12))); em.getTransaction().commit();
        }
    }
    @AfterEach void tearDown() {
        clients.forEach(Peer::close); servers.forEach(ExamServer::stop);
        if (factory != null) factory.close();
    }

    @Test void firstAccountInheritsLegacyDataAndPasswordsAreSalted() {
        QuestionBankService oldBank = new QuestionBankService(factory::createEntityManager);
        SubjectInfo subject = oldBank.createSubject("Mạng máy tính");
        BankQuestionInfo question = oldBank.createQuestion(choice(subject.id()));
        ExamService oldExams = new ExamService(factory::createEntityManager);
        ExamSummary template = oldExams.createExamFromBank(new BankExamDraft("Đề đã lưu", 20, subject.id(), List.of(question.id())));
        ExamDto round = oldExams.activateExam(template.id());
        oldExams.startAttempt("ST001", round.getId());
        oldExams.submit("ST001", new SubmitExamRequest(round.getId(), Map.of(round.getQuestions().getFirst().getId(), "A")));
        Long homework = new AssignmentService(factory::createEntityManager).create(new AssignmentDraft("Bài cũ", "Nội dung", null, null, null));

        TeacherIdentity first = accounts.create(" GV01 ", "Giáo viên Một", PASSWORD);
        TeacherIdentity second = accounts.create("gv02", "Giáo viên Hai", PASSWORD);
        assertEquals("gv01", first.username());
        assertEquals(first, accounts.authenticate("GV01", PASSWORD).orElseThrow());
        assertTrue(accounts.authenticate("gv01", "SaiMatKhau").isEmpty());
        assertTrue(accounts.authenticate("unknown", PASSWORD).isEmpty());
        assertEquals(List.of(subject.id()), bank(first).listSubjects().stream().map(SubjectInfo::id).toList());
        assertThrows(IllegalArgumentException.class, () -> bank(first).createSubject("MẠNG MÁY TÍNH"));
        assertEquals(template.id(), exams(first).listExams().getFirst().id());
        assertEquals(10.0, exams(first).listResults().getFirst().score());
        assertEquals(homework, assignments(first).listForTeacher().getFirst().id());
        assertTrue(bank(second).listSubjects().isEmpty());
        assertTrue(exams(second).listResults().isEmpty());
        assertTrue(assignments(second).listForTeacher().isEmpty());
        try (EntityManager em = factory.createEntityManager()) {
            String firstHash = em.find(Teacher.class, first.id()).getPasswordHash();
            String secondHash = em.find(Teacher.class, second.id()).getPasswordHash();
            assertTrue(firstHash.startsWith("pbkdf2$600000$"));
            assertFalse(firstHash.contains(PASSWORD)); assertNotEquals(firstHash, secondHash);
            assertEquals(first.id(), em.find(Exam.class, round.getId()).getOwnerTeacherId());
        }
    }

    @Test void resourcesAndResultsArePrivateEvenWhenAnotherTeachersIdsAreKnown() {
        TeacherIdentity a = accounts.create("gv01", "Một", PASSWORD);
        TeacherIdentity b = accounts.create("gv02", "Hai", PASSWORD);
        SubjectInfo subjectA = bank(a).createSubject("Mạng");
        SubjectInfo subjectB = bank(b).createSubject("Mạng");
        BankQuestionInfo questionA = bank(a).createQuestion(choice(subjectA.id()));
        BankQuestionInfo questionB = bank(b).createQuestion(choice(subjectB.id()));
        ExamSummary templateA = exams(a).createExamFromBank(new BankExamDraft("Đề A", 20, subjectA.id(), List.of(questionA.id())));
        ExamSummary templateB = exams(b).createExamFromBank(new BankExamDraft("Đề B", 20, subjectB.id(), List.of(questionB.id())));
        assertEquals(List.of(templateA.id()), exams(a).listExams().stream().map(ExamSummary::id).toList());
        assertEquals(List.of(templateB.id()), exams(b).listExams().stream().map(ExamSummary::id).toList());
        assertTrue(bank(b).listQuestions(subjectA.id(), null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> bank(b).updateQuestion(questionA.id(), choice(subjectB.id())));
        assertThrows(IllegalArgumentException.class, () -> bank(b).createQuestion(choice(subjectA.id())));
        assertThrows(IllegalArgumentException.class, () -> exams(b).createExamFromBank(new BankExamDraft("Sai", 20, subjectA.id(), List.of(questionA.id()))));
        assertThrows(IllegalArgumentException.class, () -> exams(b).activateExam(templateA.id()));
        ExamDto roundA = exams(a).activateExam(templateA.id());
        assertThrows(IllegalArgumentException.class, () -> exams(b).startAttempt("ST001", roundA.getId()));
        assertThrows(IllegalArgumentException.class, () -> exams(b).findOffer("ST001", roundA.getId()));
        exams(a).startAttempt("ST001", roundA.getId());
        assertThrows(IllegalStateException.class, () -> exams(b).submit("ST001", new SubmitExamRequest(roundA.getId(), Map.of())));
        assertThrows(IllegalStateException.class, () -> exams(b).updateAnswers("ST001", new SubmitExamRequest(roundA.getId(), Map.of())));
        exams(a).submit("ST001", new SubmitExamRequest(roundA.getId(), Map.of(roundA.getQuestions().getFirst().getId(), "A")));
        assertEquals(1, exams(a).listResults().size());
        assertTrue(exams(b).listResults().isEmpty()); assertTrue(exams(b).listStudentHistory("ST001").isEmpty());
        assertTrue(exams(b).getExamResult("ST001", roundA.getId()).isEmpty());
        assertTrue(exams(b).getOpenAttempt("ST001").isEmpty());
        Long taskA = assignments(a).create(new AssignmentDraft("Bài A", "Đề A", null, "de.txt", new byte[]{1, 2}));
        AssignmentSubmissionInfo submission = assignments(a).submit("ST001", new SubmitRequest(taskA, "Bài làm", "bai.txt", new byte[]{3, 4}, false));
        assertTrue(assignments(b).listForTeacher().isEmpty()); assertTrue(assignments(b).listForStudent("ST001").isEmpty());
        assertTrue(assignments(b).listSubmissions(taskA).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> assignments(b).downloadAssignment(taskA));
        assertThrows(IllegalArgumentException.class, () -> assignments(b).downloadSubmission(submission.id()));
        assertThrows(IllegalArgumentException.class, () -> assignments(b).downloadForStudent("ST001", new FileRequest(taskA, false)));
        assertThrows(IllegalArgumentException.class, () -> assignments(b).downloadForStudent("ST001", new FileRequest(taskA, true)));
        assertThrows(IllegalArgumentException.class, () -> assignments(b).submit("ST001", new SubmitRequest(taskA, "Sai", null, null, false)));
    }

    @Test void anotherTeacherCannotLoginWhileAnOfflineAttemptIsStillOpen() {
        TeacherIdentity a = accounts.create("gv01", "Một", PASSWORD);
        accounts.create("gv02", "Hai", PASSWORD);
        ExamDto round = round(a, "Đề A");
        exams(a).startAttempt("ST001", round.getId());
        assertTrue(exams(a).hasOpenAttempts());
        assertTrue(accounts.authenticate("gv01", PASSWORD).isPresent());
        assertThrows(IllegalStateException.class, () -> accounts.authenticate("gv02", PASSWORD));
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            em.createQuery("update ExamAttempt a set a.deadlineAt = :past where a.submittedAt is null")
                    .setParameter("past", Instant.now().minusSeconds(5)).executeUpdate();
            em.createQuery("update Exam e set e.deadlineAt = :past where e.active = true")
                    .setParameter("past", Instant.now().minusSeconds(5)).executeUpdate();
            em.getTransaction().commit();
        }
        assertFalse(exams(a).hasOpenAttempts());
        assertTrue(accounts.authenticate("gv02", PASSWORD).isPresent());
        exams(a).finalizeExpiredAttempts();
        assertEquals(1, exams(a).listResults().size());
    }

    @Test void validatesRegistrationAndChangingPasswordRequiresCurrentPassword() {
        TeacherIdentity a = accounts.create("gv01", "Một", PASSWORD);
        assertThrows(IllegalArgumentException.class, () -> accounts.create("GV01", "Trùng", PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> accounts.create("!@#", "Sai", PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> accounts.create("gv02", " ", PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> accounts.create("gv02", "Hai", "123"));
        assertThrows(IllegalArgumentException.class, () -> accounts.changePassword(a.id(), "Wrong", "MatKhauMoi456!"));
        assertTrue(accounts.authenticate("gv01", PASSWORD).isPresent());
        accounts.changePassword(a.id(), PASSWORD, "MatKhauMoi456!");
        assertTrue(accounts.authenticate("gv01", PASSWORD).isEmpty());
        assertTrue(new TeacherAccountService(factory::createEntityManager).authenticate("gv01", "MatKhauMoi456!").isPresent());
    }

    @Test void tcpStudentSeesOnlyCurrentTeachersDataAndSafeLogoutClosesConnections() throws Exception {
        TeacherIdentity a = accounts.create("gv01", "Một", PASSWORD);
        TeacherIdentity b = accounts.create("gv02", "Hai", PASSWORD);
        assignments(a).create(new AssignmentDraft("Bài A", "A", null, null, null));
        assignments(b).create(new AssignmentDraft("Bài B", "B", null, null, null));
        ExamDto roundA = round(a, "Đề A");
        ExamServer first = startServer(exams(a), assignments(a)); first.publishExam(roundA);
        Peer student = login(first);
        assertEquals("Bài A", JsonUtil.payload(student.await(MessageType.ASSIGNMENTS_RESULT), ListResult.class).items().getFirst().title());
        student.send(MessageType.JOIN_EXAM, new JoinExamRequest(roundA.getId())); student.await(MessageType.EXAM_STARTED);
        assertThrows(IllegalStateException.class, first::stopIfIdle); assertTrue(first.isRunning());
        student.close(); // An offline student still owns an open attempt.
        assertThrows(IllegalStateException.class, first::stopIfIdle);
        exams(a).submit("ST001", new SubmitExamRequest(roundA.getId(), Map.of()));
        assertThrows(IllegalStateException.class, first::stopIfIdle);
        expireRound(roundA.getId());
        Peer unauthenticated = connect(first);
        first.stopIfIdle(); assertFalse(first.isRunning());
        assertNotNull(unauthenticated.disconnected.poll(5, TimeUnit.SECONDS));

        ExamDto roundB = round(b, "Đề B");
        ExamServer second = startServer(exams(b), assignments(b)); second.publishExam(roundB);
        Peer current = login(second);
        assertEquals("Bài B", JsonUtil.payload(current.await(MessageType.ASSIGNMENTS_RESULT), ListResult.class).items().getFirst().title());
        current.send(MessageType.HISTORY_REQUEST, null);
        assertTrue(JsonUtil.payload(current.await(MessageType.HISTORY_RESULT), ExamHistoryResponse.class).items().isEmpty());
        current.send(MessageType.JOIN_EXAM, new JoinExamRequest(roundA.getId())); current.await(MessageType.ERROR);
        current.send(MessageType.JOIN_EXAM, new JoinExamRequest(roundB.getId())); current.await(MessageType.EXAM_STARTED);
        current.send(MessageType.SUBMIT_EXAM, new SubmitExamRequest(roundB.getId(), Map.of())); current.await(MessageType.SUBMIT_RESULT);
        assertEquals(1, exams(a).listResults().size()); assertEquals(1, exams(b).listResults().size());
        assertThrows(IllegalStateException.class, second::stopIfIdle);
        expireRound(roundB.getId());
        second.stopIfIdle();
    }

    @Test void joinAndLogoutCannotRaceToStopAnExamThatJustStarted() throws Exception {
        TeacherIdentity a = accounts.create("gv01", "Một", PASSWORD);
        ExamDto round = round(a, "Đề A");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExamService slow = new ExamService(factory::createEntityManager, a.id()) {
            @Override public Optional<ExamDto> startAttempt(String code, Long id) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException ex) { throw new RuntimeException(ex); }
                return super.startAttempt(code, id);
            }
        };
        ExamServer server = startServer(slow, assignments(a)); server.publishExam(round);
        Peer peer = login(server); peer.await(MessageType.ASSIGNMENTS_RESULT);
        peer.send(MessageType.JOIN_EXAM, new JoinExamRequest(round.getId()));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Future<?> stopping = worker.submit(server::stopIfIdle);
            release.countDown(); peer.await(MessageType.EXAM_STARTED);
            ExecutionException error = assertThrows(ExecutionException.class, () -> stopping.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, error.getCause());
            assertTrue(server.isRunning()); assertTrue(exams(a).hasOpenAttempts());
        } finally { release.countDown(); }
    }

    private QuestionBankService bank(TeacherIdentity teacher) { return new QuestionBankService(factory::createEntityManager, teacher.id()); }
    private void expireRound(Long id) {
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            em.createQuery("update Exam e set e.deadlineAt = :past where e.id = :id")
                    .setParameter("past", Instant.now().minusSeconds(1)).setParameter("id", id).executeUpdate();
            em.getTransaction().commit();
        }
    }
    private ExamService exams(TeacherIdentity teacher) { return new ExamService(factory::createEntityManager, teacher.id()); }
    private AssignmentService assignments(TeacherIdentity teacher) { return new AssignmentService(factory::createEntityManager, Clock.systemUTC(), teacher.id()); }
    private static BankQuestionDraft choice(Long subject) {
        return new BankQuestionDraft(subject, 1, new ExamDraft.QuestionDraft("Chọn A", QuestionType.MULTIPLE_CHOICE, "Một", "Hai", "Ba", "Bốn", "A"));
    }
    private ExamDto round(TeacherIdentity teacher, String name) {
        ExamSummary template = exams(teacher).createExam(new ExamDraft(name, 20, List.of(choice(1L).question())));
        return exams(teacher).activateExam(template.id());
    }
    private ExamServer startServer(ExamService exams, AssignmentService tasks) throws Exception {
        ExamServer server = new ExamServer(new SessionRegistry(), new AuthService(factory::createEntityManager), exams, tasks);
        servers.add(server); server.start(0); return server;
    }
    private Peer connect(ExamServer server) throws Exception {
        Peer peer = new Peer(); clients.add(peer); peer.client.connect("127.0.0.1", server.getPort()); return peer;
    }
    private Peer login(ExamServer server) throws Exception {
        Peer peer = connect(server); peer.send(MessageType.LOGIN, new LoginRequest("ST001", "2003-05-12"));
        assertTrue(JsonUtil.payload(peer.await(MessageType.LOGIN_RESULT), LoginResponse.class).isSuccess()); return peer;
    }
    private static final class Peer implements AutoCloseable {
        final LanClient client = new LanClient();
        final BlockingQueue<NetworkMessage> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> disconnected = new LinkedBlockingQueue<>();
        Peer() { client.setMessageListener(messages::add); client.setDisconnectListener(disconnected::add); }
        void send(MessageType type, Object value) { client.send(JsonUtil.message(type, value)); }
        NetworkMessage await(MessageType type) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < end) {
                NetworkMessage message = messages.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertNotNull(message, "Không nhận được " + type);
                if (message.getType() == type) return message;
                if (message.getType() == MessageType.ERROR || message.getType() == MessageType.ASSIGNMENT_ERROR) fail("Server trả lỗi: " + message.getPayload());
            }
            throw new AssertionError("Timeout " + type);
        }
        public void close() { client.close(); }
    }
}
