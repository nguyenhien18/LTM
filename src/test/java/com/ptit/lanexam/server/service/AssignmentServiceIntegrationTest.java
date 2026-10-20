package com.ptit.lanexam.server.service;

import com.ptit.lanexam.client.network.LanClient;
import com.ptit.lanexam.common.*;
import com.ptit.lanexam.common.dto.*;
import com.ptit.lanexam.common.dto.AssignmentMessages.*;
import com.ptit.lanexam.server.entity.Student;
import com.ptit.lanexam.server.network.ExamServer;
import com.ptit.lanexam.server.network.SessionRegistry;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class AssignmentServiceIntegrationTest {
    private static EntityManagerFactory factory;
    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");
    private AssignmentService service;

    @BeforeAll
    static void startDatabase() {
        factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:assignments_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
    }

    @BeforeEach
    void seedStudents() {
        service = new AssignmentService(factory::createEntityManager, Clock.fixed(NOW, ZoneOffset.UTC));
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            em.createQuery("delete from AssignmentSubmission").executeUpdate();
            em.createQuery("delete from Assignment").executeUpdate();
            em.createQuery("delete from Student").executeUpdate();
            em.persist(new Student("ST001", "Nguyễn Văn An", LocalDate.of(2003, 5, 12)));
            em.persist(new Student("ST002", "Trần Bình", LocalDate.of(2003, 8, 20)));
            em.getTransaction().commit();
        }
    }

    @AfterAll
    static void stopDatabase() { if (factory != null) factory.close(); }

    @Test
    void teacherPublishesTaskAndStudentSubmitsPersistentTextAndFile() {
        byte[] requirement = "Đề bài mạng LAN".getBytes(StandardCharsets.UTF_8);
        Long id = service.create(new AssignmentDraft("Bài tập Socket", "Viết ứng dụng chat.", NOW.plusSeconds(3600),
                "đề bài.pdf", requirement));
        assertArrayEquals(requirement, service.downloadForStudent("ST001", new FileRequest(id, false)).data());
        assertNull(service.listForStudent("ST001").getFirst().mySubmission());
        byte[] content = "public class Main {}".getBytes(StandardCharsets.UTF_8);
        var saved = service.submit("ST001", new SubmitRequest(id, "Bài làm của em", "Main.java", content, false));

        // A different service/entity manager must read the committed submission.
        AssignmentService reopened = new AssignmentService(factory::createEntityManager);
        var mine = reopened.listForStudent("ST001").getFirst();
        assertEquals(saved.id(), mine.mySubmission().id());
        assertEquals("Bài làm của em", mine.mySubmission().content());
        assertEquals(1, mine.submissionCount());
        assertEquals("ST001", reopened.listSubmissions(id).getFirst().studentCode());
        assertArrayEquals(content, reopened.downloadSubmission(saved.id()).data());
        assertEquals("đề bài.pdf", reopened.listForTeacher().getFirst().fileName());
    }

    @Test
    void studentsCanOnlyReadTheirOwnSubmissionsAndFiles() {
        Long id = createTask(null);
        service.submit("ST001", new SubmitRequest(id, "Bài riêng của An", "an.txt", new byte[]{1, 2}, false));
        assertNull(service.listForStudent("ST002").getFirst().mySubmission());
        assertThrows(IllegalArgumentException.class,
                () -> service.downloadForStudent("ST002", new FileRequest(id, true)));
        service.submit("ST002", new SubmitRequest(id, "Bài của Bình", "binh.txt", new byte[]{3}, false));
        assertEquals(2, service.listSubmissions(id).size());
        assertArrayEquals(new byte[]{3}, service.downloadForStudent("ST002", new FileRequest(id, true)).data());
        assertThrows(IllegalArgumentException.class, () -> service.listForStudent(null));
        assertThrows(IllegalArgumentException.class, () -> service.submit(null, new SubmitRequest(id, "fake", null, null, false)));
    }

    @Test
    void resubmittingKeepsOrReplacesOrRemovesFileWithoutCreatingDuplicateRows() {
        Long id = createTask(null);
        var first = service.submit("ST001", new SubmitRequest(id, "Lần đầu", "a.txt", new byte[]{1}, false));
        var second = service.submit("ST001", new SubmitRequest(id, "Sửa nội dung", null, null, true));
        assertEquals(first.id(), second.id());
        assertArrayEquals(new byte[]{1}, service.downloadSubmission(first.id()).data());
        service.submit("ST001", new SubmitRequest(id, "", "b.zip", new byte[]{2, 3}, false));
        assertArrayEquals(new byte[]{2, 3}, service.downloadSubmission(first.id()).data());
        assertThrows(IllegalArgumentException.class,
                () -> service.submit("ST001", new SubmitRequest(id, "", null, null, false)));
        assertEquals("b.zip", service.listForStudent("ST001").getFirst().mySubmission().fileName());
        service.submit("ST001", new SubmitRequest(id, "Chỉ nộp văn bản", null, null, false));
        assertThrows(IllegalArgumentException.class, () -> service.downloadSubmission(first.id()));
        assertEquals(1, service.listSubmissions(id).size());
    }

    @Test
    void deadlineUsesServerClockAndLateChangesCannotOverwriteAcceptedWork() {
        Instant deadline = NOW.plusSeconds(60);
        Long id = createTask(deadline);
        service.submit("ST001", new SubmitRequest(id, "Đã nộp trước hạn", null, null, false));
        AssignmentService late = new AssignmentService(factory::createEntityManager, Clock.fixed(deadline, ZoneOffset.UTC));
        assertThrows(IllegalStateException.class,
                () -> late.submit("ST001", new SubmitRequest(id, "Không được ghi đè", null, null, false)));
        assertThrows(IllegalStateException.class,
                () -> late.submit("ST002", new SubmitRequest(id, "Quá hạn", null, null, false)));
        assertEquals("Đã nộp trước hạn", late.listForStudent("ST001").getFirst().mySubmission().content());
        assertEquals(1, late.listSubmissions(id).size());
    }

    @Test
    void rejectsInvalidTasksUnsafeFileNamesAndOversizeContent() {
        assertThrows(IllegalArgumentException.class, () -> service.create(new AssignmentDraft("", "Nội dung", null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.create(new AssignmentDraft("Tên", "", null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.create(new AssignmentDraft("Tên", "Nội dung", NOW, null, null)));
        Long id = createTask(null);
        assertThrows(IllegalArgumentException.class, () -> service.submit("ST001",
                new SubmitRequest(id, "Bài làm", "../outside.txt", new byte[]{1}, false)));
        assertThrows(IllegalArgumentException.class, () -> service.submit("ST001",
                new SubmitRequest(id, "Bài làm", "large.zip", new byte[TransferLimits.MAX_ATTACHMENT_BYTES + 1], false)));
        assertThrows(IllegalArgumentException.class, () -> service.submit("ST001",
                new SubmitRequest(id, "x".repeat(TransferLimits.MAX_TEXT_LENGTH + 1), null, null, false)));
        assertThrows(IllegalArgumentException.class,
                () -> service.submit("ST001", new SubmitRequest(-1L, "Không có bài", null, null, false)));
        assertTrue(service.listSubmissions(id).isEmpty());
    }

    @Test
    void concurrentRepeatedSubmissionsUpdateOneStudentRow() throws Exception {
        Long id = createTask(null);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<AssignmentSubmissionInfo> action = () -> {
                start.await();
                return service.submit("ST001", new SubmitRequest(id, "Bài nộp", null, null, false));
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS).id(), second.get(10, TimeUnit.SECONDS).id());
        }
        assertEquals(1, service.listSubmissions(id).size());
    }

    @Test
    void realTcpClientLogsInUploadsLargeFileAndDownloadsItAgain() throws Exception {
        Long id = createTask(null);
        ExamServer server = new ExamServer(new SessionRegistry(), new AuthService(factory::createEntityManager),
                new ExamService(factory::createEntityManager), service);
        LanClient client = new LanClient();
        BlockingQueue<NetworkMessage> messages = new LinkedBlockingQueue<>();
        client.setMessageListener(messages::add);
        try {
            server.start(0);
            client.connect("127.0.0.1", server.getPort());
            client.send(JsonUtil.message(MessageType.ASSIGNMENT_SUBMIT, new SubmitRequest(id, "Chưa đăng nhập", null, null, false)));
            assertEquals(MessageType.ERROR, await(messages, MessageType.ERROR).getType());
            assertTrue(service.listSubmissions(id).isEmpty());
            client.send(JsonUtil.message(MessageType.LOGIN, new LoginRequest("ST001", "2003-05-12")));
            assertTrue(JsonUtil.payload(await(messages, MessageType.LOGIN_RESULT), LoginResponse.class).isSuccess());
            var tasks = JsonUtil.payload(await(messages, MessageType.ASSIGNMENTS_RESULT), ListResult.class);
            assertEquals(id, tasks.items().getFirst().id());
            byte[] bytes = new byte[1_200_000]; // Larger than the old one-megabyte protocol limit.
            new Random(17).nextBytes(bytes);
            client.send(JsonUtil.message(MessageType.ASSIGNMENT_SUBMIT, new SubmitRequest(id, "Qua TCP", "source.zip", bytes, false)));
            var saved = JsonUtil.payload(await(messages, MessageType.ASSIGNMENT_SUBMITTED), AssignmentSubmissionInfo.class);
            assertEquals("ST001", saved.studentCode());
            await(messages, MessageType.ASSIGNMENTS_RESULT);
            client.send(JsonUtil.message(MessageType.ASSIGNMENT_FILE_REQUEST, new FileRequest(id, true)));
            var file = JsonUtil.payload(await(messages, MessageType.ASSIGNMENT_FILE_RESULT), FileResult.class);
            assertArrayEquals(bytes, file.data());

            client.send(JsonUtil.message(MessageType.ASSIGNMENT_FILE_REQUEST, new FileRequest(-1L, true)));
            await(messages, MessageType.ASSIGNMENT_ERROR);
            client.send(JsonUtil.message(MessageType.PING, null));
            await(messages, MessageType.PONG); // A failed task request must not terminate the connection.
            server.notifyAssignmentsChanged();
            await(messages, MessageType.ASSIGNMENTS_CHANGED);
        } finally {
            client.close();
            server.stop();
        }
    }

    private Long createTask(Instant dueAt) {
        return service.create(new AssignmentDraft("Bài tập", "Nội dung bài tập", dueAt, null, null));
    }

    private static NetworkMessage await(BlockingQueue<NetworkMessage> messages, MessageType type) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            NetworkMessage message = messages.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull(message, "Không nhận được " + type);
            if (message.getType() == type) return message;
            if (message.getType() == MessageType.ERROR || message.getType() == MessageType.ASSIGNMENT_ERROR)
                fail("Server trả lỗi thay vì " + type + ": " + message.getPayload());
        }
        throw new AssertionError("Timeout: " + type);
    }
}
