package com.ptit.lanexam.server.service;

import com.ptit.lanexam.client.network.LanClient;
import com.ptit.lanexam.common.*;
import com.ptit.lanexam.common.dto.*;
import com.ptit.lanexam.server.entity.*;
import com.ptit.lanexam.server.network.*;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.time.LocalDate;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class SessionModerationIntegrationTest {
    private EntityManagerFactory factory;
    private SessionRegistry registry;
    private AuthService auth;
    private ExamService exams;
    private ExamServer server;
    private final List<Peer> peers = new ArrayList<>();

    @BeforeEach void setUp() throws Exception {
        factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:moderation_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            em.persist(new Student("ST001", "Sinh viên A", LocalDate.of(2003, 5, 12)));
            em.persist(new Student("ST002", "Sinh viên B", LocalDate.of(2003, 5, 12)));
            em.getTransaction().commit();
        }
        startServer();
    }

    private void startServer() throws Exception {
        registry = new SessionRegistry();
        auth = new AuthService(factory::createEntityManager);
        exams = new ExamService(factory::createEntityManager);
        server = new ExamServer(registry, auth, exams, new AssignmentService(factory::createEntityManager));
        server.start(0);
    }

    @AfterEach void tearDown() {
        peers.forEach(Peer::close);
        if (server != null) server.stop();
        if (factory != null) factory.close();
    }

    @Test void loginStatusBelongsToThisSessionAndNewRoundCanBeJoined() throws Exception {
        ExamDto first = publish("Đề 1");
        Peer peer = login("ST001");
        assertEquals(ClientStatus.ONLINE, registry.find("ST001").getStatus());
        peer.join(first.getId());
        assertProgress(ClientStatus.DOING_EXAM, first.getId());
        peer.submit(first);
        assertProgress(ClientStatus.SUBMITTED, first.getId());

        peer.close();
        peer = login("ST001");
        assertProgress(ClientStatus.ONLINE, null);
        assertEquals(1, exams.listStudentHistory("ST001").size());

        ExamDto second = publish("Đề 2");
        peer.await(MessageType.EXAM_AVAILABLE);
        assertProgress(ClientStatus.ONLINE, null);
        peer.join(second.getId());
        assertProgress(ClientStatus.DOING_EXAM, second.getId());
        peer.send(MessageType.SUBMIT_EXAM, new SubmitExamRequest(first.getId(), Map.of()));
        peer.await(MessageType.ERROR);
        assertProgress(ClientStatus.DOING_EXAM, second.getId());
        peer.submit(second);
        assertProgress(ClientStatus.SUBMITTED, second.getId());
        assertEquals(2, exams.listStudentHistory("ST001").size());

        // Same connection: a third round must reset SUBMITTED before the next join.
        ExamDto third = publish("Đề 3");
        peer.await(MessageType.EXAM_AVAILABLE);
        assertProgress(ClientStatus.ONLINE, null);
        peer.join(third.getId());
        assertProgress(ClientStatus.DOING_EXAM, third.getId());

        // Reconnecting mid-exam keeps saved work and only switches to DOING after resume.
        peer.close();
        peer = login("ST001");
        assertProgress(ClientStatus.ONLINE, null);
        peer.join(third.getId());
        assertProgress(ClientStatus.DOING_EXAM, third.getId());
    }

    @Test void warningsAreTargetedAndLockPersistsAcrossServerRestartUntilUnlocked() throws Exception {
        Peer first = login("ST001");
        Peer second = login("ST002");
        server.warnViolation("ST001", "Không trao đổi bài trong giờ thi.");
        assertEquals("Không trao đổi bài trong giờ thi.", first.await(MessageType.VIOLATION_WARNING).getPayload().asText());
        assertTrue(second.messages.stream().noneMatch(m -> m.getType() == MessageType.VIOLATION_WARNING));
        first.send(MessageType.PING, null);
        first.await(MessageType.PONG);

        // Student messages cannot trigger the teacher's moderation actions.
        second.send(MessageType.ACCOUNT_LOCKED, "ST001");
        second.await(MessageType.ERROR);
        assertFalse(auth.isAccountLocked("ST001"));
        assertThrows(IllegalArgumentException.class, () -> server.warnViolation("ST001", " "));
        assertThrows(IllegalArgumentException.class, () -> server.lockAccount("ST001", " "));
        assertFalse(auth.isAccountLocked("ST001"));

        server.lockAccount("ST001", "Trao đổi bài nhiều lần");
        assertTrue(first.await(MessageType.ACCOUNT_LOCKED).getPayload().asText().contains("Trao đổi bài nhiều lần"));
        awaitCondition(() -> registry.find("ST001") == null);
        assertTrue(first.disconnections.isEmpty(), "Thông báo khóa thay cho popup mất kết nối chung.");
        assertTrue(new AuthService(factory::createEntityManager).isAccountLocked("ST001"));
        assertEquals("Trao đổi bài nhiều lần", auth.listLockedAccounts().getFirst().reason());
        second.send(MessageType.PING, null);
        second.await(MessageType.PONG);

        Peer denied = connect();
        denied.sendLogin("ST001");
        LoginResponse failure = JsonUtil.payload(denied.await(MessageType.LOGIN_RESULT), LoginResponse.class);
        assertFalse(failure.isSuccess());
        assertTrue(failure.getMessage().contains("khóa"));

        server.stop();
        startServer();
        Peer afterRestart = connect();
        afterRestart.sendLogin("ST001");
        assertFalse(JsonUtil.payload(afterRestart.await(MessageType.LOGIN_RESULT), LoginResponse.class).isSuccess());
        auth.setAccountLocked("ST001", false, null);
        assertTrue(auth.listLockedAccounts().isEmpty());
        login("ST001");
        assertProgress(ClientStatus.ONLINE, null);
    }

    @Test void timeoutUpdatesLiveStatusAndLockPreservesLastSavedDraft() throws Exception {
        ExamDto round = publish("Đề hết giờ");
        Peer peer = login("ST001");
        peer.join(round.getId());
        peer.send(MessageType.SAVE_DRAFT, new SubmitExamRequest(round.getId(), Map.of(round.getQuestions().getFirst().getId(), "A")));
        peer.send(MessageType.PING, null);
        peer.await(MessageType.PONG); // All earlier requests, including the draft, have completed.
        expireAttempt("ST001");
        peer.await(MessageType.SUBMIT_RESULT);
        assertProgress(ClientStatus.SUBMITTED, round.getId());
        assertEquals(10.0, exams.getExamResult("ST001", round.getId()).orElseThrow().getScore());

        ExamDto next = publish("Đề bị khóa giữa giờ");
        peer.await(MessageType.EXAM_AVAILABLE);
        peer.join(next.getId());
        peer.send(MessageType.SAVE_DRAFT, new SubmitExamRequest(next.getId(), Map.of(next.getQuestions().getFirst().getId(), "A")));
        peer.send(MessageType.PING, null);
        peer.await(MessageType.PONG);
        server.lockAccount("ST001", "Vi phạm");
        peer.await(MessageType.ACCOUNT_LOCKED);
        expireAttempt("ST001");
        exams.finalizeExpiredAttempts();
        assertEquals(10.0, exams.getExamResult("ST001", next.getId()).orElseThrow().getScore());
    }

    @Test void schemaUpdateKeepsExistingStudentsUnlocked() throws Exception {
        String url = "jdbc:h2:mem:existing_students_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.execute("create table students (id bigint auto_increment primary key, student_code varchar(30) not null unique, "
                    + "full_name varchar(120) not null, birth_date date not null)");
            sql.execute("insert into students(student_code, full_name, birth_date) values ('OLD001', 'Existing student', '2003-05-12')");
        }
        try (EntityManagerFactory upgraded = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver", "jakarta.persistence.jdbc.url", url,
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "update", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            AuthService existingAccounts = new AuthService(upgraded::createEntityManager);
            Student old = existingAccounts.authenticate("OLD001", "2003-05-12").orElseThrow();
            assertFalse(old.isAccountLocked());
            assertFalse(existingAccounts.isAccountLocked("OLD001"));
            existingAccounts.setAccountLocked("OLD001", true, "Vi phạm");
            assertTrue(existingAccounts.isAccountLocked("OLD001"));
        } finally {
            try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
                sql.execute("drop all objects");
            }
        }
    }

    private ExamDto publish(String title) {
        ExamSummary template = exams.createExam(new ExamDraft(title, 20, List.of(new ExamDraft.QuestionDraft(
                "Chọn A", QuestionType.MULTIPLE_CHOICE, "Một", "Hai", "Ba", "Bốn", "A"))));
        ExamDto round = exams.activateExam(template.id());
        server.publishExam(round);
        return round;
    }

    private void expireAttempt(String code) {
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            em.createQuery("update ExamAttempt a set a.deadlineAt = :past where a.student.studentCode = :code and a.submittedAt is null")
                    .setParameter("past", Instant.now().minusSeconds(2)).setParameter("code", code).executeUpdate();
            em.getTransaction().commit();
        }
    }

    private void assertProgress(ClientStatus status, Long examId) {
        ClientSessionInfo info = registry.find("ST001").info();
        assertEquals(status.name(), info.status());
        assertEquals(examId, info.examId());
    }

    private Peer connect() throws Exception {
        Peer peer = new Peer();
        peers.add(peer);
        peer.client.connect("127.0.0.1", server.getPort());
        return peer;
    }

    private Peer login(String code) throws Exception {
        Peer peer = connect();
        peer.sendLogin(code);
        assertTrue(JsonUtil.payload(peer.await(MessageType.LOGIN_RESULT), LoginResponse.class).isSuccess());
        peer.await(MessageType.ASSIGNMENTS_RESULT); // Login bootstrap is complete.
        return peer;
    }

    private static void awaitCondition(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        fail("Trạng thái phiên không được cập nhật đúng hạn.");
    }

    private static final class Peer implements AutoCloseable {
        final LanClient client = new LanClient();
        final BlockingQueue<NetworkMessage> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> disconnections = new LinkedBlockingQueue<>();
        Peer() {
            client.setMessageListener(messages::add);
            client.setDisconnectListener(disconnections::add);
        }
        void send(MessageType type, Object payload) { client.send(JsonUtil.message(type, payload)); }
        void sendLogin(String code) { send(MessageType.LOGIN, new LoginRequest(code, "2003-05-12")); }
        void join(Long id) throws Exception {
            send(MessageType.JOIN_EXAM, new JoinExamRequest(id));
            await(MessageType.EXAM_STARTED);
        }
        void submit(ExamDto exam) throws Exception {
            send(MessageType.SUBMIT_EXAM, new SubmitExamRequest(exam.getId(), Map.of(exam.getQuestions().getFirst().getId(), "A")));
            await(MessageType.SUBMIT_RESULT);
            await(MessageType.EXAM_AVAILABLE);
        }
        NetworkMessage await(MessageType type) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < end) {
                NetworkMessage message = messages.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertNotNull(message, "Không nhận được " + type);
                if (message.getType() == type) return message;
                if (message.getType() == MessageType.ERROR || message.getType() == MessageType.ASSIGNMENT_ERROR)
                    fail("Server trả lỗi: " + message.getPayload());
            }
            throw new AssertionError("Timeout: " + type);
        }
        public void close() { client.close(); }
    }
}
