package com.ptit.lanexam.server.service;

import com.ptit.lanexam.client.network.LanClient;
import com.ptit.lanexam.common.*;
import com.ptit.lanexam.common.dto.*;
import com.ptit.lanexam.server.entity.ExamEvent.Kind;
import com.ptit.lanexam.server.entity.QuestionType;
import com.ptit.lanexam.server.network.*;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ExamWarningIntegrationTest {
    private EntityManagerFactory factory;
    private ExamService exams;
    private ExamServer server;
    private Long teacherId, templateId, classId;
    private final TestClock clock = new TestClock();
    private final List<LanClient> clients = new ArrayList<>();
    private record Peer(LanClient client, BlockingQueue<NetworkMessage> messages) {}

    @BeforeEach void setup() throws Exception {
        factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver", "jakarta.persistence.jdbc.url", "jdbc:h2:mem:warnings_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "", "hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
        teacherId = new TeacherAccountService(factory::createEntityManager).create("teacher", "Teacher", "Matkhau123!").id();
        var bank = new QuestionBankService(factory::createEntityManager, teacherId);
        var subject = bank.createSubject("LTM");
        var classes = new ClassService(factory::createEntityManager, teacherId);
        classId = classes.create("A", "Class A", "HK1", subject.id()).id();
        classes.importRoster(classId, RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n1,ST001,An,12/05/2003,C1\n2,ST002,Bình,12/05/2003,C1"));
        exams = new ExamService(factory::createEntityManager, teacherId, clock);
        templateId = exams.createExam(new ExamDraft("Exam", 20, List.of(new ExamDraft.QuestionDraft(
                "Choose A", QuestionType.MULTIPLE_CHOICE, "Right", "Wrong", "Wrong", "Wrong", "A")))).id();
        server = new ExamServer(new SessionRegistry(), new AuthService(factory::createEntityManager, teacherId), exams);
        server.start(0);
    }
    @AfterEach void cleanup() { clients.forEach(LanClient::close); if (server != null) server.stop(); if (factory != null) factory.close(); }

    @Test void tcpFocusEpisodesUseServerTimeAndDoNotCountShortDuplicateOrForeignRoundSignals() throws Exception {
        var peer = login();
        var round = server.openExam(templateId, classId).exam();
        peer.client.send(JsonUtil.message(MessageType.EXAM_FOCUS_CHANGED, new ExamFocusChange(round.getId(), UUID.randomUUID().toString(), false)));
        ping(peer); assertTrue(exams.events().list(round.getId(), "ST001").isEmpty(), "Waiting-room signals must not count");
        peer.client.send(JsonUtil.message(MessageType.JOIN_EXAM, new JoinExamRequest(round.getId())));
        await(peer, MessageType.EXAM_STARTED); await(peer, MessageType.EXAM_WARNING_STATUS);
        assertEquals(0, episode(peer, round.getId(), 2999, false).count());
        assertEquals(1, episode(peer, round.getId(), 3000, true).count());
        var second = episode(peer, round.getId(), 5000, false); assertEquals(2, second.count()); assertFalse(second.needsReview());
        var third = episode(peer, round.getId(), 7000, false); assertEquals(3, third.count()); assertTrue(third.needsReview());
        peer.client.send(JsonUtil.message(MessageType.EXAM_FOCUS_CHANGED, new ExamFocusChange(round.getId() + 10000, UUID.randomUUID().toString(), false)));
        ping(peer);
        assertEquals(3, exams.events().status(round.getId(), "ST001", "").count());
        exams.events().review(round.getId(), "ST001"); assertFalse(exams.events().status(round.getId(), "ST001", "").needsReview());
        assertTrue(episode(peer, round.getId(), 4000, false).needsReview());
        server.remindExam(round.getId(), "ST001", "Tập trung làm bài");
        assertEquals("Tập trung làm bài", await(peer, MessageType.VIOLATION_WARNING).getPayload().asText());
        assertTrue(exams.events().list(round.getId(), "ST001").stream().anyMatch(e -> e.kind() == Kind.MANUAL_WARNING));
        long question = round.getQuestions().getFirst().getId();
        peer.client.send(JsonUtil.message(MessageType.SUBMIT_EXAM, new SubmitExamRequest(round.getId(), Map.of(question, "A"))));
        assertEquals(10.0, JsonUtil.payload(await(peer, MessageType.SUBMIT_RESULT), SubmitResult.class).getScore());
        peer.client.send(JsonUtil.message(MessageType.EXAM_FOCUS_CHANGED, new ExamFocusChange(round.getId(), UUID.randomUUID().toString(), false)));
        ping(peer); assertEquals(4, exams.events().status(round.getId(), "ST001", "").count());
    }
    @Test void historySurvivesReconnectRestartAndReviewIsScopedToTeacherAndRound() throws Exception {
        var round = server.openExam(templateId, classId).exam(); var peer = login();
        peer.client.send(JsonUtil.message(MessageType.JOIN_EXAM, new JoinExamRequest(round.getId())));
        await(peer, MessageType.EXAM_STARTED); await(peer, MessageType.EXAM_WARNING_STATUS);
        episode(peer, round.getId(), 4000, false);
        peer.client.close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (exams.events().list(round.getId(), "ST001").stream().noneMatch(e -> e.kind() == Kind.DISCONNECTED) && System.nanoTime() < deadline) Thread.sleep(20);
        assertEquals(1, exams.events().summaries(round.getId()).get("ST001").interruptions());
        var back = login(); ping(back);
        assertEquals(1, exams.events().status(round.getId(), "ST001", "").count());
        assertTrue(exams.events().list(round.getId(), "ST001").stream().anyMatch(e -> e.kind() == Kind.RECONNECTED));
        var other = new TeacherAccountService(factory::createEntityManager).create("other", "Other", "Matkhau123!");
        var foreign = new ExamEventService(factory::createEntityManager, other.id(), clock);
        assertThrows(IllegalArgumentException.class, () -> foreign.list(round.getId(), "ST001"));
        assertThrows(IllegalArgumentException.class, () -> foreign.review(round.getId(), "ST001"));
        server.stop();
        exams = new ExamService(factory::createEntityManager, teacherId, clock);
        server = new ExamServer(new SessionRegistry(), new AuthService(factory::createEntityManager, teacherId), exams); server.start(0);
        assertEquals(1, exams.events().status(round.getId(), "ST001", "").count());
        server.endExam(round.getId()); var next = server.openExam(templateId, classId).exam();
        assertEquals(0, exams.events().status(next.getId(), "ST001", "").count());
        assertEquals(1, exams.events().status(round.getId(), "ST001", "").count());
    }
    private Peer login() throws Exception {
        var queue = new LinkedBlockingQueue<NetworkMessage>(); var client = new LanClient(); client.setMessageListener(queue::add);
        clients.add(client); client.connect("127.0.0.1", server.getPort());
        client.send(JsonUtil.message(MessageType.LOGIN, new LoginRequest("ST001", "2003-05-12", true)));
        var peer = new Peer(client, queue); await(peer, MessageType.LOGIN_RESULT); return peer;
    }
    private ExamWarningStatus episode(Peer peer, Long round, long millis, boolean duplicate) throws Exception {
        String episode = UUID.randomUUID().toString();
        var left = new ExamFocusChange(round, episode, false);
        peer.client.send(JsonUtil.message(MessageType.EXAM_FOCUS_CHANGED, left)); ping(peer);
        if (duplicate) { peer.client.send(JsonUtil.message(MessageType.EXAM_FOCUS_CHANGED, left)); ping(peer); }
        clock.now = clock.now.plusMillis(millis);
        if (duplicate) {
            peer.client.send(JsonUtil.message(MessageType.EXAM_FOCUS_CHANGED, left));
            var whileAway = JsonUtil.payload(await(peer, MessageType.EXAM_WARNING_STATUS), ExamWarningStatus.class);
            assertTrue(whileAway.count() > 0, "Warn while still outside the exam window, before returning");
        }
        var returned = new ExamFocusChange(round, episode, true);
        peer.client.send(JsonUtil.message(MessageType.EXAM_FOCUS_CHANGED, returned));
        var status = JsonUtil.payload(await(peer, MessageType.EXAM_WARNING_STATUS), ExamWarningStatus.class);
        if (duplicate) {
            peer.client.send(JsonUtil.message(MessageType.EXAM_FOCUS_CHANGED, returned)); ping(peer);
            assertEquals(status.count(), exams.events().status(round, "ST001", "").count());
        }
        return status;
    }
    private void ping(Peer peer) throws Exception { peer.client.send(JsonUtil.message(MessageType.PING, null)); await(peer, MessageType.PONG); }
    private static NetworkMessage await(Peer peer, MessageType type) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < deadline) {
            var message = peer.messages.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull(message, "Missing " + type);
            if (message.getType() == MessageType.ERROR) fail(message.getPayload().toString());
            if (message.getType() == type) return message;
        }
        throw new AssertionError("Missing " + type);
    }
    private static class TestClock extends Clock {
        volatile Instant now = Instant.now(); public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; }
    }
}
