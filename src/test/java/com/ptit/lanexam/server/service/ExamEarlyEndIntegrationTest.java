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

class ExamEarlyEndIntegrationTest {
    private EntityManagerFactory factory;
    private ExamService exams;
    private ExamServer server;
    private Long teacherId, classId, templateId;
    private final TestClock clock = new TestClock();

    @BeforeEach void setup() throws Exception {
        factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:early_end_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
        teacherId = new TeacherAccountService(factory::createEntityManager).create("teacher", "Teacher", "Matkhau123!").id();
        var bank = new QuestionBankService(factory::createEntityManager, teacherId);
        var subject = bank.createSubject("Network");
        var classes = new ClassService(factory::createEntityManager, teacherId);
        classId = classes.create("LTM", "Class", "HK1", subject.id()).id();
        classes.importRoster(classId, RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n1,ST001,An,12/05/2003,C1\n2,ST002,Bình,12/05/2003,C1\n3,ST003,Cường,12/05/2003,C1"));
        exams = new ExamService(factory::createEntityManager, teacherId, clock);
        templateId = exams.createExam(new ExamDraft("Exam", 20, List.of(new ExamDraft.QuestionDraft(
                "Choose A", QuestionType.MULTIPLE_CHOICE, "Right", "Wrong", "Wrong", "Wrong", "A")))).id();
        server = new ExamServer(new SessionRegistry(), new AuthService(factory::createEntityManager, teacherId), exams);
        server.start(0);
    }
    @AfterEach void cleanup() { if (server != null) server.stop(); if (factory != null) factory.close(); }

    @Test void endingGradesOnlineAndOfflineKeepsConnectionsAndAllowsTheNextRound() throws Exception {
        ExamDto round = server.openExam(templateId, classId).exam();
        var messages = new LinkedBlockingQueue<NetworkMessage>();
        LanClient online = new LanClient(); online.setMessageListener(messages::add);
        try {
            online.connect("127.0.0.1", server.getPort());
            online.send(JsonUtil.message(MessageType.LOGIN, new LoginRequest("ST001", "2003-05-12")));
            await(messages, MessageType.LOGIN_RESULT);
            online.send(JsonUtil.message(MessageType.JOIN_EXAM, new JoinExamRequest(round.getId())));
            await(messages, MessageType.EXAM_STARTED);
            long question = round.getQuestions().getFirst().getId();
            online.send(JsonUtil.message(MessageType.ANSWER_UPDATE, new SubmitExamRequest(round.getId(), Map.of(question, "A"))));
            online.send(JsonUtil.message(MessageType.PING, null)); await(messages, MessageType.PONG);
            exams.startAttempt("ST002", round.getId());
            exams.updateAnswers("ST002", new SubmitExamRequest(round.getId(), Map.of(question, "A")));
            clock.now = clock.now.plusSeconds(5);
            server.endExam(round.getId());
            assertEquals(10.0, JsonUtil.payload(await(messages, MessageType.SUBMIT_RESULT), SubmitResult.class).getScore());
            assertEquals(10.0, exams.getExamResult("ST002", round.getId()).orElseThrow().getScore());
            assertTrue(exams.listRoundResults().stream().anyMatch(row -> row.studentCode().equals("ST003") && row.status() == ExamResultRow.Status.ABSENT));
            assertTrue(exams.findOffer("ST003", round.getId()).isEmpty());
            assertTrue(exams.startAttempt("ST003", round.getId()).isEmpty());
            assertEquals(10.0, exams.submit("ST002", new SubmitExamRequest(round.getId(), Map.of(question, "B"))).getScore());
            server.endExam(round.getId()); // repeated stop must preserve results
            assertEquals(2L, exams.listRoundResults().stream().filter(row -> row.score() != null).count());
            assertTrue(server.isRunning());
            online.send(JsonUtil.message(MessageType.PING, null)); await(messages, MessageType.PONG);
            assertTrue(new ExamService(factory::createEntityManager, teacherId, clock).recoverPublishedExam().isEmpty());
            ExamDto next = server.openExam(templateId, classId).exam();
            assertNotEquals(round.getId(), next.getId());
            assertTrue(exams.findOffer("ST003", next.getId()).isPresent());
        } finally { online.close(); }
    }

    @Test void endingWaitsForTimelyQueuedAnswersAndRejectsAnotherTeachersRound() throws Exception {
        ExamDto round = server.openExam(templateId, classId).exam();
        exams.startAttempt("ST001", round.getId());
        long question = round.getQuestions().getFirst().getId();
        var other = new TeacherAccountService(factory::createEntityManager).create("other", "Other", "Matkhau123!");
        assertThrows(IllegalArgumentException.class, () -> new ExamService(factory::createEntityManager, other.id(), clock).endExam(round.getId()));
        try (var receipt = exams.receiveRequest()) {
            receipt.bind("ST001", round.getId());
            clock.now = clock.now.plusSeconds(2);
            server.endExam(round.getId());
            assertTrue(exams.getExamResult("ST001", round.getId()).isEmpty(), "Do not grade before queued answers are saved");
            exams.updateAnswers("ST001", new SubmitExamRequest(round.getId(), Map.of(question, "A")), receipt.receivedAt());
        }
        exams.updateAnswers("ST001", new SubmitExamRequest(round.getId(), Map.of(question, "B")), clock.instant());
        exams.finalizeExpiredAttempts();
        assertEquals(10.0, exams.getExamResult("ST001", round.getId()).orElseThrow().getScore());
    }

    private static NetworkMessage await(BlockingQueue<NetworkMessage> messages, MessageType type) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var message = messages.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull(message, "Missing " + type);
            if (message.getType() == MessageType.ERROR) fail(message.getPayload().toString());
            if (message.getType() == type) return message;
        }
        throw new AssertionError("Missing " + type);
    }
    private static class TestClock extends Clock {
        volatile Instant now = Instant.now();
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
