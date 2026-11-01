package com.ptit.lanexam.server.service;

import com.ptit.lanexam.client.network.LanClient;
import com.ptit.lanexam.common.*;
import com.ptit.lanexam.common.dto.*;
import com.ptit.lanexam.server.entity.*;
import com.ptit.lanexam.server.network.*;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ExamExpiryScopeIntegrationTest {
    private EntityManagerFactory factory;
    private final TestClock clock = new TestClock();
    private final List<ExamServer> servers = new ArrayList<>();
    private volatile CountDownLatch receivedFrames;
    private Long firstTeacher, secondTeacher;
    private record Round(ExamService exams, ExamDto exam) {
        SubmitExamRequest answer(String choice) {
            return new SubmitExamRequest(exam.getId(), Map.of(exam.getQuestions().getFirst().getId(), choice));
        }
        Optional<SubmitResult> result() { return exams.getExamResult("ST001", exam.getId()); }
    }

    @BeforeEach void setup() {
        factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:expiry_scope_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
        var accounts = new TeacherAccountService(factory::createEntityManager);
        firstTeacher = accounts.create("gvfirst", "Giảng viên A", "Matkhau123!").id();
        secondTeacher = accounts.create("gvsecond", "Giảng viên B", "Matkhau123!").id();
        try (var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            em.persist(new Student("ST001", "Sinh viên", LocalDate.of(2003, 5, 12)));
            em.getTransaction().commit();
        }
    }

    @AfterEach void cleanup() {
        servers.forEach(ExamServer::stop);
        if (factory != null) factory.close();
    }

    private Round round(Long teacherId) {
        var exams = new ExamService(factory::createEntityManager, teacherId, clock) {
            @Override public RequestReceipt receiveRequest() {
                var receipt = super.receiveRequest();
                var latch = receivedFrames; if (latch != null) latch.countDown();
                return receipt;
            }
        };
        Long classId = null;
        if (teacherId != null) {
            var subject = new QuestionBankService(factory::createEntityManager, teacherId).createSubject("Mạng");
            var classes = new ClassService(factory::createEntityManager, teacherId);
            classId = classes.create("LTM", "Lớp thử nghiệm", "HK1", subject.id()).id();
            classes.importRoster(classId, RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n1,ST001,Sinh viên,12/05/2003,C1"));
        }
        var template = exams.createExam(new ExamDraft("Đề một phút", 1, List.of(new ExamDraft.QuestionDraft(
                "Chọn A", QuestionType.MULTIPLE_CHOICE, "Đúng", "Sai B", "Sai C", "Sai D", "A"))));
        var exam = exams.activateExam(template.id(), classId);
        exams.startAttempt("ST001", exam.getId()).orElseThrow();
        return new Round(exams, exam);
    }

    @Test void expiryOnlyGradesOwnTeacherAndKeepsLegacyAttemptsSeparate() {
        var first = round(firstTeacher); var second = round(secondTeacher); var legacy = round(null);
        for (var r : List.of(first, second, legacy)) r.exams().updateAnswers("ST001", r.answer("A"));
        clock.now = clock.now.plusSeconds(61);

        first.exams().finalizeExpiredAttempts();
        assertEquals(10.0, first.result().orElseThrow().getScore());
        assertTrue(second.result().isEmpty(), "Teacher A must not grade teacher B's attempt");
        assertTrue(legacy.result().isEmpty(), "Teacher A must not grade unowned legacy data");
        legacy.exams().finalizeExpiredAttempts();
        assertEquals(10.0, legacy.result().orElseThrow().getScore());
        assertTrue(second.result().isEmpty(), "An unscoped legacy service must not grade owned attempts");
        second.exams().finalizeExpiredAttempts();
        assertEquals(10.0, second.result().orElseThrow().getScore());
        for (var r : List.of(first, second, legacy)) r.exams().finalizeExpiredAttempts();
        try (var em = factory.createEntityManager()) {
            assertEquals(3L, em.createQuery("select count(s) from Submission s", Long.class).getSingleResult());
        }
    }

    @Test void anotherTeacherCannotBypassTheOwnersTimelyQueuedAnswer() {
        var first = round(firstTeacher); var second = round(secondTeacher);
        first.exams().updateAnswers("ST001", first.answer("A"));
        try (var receipt = second.exams().receiveRequest()) {
            receipt.bind("ST001", second.exam().getId());
            clock.now = clock.now.plusSeconds(61);
            first.exams().finalizeExpiredAttempts();
            assertEquals(10.0, first.result().orElseThrow().getScore());
            assertTrue(second.result().isEmpty(), "Only the owner can coordinate its pending answers and expiry");
            second.exams().finalizeExpiredAttempts();
            assertTrue(second.result().isEmpty(), "The owner must still wait for its timely queued answer");
            second.exams().updateAnswers("ST001", second.answer("A"), receipt.receivedAt());
        }
        second.exams().finalizeExpiredAttempts();
        assertEquals(10.0, second.result().orElseThrow().getScore());
    }

    @Test void twoTcpServersSharingDatabaseWaitForTheirOwnAnswersBeforeGrading() throws Exception {
        var first = round(firstTeacher); var second = round(secondTeacher);
        first.exams().updateAnswers("ST001", first.answer("A"));
        ExamServer a = server(firstTeacher, first.exams()), b = server(secondTeacher, second.exams());
        var messages = new LinkedBlockingQueue<NetworkMessage>();
        var peer = new LanClient(); peer.setMessageListener(messages::add);
        var slotsField = ExamServer.class.getDeclaredField("requestSlots"); slotsField.setAccessible(true);
        var slots = (Semaphore) slotsField.get(b);
        boolean held = false;
        try {
            peer.connect("127.0.0.1", b.getPort());
            peer.send(JsonUtil.message(MessageType.LOGIN, new LoginRequest("ST001", "2003-05-12")));
            assertTrue(JsonUtil.payload(await(messages, MessageType.LOGIN_RESULT), LoginResponse.class).isSuccess());
            peer.send(JsonUtil.message(MessageType.JOIN_EXAM, new JoinExamRequest(second.exam().getId())));
            await(messages, MessageType.EXAM_STARTED);
            slots.acquire(16); held = true;
            clock.now = Instant.ofEpochMilli(second.exam().getDeadlineEpochMillis()).minusMillis(500);
            receivedFrames = new CountDownLatch(1);
            peer.send(JsonUtil.message(MessageType.ANSWER_UPDATE, second.answer("A")));
            assertTrue(receivedFrames.await(3, TimeUnit.SECONDS), "B must read the complete answer before the deadline");
            long waitingUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!slots.hasQueuedThreads() && System.nanoTime() < waitingUntil) Thread.sleep(10);
            assertTrue(slots.hasQueuedThreads(), "B's answer processing must be waiting for a request slot");
            clock.now = Instant.ofEpochMilli(second.exam().getDeadlineEpochMillis()).plusMillis(1);
            first.exams().finalizeExpiredAttempts();
            second.exams().finalizeExpiredAttempts();
            assertEquals(10.0, first.result().orElseThrow().getScore());
            assertTrue(second.result().isEmpty(), "A's expiry must not steal B's pending attempt");
            // Allow the scheduled expiry workers to run while the answer is still queued.
            Thread.sleep(1200);
            assertTrue(second.result().isEmpty(), "Both scheduled workers must preserve B's pending answer");
            slots.release(16); held = false;
            assertEquals(10.0, JsonUtil.payload(await(messages, MessageType.SUBMIT_RESULT), SubmitResult.class).getScore());
            assertEquals(10.0, second.result().orElseThrow().getScore());
            peer.send(JsonUtil.message(MessageType.PING, null)); await(messages, MessageType.PONG);
            assertTrue(a.isRunning()); assertTrue(b.isRunning());
        } finally { if (held) slots.release(16); peer.close(); }
    }

    private ExamServer server(Long teacherId, ExamService exams) throws Exception {
        var server = new ExamServer(new SessionRegistry(), new AuthService(factory::createEntityManager, teacherId), exams);
        servers.add(server); server.start(0); return server;
    }

    private static NetworkMessage await(BlockingQueue<NetworkMessage> messages, MessageType type) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            var message = messages.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull(message, "Missing " + type);
            if (message.getType() == MessageType.ERROR) fail(message.getPayload().toString());
            if (message.getType() == type) return message;
        }
        throw new AssertionError("Missing " + type);
    }

    private static final class TestClock extends Clock {
        volatile Instant now = Instant.now();
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
