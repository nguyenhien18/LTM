package com.ptit.lanexam.server.network;

import com.ptit.lanexam.common.JsonUtil;
import com.ptit.lanexam.common.MessageType;
import com.ptit.lanexam.common.dto.ExamDto;
import com.ptit.lanexam.common.dto.LoginRequest;
import com.ptit.lanexam.server.entity.Student;
import com.ptit.lanexam.server.service.AuthService;
import com.ptit.lanexam.server.service.ExamService;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ExamServerFailureTest {
    private final ExamService exams = new ExamService(() -> { throw new AssertionError("No database expected"); }) {
        @Override public Optional<ExamDto> recoverPublishedExam() { return Optional.empty(); }
        @Override public void finalizeExpiredAttempts() {}
    };
    private final AuthService auth = new AuthService(() -> { throw new AssertionError("No database expected"); }) {
        @Override public Optional<Student> authenticate(String code, String birthDate) { return Optional.empty(); }
    };

    @Test void missingHandlerClassClosesListenerAndAllowsFreshServerToAnswerLogin() throws Exception {
        assertAcceptFailure(new NoClassDefFoundError("ClientHandler unavailable during rebuild"));
    }

    @Test void unexpectedHandlerInitializationFailureDoesNotLeaveZombieListener() throws Exception {
        assertAcceptFailure(new IllegalStateException("Handler initialization failed"));
    }

    private void assertAcceptFailure(Throwable expected) throws Exception {
        var failures = new LinkedBlockingQueue<Throwable>();
        ExamServer broken = new ExamServer(new SessionRegistry(), auth, exams, failures::add) {
            @Override void dispatchClient(Socket socket) {
                if (expected instanceof Error error) throw error;
                throw (RuntimeException) expected;
            }
        };
        ExamServer replacement = new ExamServer(new SessionRegistry(), auth, exams);
        try {
            broken.start(0);
            int port = broken.getPort();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                assertSame(expected, failures.poll(3, TimeUnit.SECONDS));
                assertFalse(broken.isRunning());
                assertEquals(-1, socket.getInputStream().read(), "Accepted Client must be closed too");
            }
            replacement.start(port);
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                String request = JsonUtil.toJson(JsonUtil.message(MessageType.LOGIN,
                        new LoginRequest("UNKNOWN", "2000-01-01"))) + "\n";
                socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                assertEquals(MessageType.LOGIN_RESULT, JsonUtil.fromJson(reader.readLine()).getType());
                assertTrue(replacement.isRunning());
            }
        } finally { broken.stop(); replacement.stop(); }
    }
}
