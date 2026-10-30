package com.ptit.lanexam.server.service;

import com.ptit.lanexam.common.*;
import com.ptit.lanexam.common.dto.*;
import com.ptit.lanexam.server.entity.Teacher;
import com.ptit.lanexam.server.network.*;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TeacherStudentLockIntegrationTest {
    private EntityManagerFactory factory;
    private Long teacherA, teacherB;
    private AuthService a, b;
    @BeforeEach void setup() {
        factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:teacher_locks_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
        try (var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            var first = new Teacher("lock_a", "Giảng viên A", "unused");
            var second = new Teacher("lock_b", "Giảng viên B", "unused");
            em.persist(first); em.persist(second); em.flush();
            teacherA = first.getId(); teacherB = second.getId(); em.getTransaction().commit();
        }
        enroll(teacherA, "1,SHARED,Sinh viên chung,12/05/2003,C1\n2,ONLYA,Sinh viên A,12/05/2003,C1");
        enroll(teacherB, "1,SHARED,Sinh viên chung,12/05/2003,C1\n2,ONLYB,Sinh viên B,12/05/2003,C1");
        a = new AuthService(factory::createEntityManager, teacherA);
        b = new AuthService(factory::createEntityManager, teacherB);
    }
    private void enroll(Long teacher, String rows) {
        var bank = new QuestionBankService(factory::createEntityManager, teacher);
        var subject = bank.createSubject("Mạng");
        var classes = new ClassService(factory::createEntityManager, teacher);
        var group = classes.create("LTM", "Lớp mạng", "HK1", subject.id());
        classes.importRoster(group.id(), RosterCsv.parse("STT,MSV,Họ và tên,Ngày sinh,Lớp\n" + rows));
    }
    @AfterEach void close() { factory.close(); }
    @Test void teacherCannotAuthenticateLockUnlockOrListAnotherTeachersExclusiveStudent() {
        b.setAccountLocked("ONLYB", true, "Khóa của B");
        assertTrue(a.authenticate("ONLYB", "2003-05-12").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> a.setAccountLocked("ONLYB", true, "Không có quyền"));
        assertThrows(IllegalArgumentException.class, () -> a.setAccountLocked("ONLYB", false, null));
        assertTrue(a.listLockedAccounts().isEmpty());
        assertTrue(b.isAccountLocked("ONLYB"));
    }
    @Test void sharedStudentLocksAreIndependentAndPersistAcrossServiceRestart() {
        a.setAccountLocked("SHARED", true, "Lý do của A");
        assertFalse(b.isAccountLocked("SHARED"));
        b.setAccountLocked("SHARED", false, null);
        assertTrue(a.isAccountLocked("SHARED"));
        b.setAccountLocked("SHARED", true, "Lý do của B");
        a.setAccountLocked("SHARED", true, "A cập nhật lý do");
        var restored = new AuthService(factory::createEntityManager, teacherA);
        assertEquals("A cập nhật lý do", restored.accountLockReason("SHARED").orElseThrow());
        assertEquals(1, restored.listLockedAccounts().size());
        assertEquals("Lý do của B", b.listLockedAccounts().getFirst().reason());
        restored.setAccountLocked("SHARED", false, null);
        assertFalse(a.isAccountLocked("SHARED"));
        assertTrue(b.isAccountLocked("SHARED"));
    }
    @Test void oldGlobalLockDoesNotBecomeAnotherTeachersRestriction() {
        new AuthService(factory::createEntityManager).setAccountLocked("SHARED", true, "Khóa cũ chưa có chủ");
        assertFalse(a.isAccountLocked("SHARED")); assertFalse(b.isAccountLocked("SHARED"));
        assertTrue(a.listLockedAccounts().isEmpty()); assertTrue(b.listLockedAccounts().isEmpty());
    }
    @Test void tcpLoginChecksCurrentTeachersLockAndPreservesReasonAfterServerRestart() throws Exception {
        a.setAccountLocked("SHARED", true, "Vi phạm tại lớp A");
        for (int i = 0; i < 2; i++) {
            var server = new ExamServer(new SessionRegistry(), new AuthService(factory::createEntityManager, teacherA), new ExamService(factory::createEntityManager, teacherA));
            try {
                server.start(0);
                var result = login(server, "SHARED");
                assertFalse(result.isSuccess()); assertTrue(result.getMessage().contains("Vi phạm tại lớp A"));
            } finally { server.stop(); }
        }
        var other = new ExamServer(new SessionRegistry(), b, new ExamService(factory::createEntityManager, teacherB));
        try {
            other.start(0);
            assertTrue(login(other, "SHARED").isSuccess());
            assertFalse(login(other, "ONLYA").isSuccess());
        } finally { other.stop(); }
    }
    private LoginResponse login(ExamServer server, String code) throws Exception {
        try (var socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(5000);
            var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(JsonUtil.toJson(JsonUtil.message(MessageType.LOGIN, new LoginRequest(code, "2003-05-12"))) + "\n"); writer.flush();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var response = JsonUtil.fromJson(reader.readLine());
            assertEquals(MessageType.LOGIN_RESULT, response.getType());
            return JsonUtil.payload(response, LoginResponse.class);
        }
    }
}
