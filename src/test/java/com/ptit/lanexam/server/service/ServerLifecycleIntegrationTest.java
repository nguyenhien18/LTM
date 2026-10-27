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

class ServerLifecycleIntegrationTest {
    private EntityManagerFactory factory;
    private ExamServer server;
    private final TestClock clock = new TestClock();
    private ExamService exams;
    private Long templateId;
    private volatile CountDownLatch receivedFrames;

    @BeforeEach void setUp() {
        factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:lifecycle_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            em.persist(new Student("ST001", "Student", LocalDate.of(2003, 5, 12)));
            em.getTransaction().commit();
        }
        exams = new ExamService(factory::createEntityManager, null, clock) {
            @Override public RequestReceipt receiveRequest() {
                RequestReceipt receipt = super.receiveRequest();
                CountDownLatch latch = receivedFrames;
                if (latch != null) latch.countDown();
                return receipt;
            }
        };
        templateId = exams.createExam(new ExamDraft("One minute", 1, List.of(new ExamDraft.QuestionDraft(
                "Choose A", QuestionType.MULTIPLE_CHOICE, "Correct", "Wrong", "Wrong", "Wrong", "A")))).id();
    }

    private void start(ExamService service) throws Exception {
        server = new ExamServer(new SessionRegistry(), new AuthService(factory::createEntityManager), service,
                new AssignmentService(factory::createEntityManager));
        server.start(0);
    }

    @AfterEach void tearDown() { if (server != null) server.stop(); if (factory != null) factory.close(); }

    @Test void answerReadBeforeDeadlineIsGradedAfterWaitingForARequestSlot() throws Exception {
        queuedAnswersAreGraded(false);
    }

    @Test void disconnectDoesNotDiscardTimelyAnswersAlreadyInTheInboundQueue() throws Exception {
        queuedAnswersAreGraded(true);
    }

    private void queuedAnswersAreGraded(boolean disconnect) throws Exception {
        start(exams);
        ExamDto round = server.openExam(templateId).exam();
        BlockingQueue<NetworkMessage> messages = new LinkedBlockingQueue<>();
        LanClient client = new LanClient(); client.setMessageListener(messages::add);
        var field = ExamServer.class.getDeclaredField("requestSlots"); field.setAccessible(true);
        Semaphore slots = (Semaphore) field.get(server);
        boolean held = false;
        try {
            client.connect("127.0.0.1", server.getPort());
            client.send(JsonUtil.message(MessageType.LOGIN, new LoginRequest("ST001", "2003-05-12")));
            await(messages, MessageType.ASSIGNMENTS_RESULT);
            client.send(JsonUtil.message(MessageType.JOIN_EXAM, new JoinExamRequest(round.getId())));
            await(messages, MessageType.EXAM_STARTED);
            long question = round.getQuestions().getFirst().getId();
            client.send(JsonUtil.message(MessageType.ANSWER_UPDATE, new SubmitExamRequest(round.getId(), Map.of(question, "B"))));
            client.send(JsonUtil.message(MessageType.PING, null)); await(messages, MessageType.PONG);
            slots.acquire(16); held = true;
            clock.now = Instant.ofEpochMilli(round.getDeadlineEpochMillis()).minusMillis(500);
            receivedFrames = new CountDownLatch(2);
            client.send(JsonUtil.message(MessageType.ANSWER_UPDATE, new SubmitExamRequest(round.getId(), Map.of(question, "B"))));
            client.send(JsonUtil.message(MessageType.ANSWER_UPDATE, new SubmitExamRequest(round.getId(), Map.of(question, "A"))));
            assertTrue(receivedFrames.await(3, TimeUnit.SECONDS), "Both complete frames must be read while processing is blocked.");
            if (disconnect) client.close();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!slots.hasQueuedThreads() && System.nanoTime() < end) Thread.sleep(10);
            assertTrue(slots.hasQueuedThreads(), "The frame must already have been read before its deadline.");
            clock.now = Instant.ofEpochMilli(round.getDeadlineEpochMillis()).plusMillis(1);
            exams.finalizeExpiredAttempts();
            assertTrue(exams.getExamResult("ST001", round.getId()).isEmpty());
            assertThrows(IllegalStateException.class, server::stopIfIdle);
            slots.release(16); held = false;
            if (!disconnect) {
                assertEquals(10.0, JsonUtil.payload(await(messages, MessageType.SUBMIT_RESULT), SubmitResult.class).getScore());
            } else {
                long finish = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (exams.getExamResult("ST001", round.getId()).isEmpty() && System.nanoTime() < finish) Thread.sleep(20);
                assertEquals(10.0, exams.getExamResult("ST001", round.getId()).orElseThrow().getScore());
            }
            server.stopIfIdle();
            assertFalse(server.isRunning());
        } finally { if (held) slots.release(16); client.close(); }
    }

    @Test void openingAndStoppingAreSerializedAndRoundBlocksStopWithNoParticipants() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExamService slow = new ExamService(factory::createEntityManager, null, clock) {
            @Override public ExamDto activateExam(Long id) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Not released"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
                return super.activateExam(id);
            }
        };
        start(slow);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ExamServer.Publication> opening = workers.submit(() -> server.openExam(templateId));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Future<?> stopping = workers.submit(server::stopIfIdle);
            assertThrows(TimeoutException.class, () -> stopping.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            ExamDto round = opening.get(5, TimeUnit.SECONDS).exam();
            ExecutionException refused = assertThrows(ExecutionException.class, () -> stopping.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, refused.getCause());
            assertTrue(server.isRunning());
            assertThrows(IllegalStateException.class, server::stopIfIdle);
            assertEquals(round.getDeadlineEpochMillis(), server.openExam(templateId).exam().getDeadlineEpochMillis());
            clock.now = Instant.ofEpochMilli(round.getDeadlineEpochMillis()).plusMillis(1);
            server.stopIfIdle();
        } finally { release.countDown(); }
    }

    @Test void stoppingFirstPreventsOpeningAndDoesNotCreateARoundInDatabase() throws Exception {
        start(exams);
        server.stopIfIdle();
        assertThrows(IllegalStateException.class, () -> server.openExam(templateId));
        try (EntityManager em = factory.createEntityManager()) {
            assertEquals(1L, em.createQuery("select count(e) from Exam e", Long.class).getSingleResult());
        }
    }

    private static NetworkMessage await(BlockingQueue<NetworkMessage> messages, MessageType type) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            NetworkMessage message = messages.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull(message, "No " + type);
            if (message.getType() == MessageType.ERROR) fail(message.getPayload().toString());
            if (message.getType() == type) return message;
        }
        throw new AssertionError("No " + type);
    }

    private static final class TestClock extends Clock {
        volatile Instant now = Instant.now();
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
