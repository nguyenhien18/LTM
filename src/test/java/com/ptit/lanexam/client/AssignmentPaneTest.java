package com.ptit.lanexam.client;

import com.ptit.lanexam.common.*;
import com.ptit.lanexam.common.dto.*;
import com.ptit.lanexam.common.dto.AssignmentMessages.SubmitRequest;
import com.ptit.lanexam.server.AssignmentManager;
import com.ptit.lanexam.server.ExamEditorDialog;
import com.ptit.lanexam.server.QuestionEditorDialog;
import com.ptit.lanexam.server.StudentSessionTable;
import com.ptit.lanexam.server.LockedAccountManager;
import com.ptit.lanexam.server.ServerApp;
import com.ptit.lanexam.server.network.*;
import com.ptit.lanexam.server.entity.Student;
import com.ptit.lanexam.server.entity.QuestionType;
import com.ptit.lanexam.server.service.BankExamDraft;
import com.ptit.lanexam.server.service.BankQuestionDraft;
import com.ptit.lanexam.server.service.BankQuestionInfo;
import com.ptit.lanexam.server.service.QuestionBankService;
import com.ptit.lanexam.server.service.SubjectInfo;
import com.ptit.lanexam.server.service.AssignmentService;
import com.ptit.lanexam.server.service.AuthService;
import com.ptit.lanexam.server.service.ExamService;
import com.ptit.lanexam.server.service.LockedAccountInfo;
import com.ptit.lanexam.server.service.TeacherAccountService;
import com.ptit.lanexam.server.service.ExamDraft;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import javafx.collections.FXCollections;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "lanexam.uiTests", matches = "true")
class AssignmentPaneTest {
    private static final Instant TIME = Instant.parse("2026-10-20T08:00:00Z");

    @BeforeAll
    static void initializeToolkit() throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.complete(null);
        });
        ready.get(10, TimeUnit.SECONDS);
    }

    @AfterAll
    static void shutdownToolkit() { Platform.exit(); }

    @Test
    void incomingUpdatesPreserveStudentDraftAndResubmissionKeepsAttachedFile() throws Exception {
        BlockingQueue<NetworkMessage> outgoing = new LinkedBlockingQueue<>();
        AssignmentPane pane = fx(() -> {
            AssignmentPane view = new AssignmentPane(null, outgoing::add);
            Scene scene = new Scene(view, 900, 600);
            view.setItems(List.of(task(submission("Bài cũ"))));
            view.applyCss();
            view.layout();
            TextArea answer = (TextArea) scene.lookup("#assignment-answer");
            answer.setText("Bài mới chưa gửi");
            view.setItems(List.of(task(submission("Bài cũ"))));
            assertEquals("Bài mới chưa gửi", answer.getText(), "Làm mới danh sách không được xóa nội dung đang soạn");
            ((Button) scene.lookup("#assignment-submit")).fire();
            return view;
        });
        NetworkMessage sent = outgoing.poll(5, TimeUnit.SECONDS);
        assertNotNull(sent);
        assertEquals(MessageType.ASSIGNMENT_SUBMIT, sent.getType());
        SubmitRequest request = JsonUtil.payload(sent, SubmitRequest.class);
        assertEquals(1L, request.assignmentId());
        assertEquals("Bài mới chưa gửi", request.content());
        assertTrue(request.keepExistingFile());
        assertNull(request.fileData());
        fx(() -> {
            pane.submitted(submission("Bài mới chưa gửi"));
            pane.setItems(List.of(task(submission("Bài mới chưa gửi"))));
            assertEquals("Bài mới chưa gửi", ((TextArea) pane.lookup("#assignment-answer")).getText());
            assertFalse(((Button) pane.lookup("#assignment-submit")).isDisabled());
            pane.dispose();
            return null;
        });
    }

    @Test
    void studentAndTeacherViewsRenderAndTeacherCanReadSelectedSubmission() throws Exception {
        fx(() -> {
            AssignmentPane pane = new AssignmentPane(null, ignored -> {});
            new Scene(pane, 920, 620);
            pane.applyCss();
            pane.layout();
            assertTrue(((Button) pane.lookup("#assignment-submit")).isDisabled());
            pane.setItems(List.of(task(submission("Em đã hoàn thành chương trình Socket.\nMã nguồn nằm trong source.zip."))));
            snapshot(pane, "assignment-student");
            pane.dispose();
            return null;
        });
        fx(() -> {
            AssignmentService sample = new AssignmentService() {
                @Override public List<AssignmentInfo> listForTeacher() { return List.of(task(null)); }
                @Override public List<AssignmentSubmissionInfo> listSubmissions(Long id) {
                    return List.of(submission("Em đã hoàn thành chương trình Socket.\nMã nguồn nằm trong source.zip."));
                }
            };
            new AssignmentManager(null, sample, () -> {});
            return null;
        });
        Stage teacher = null;
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            teacher = fx(() -> Window.getWindows().stream().filter(w -> w instanceof Stage s
                    && "Bài tập - Giảng viên".equals(s.getTitle())).map(w -> (Stage) w).findFirst().orElse(null));
            Stage current = teacher;
            if (current != null && fx(() -> !((TableView<?>) current.getScene().lookup("#teacher-submissions")).getItems().isEmpty()))
                break;
            Thread.sleep(25);
        }
        assertNotNull(teacher);
        Stage finalTeacher = teacher;
        fx(() -> {
            TableView<?> table = (TableView<?>) finalTeacher.getScene().lookup("#teacher-submissions");
            assertEquals(1, table.getItems().size());
            table.getSelectionModel().selectFirst();
            TextArea answer = (TextArea) finalTeacher.getScene().lookup("#teacher-submission-content");
            assertTrue(answer.getText().contains("hoàn thành"));
            snapshot(finalTeacher.getScene().getRoot(), "assignment-teacher");
            finalTeacher.close();
            return null;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void questionEditorValidatesDifficultyAndOnlyOffersMultipleChoice() throws Exception {
        SubjectInfo subject = new SubjectInfo(1L, "Lập trình mạng", 0);
        fx(() -> {
            Dialog<BankQuestionDraft> dialog = QuestionEditorDialog.create(null, List.of(subject), subject, null);
            dialog.show();
            var pane = dialog.getDialogPane();
            ((TextArea) pane.lookup("#question-content")).setText("Giao thức nào cung cấp truyền dữ liệu tin cậy?");
            ((TextField) pane.lookup("#question-option-a")).setText("UDP");
            ((TextField) pane.lookup("#question-option-b")).setText("IP");
            ((TextField) pane.lookup("#question-option-c")).setText("TCP");
            ((TextField) pane.lookup("#question-option-d")).setText("ICMP");
            ((ComboBox<String>) pane.lookup("#question-choice-answer")).setValue("C");
            Spinner<Integer> level = (Spinner<Integer>) pane.lookup("#question-difficulty");
            level.getEditor().setText("7");
            ((Button) pane.lookup("#question-save")).fire();
            assertTrue(dialog.isShowing(), "Độ khó ngoài 1–6 không được lưu.");
            level.getEditor().setText("6");
            snapshot(pane, "question-editor");
            ((Button) pane.lookup("#question-save")).fire();
            assertEquals(6, dialog.getResult().difficulty());
            assertEquals("C", dialog.getResult().question().correctAnswer());
            assertEquals(QuestionType.MULTIPLE_CHOICE, dialog.getResult().question().type());
            assertNull(pane.lookup("#question-type"));
            assertNull(pane.lookup("#question-text-answer"));
            return null;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void composingExamKeepsSelectionsAcrossFiltersButClearsThemOnSubjectChange() throws Exception {
        List<SubjectInfo> subjects = List.of(new SubjectInfo(1L, "Lập trình mạng", 3), new SubjectInfo(2L, "Cơ sở dữ liệu", 1));
        List<BankQuestionInfo> questions = List.of(
                new BankQuestionInfo(101L, 1L, "Lập trình mạng", 1, "Giao thức nào truyền dữ liệu tin cậy?", QuestionType.MULTIPLE_CHOICE,
                        "TCP", "UDP", "IP", "ICMP", "A"),
                new BankQuestionInfo(102L, 1L, "Lập trình mạng", 6, "Kỹ thuật nào tránh xử lý đồng thời gây mất dữ liệu?", QuestionType.MULTIPLE_CHOICE,
                        "Đóng socket", "Đồng bộ hóa", "Tăng timeout", "Bỏ kiểm tra", "B"),
                new BankQuestionInfo(103L, 1L, "Lập trình mạng", 3, "Lớp Java dùng để kết nối TCP?", QuestionType.MULTIPLE_CHOICE,
                        "Socket", "Scanner", "Thread", "String", "A"),
                new BankQuestionInfo(201L, 2L, "Cơ sở dữ liệu", 2, "Ngôn ngữ truy vấn cơ sở dữ liệu?", QuestionType.MULTIPLE_CHOICE,
                        "Java", "SQL", "C", "Python", "B"));
        QuestionBankService sample = new QuestionBankService() {
            @Override public List<SubjectInfo> listSubjects() { return subjects; }
            @Override public List<BankQuestionInfo> listQuestions(Long subject, Integer level) {
                return questions.stream().filter(q -> q.subjectId().equals(subject))
                        .filter(q -> level == null || q.difficulty() == level).toList();
            }
        };
        Dialog<BankExamDraft> dialog = fx(() -> {
            var created = ExamEditorDialog.create(null, sample);
            created.show();
            return created;
        });
        awaitFx(() -> bankTable(dialog).getItems().size() == 3 && bankReady(dialog));
        fx(() -> {
            bankTable(dialog).getSelectionModel().selectIndices(0, 1);
            ((Button) dialog.getDialogPane().lookup("#exam-add-selected")).fire();
            ((Button) dialog.getDialogPane().lookup("#exam-add-selected")).fire();
            assertEquals(2, selectedTable(dialog).getItems().size(), "Không thêm trùng câu hỏi.");
            ((ComboBox<Integer>) dialog.getDialogPane().lookup("#bank-difficulty")).setValue(6);
            return null;
        });
        awaitFx(() -> bankTable(dialog).getItems().size() == 1 && bankReady(dialog));
        fx(() -> {
            assertEquals(102L, bankTable(dialog).getItems().getFirst().id());
            assertEquals(2, selectedTable(dialog).getItems().size(), "Lọc không được làm mất câu đã chọn.");
            assertNull(dialog.getDialogPane().lookup("#bank-type"));
            ((ComboBox<Integer>) dialog.getDialogPane().lookup("#bank-difficulty")).setValue(0);
            return null;
        });
        awaitFx(() -> bankTable(dialog).getItems().size() == 3 && bankReady(dialog));
        fx(() -> {
            bankTable(dialog).getSelectionModel().clearAndSelect(2);
            ((Button) dialog.getDialogPane().lookup("#exam-add-selected")).fire();
            assertEquals(3, selectedTable(dialog).getItems().size());
            selectedTable(dialog).getSelectionModel().clearAndSelect(1);
            ((Button) dialog.getDialogPane().lookup("#exam-move-up")).fire();
            assertEquals(102L, selectedTable(dialog).getItems().getFirst().id());
            ((TextField) dialog.getDialogPane().lookup("#exam-title")).setText("Kiểm tra Lập trình mạng");
            snapshot(dialog.getDialogPane(), "exam-from-question-bank");
            ((ComboBox<SubjectInfo>) dialog.getDialogPane().lookup("#bank-subject")).setValue(subjects.get(1));
            assertTrue(selectedTable(dialog).getItems().isEmpty(), "Đổi môn phải bỏ câu của môn trước.");
            return null;
        });
        awaitFx(() -> bankTable(dialog).getItems().size() == 1 && bankReady(dialog));
        fx(() -> {
            bankTable(dialog).getSelectionModel().selectFirst();
            ((Button) dialog.getDialogPane().lookup("#exam-add-selected")).fire();
            ((TextField) dialog.getDialogPane().lookup("#exam-title")).setText("Kiểm tra CSDL");
            ((Button) dialog.getDialogPane().lookup("#exam-save")).fire();
            assertEquals(2L, dialog.getResult().subjectId());
            assertEquals(List.of(201L), dialog.getResult().questionIds());
            return null;
        });
    }

    @Test
    void teacherTableShowsExamAndButtonsFollowTheCurrentStudentRow() throws Exception {
        fx(() -> {
            var rows = FXCollections.observableArrayList(
                    new ClientSessionInfo("ST001", "Nguyễn Văn An", "192.168.1.11", "DOING_EXAM", 101L, "Đề Lập trình mạng 1"),
                    new ClientSessionInfo("ST002", "Trần Minh Bình", "192.168.1.12", "SUBMITTED", 101L, "Đề Lập trình mạng 1"));
            BlockingQueue<String> actions = new LinkedBlockingQueue<>();
            StudentSessionTable table = new StudentSessionTable(rows,
                    row -> actions.add("warn:" + row.studentCode()), row -> actions.add("lock:" + row.studentCode()));
            new Scene(table, 1180, 360);
            snapshot(table, "student-moderation-table");
            studentAction(table, "ST001", "#student-warn").fire();
            assertEquals("warn:ST001", actions.poll());
            rows.set(0, new ClientSessionInfo("ST003", "Lê Hoàng Cường", "192.168.1.13", "ONLINE", null, null));
            table.applyCss();
            table.layout();
            studentAction(table, "ST003", "#student-lock").fire();
            assertEquals("lock:ST003", actions.poll(), "Nút phải thao tác với sinh viên hiện tại sau khi bảng làm mới.");
            return null;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void clientShowsTeacherWarningAndReturnsToLoginWhenLockedAndTeacherCanUnlock() throws Exception {
        AtomicBoolean locked = new AtomicBoolean();
        Student student = new Student("ST001", "Nguyễn Văn An", LocalDate.of(2003, 5, 12));
        AuthService auth = new AuthService() {
            @Override public Optional<Student> authenticate(String code, String birthDate) { return Optional.of(student); }
            @Override public boolean isAccountLocked(String code) { return locked.get(); }
            @Override public void setAccountLocked(String code, boolean value, String reason) { locked.set(value); }
            @Override public List<LockedAccountInfo> listLockedAccounts() {
                return locked.get() ? List.of(new LockedAccountInfo("ST001", "Nguyễn Văn An", "Vi phạm quy chế")) : List.of();
            }
        };
        ExamDto exam = new ExamDto(101L, "Đề kiểm tra mạng", 120, List.of(
                new QuestionDto(1L, "Giao thức tin cậy?", "MULTIPLE_CHOICE", List.of("A. TCP", "B. UDP", "C. IP", "D. ICMP"))));
        ExamService exams = new ExamService() {
            @Override public void finalizeExpiredAttempts() {}
            @Override public Optional<SubmitResult> getExamResult(String code, Long id) { return Optional.empty(); }
            @Override public List<ExamHistoryItem> listStudentHistory(String code) { return List.of(); }
            @Override public Optional<ExamOffer> findOffer(String code, Long id) { return Optional.of(new ExamOffer(101L, exam.getTitle(), 120, false)); }
            @Override public Optional<ExamDto> startAttempt(String code, Long id) { return Optional.of(exam); }
            @Override public void updateAnswers(String code, SubmitExamRequest request, Instant receivedAt) {}
        };
        SessionRegistry registry = new SessionRegistry();
        ExamServer server = new ExamServer(registry, auth, exams, new AssignmentService() {
            @Override public List<AssignmentInfo> listForStudent(String code) { return List.of(); }
        });
        server.start(0);
        ClientApp app = new ClientApp();
        Stage clientStage = fx(() -> {
            Stage created = new Stage();
            app.start(created);
            ((TextField) created.getScene().lookup("#login-code")).setText("ST001");
            ((TextField) created.getScene().lookup("#login-port")).setText(Integer.toString(server.getPort()));
            ((Button) created.getScene().lookup("#login-connect")).fire();
            return created;
        });
        try {
            awaitFx(() -> clientStage.getScene().lookup("#join-exam") instanceof Button join && !join.isDisabled());
            fx(() -> { ((Button) clientStage.getScene().lookup("#join-exam")).fire(); return null; });
            awaitFx(() -> clientStage.getScene().lookup("#exam-submit") != null);
            fx(() -> { ((RadioButton) clientStage.getScene().getRoot().lookupAll(".radio-button").iterator().next()).fire(); return null; });
            server.warnViolation("ST001", "Không trao đổi bài với bạn bên cạnh.");
            awaitFx(() -> notice("#violation-warning-notice") != null);
            fx(() -> {
                DialogPane pane = notice("#violation-warning-notice");
                assertEquals("Không trao đổi bài với bạn bên cạnh.", pane.getContentText());
                assertNotNull(clientStage.getScene().lookup("#exam-timer"));
                assertTrue(clientStage.getScene().getRoot().lookupAll(".radio-button").stream().anyMatch(node -> ((RadioButton) node).isSelected()));
                snapshot(pane, "client-violation-warning");
                ((Button) pane.lookupButton(ButtonType.OK)).fire();
                return null;
            });
            server.lockAccount("ST001", "Vi phạm quy chế");
            awaitFx(() -> notice("#account-locked-notice") != null && clientStage.getScene().lookup("#login-connect") != null);
            fx(() -> {
                assertNull(clientStage.getScene().lookup("#exam-submit"));
                assertTrue(notice("#account-locked-notice").getContentText().contains("Vi phạm quy chế"));
                snapshot(notice("#account-locked-notice"), "client-account-locked");
                assertTrue(Window.getWindows().stream().noneMatch(window -> window instanceof Stage stage && "Mất kết nối".equals(stage.getTitle())));
                ((Button) notice("#account-locked-notice").lookupButton(ButtonType.OK)).fire();
                LockedAccountManager.show(clientStage, auth);
                return null;
            });
            awaitFx(() -> lockedAccountStage() != null && !((TableView<?>) lockedAccountStage().getScene().lookup("#locked-accounts")).getItems().isEmpty());
            fx(() -> {
                Stage manager = lockedAccountStage();
                ((TableView<LockedAccountInfo>) manager.getScene().lookup("#locked-accounts")).getSelectionModel().selectFirst();
                ((Button) manager.getScene().lookup("#account-unlock")).fire();
                return null;
            });
            awaitFx(() -> !locked.get() && ((TableView<?>) lockedAccountStage().getScene().lookup("#locked-accounts")).getItems().isEmpty());
            fx(() -> { lockedAccountStage().close(); return null; });
        } finally {
            fx(() -> { app.stop(); clientStage.close(); if (lockedAccountStage() != null) lockedAccountStage().close(); return null; });
            server.stop();
        }
    }

    @Test
    void teachersRegisterLoginSeeTheirNameAndSwitchOnlyAfterTheExamEnds() throws Exception {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:teacher_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
        try (var em = factory.createEntityManager()) {
            em.getTransaction().begin(); em.persist(new Student("ST001", "Nguyễn An", LocalDate.of(2003, 5, 12))); em.getTransaction().commit();
        }
        ServerApp app = new ServerApp(factory::createEntityManager, () -> {});
        Stage stage = fx(() -> { Stage created = new Stage(); app.start(created); return created; });
        try {
            awaitFx(() -> stage.getScene().lookup("#teacher-login-submit") != null);
            fx(() -> { snapshot(stage.getScene().getRoot(), "teacher-login"); return null; });
            createTeacherInUi(stage, "gv01", "Nguyễn Thị Lan");
            fx(() -> {
                ((TextField) stage.getScene().lookup("#teacher-login-username")).setText("gv01");
                ((PasswordField) stage.getScene().lookup("#teacher-login-password")).setText("SaiMatKhau");
                ((Button) stage.getScene().lookup("#teacher-login-submit")).fire(); return null;
            });
            awaitFx(() -> ((Label) stage.getScene().lookup("#teacher-login-message")).getText().contains("không đúng"));
            loginTeacherInUi(stage, "gv01");
            fx(() -> {
                assertTrue(((Label) stage.getScene().lookup("#active-teacher")).getText().contains("Nguyễn Thị Lan (gv01)"));
                assertNotNull(stage.getScene().lookup("#server-start"));
                snapshot(stage.getScene().getRoot(), "teacher-server-workspace");
                return null;
            });
            var teacher = new TeacherAccountService(factory::createEntityManager).authenticate("gv01", "Matkhau123!").orElseThrow();
            ExamService exams = new ExamService(factory::createEntityManager, teacher.id());
            var template = exams.createExam(new ExamDraft("Đề đang làm", 20, List.of(new ExamDraft.QuestionDraft(
                    "Chọn A", QuestionType.MULTIPLE_CHOICE, "Một", "Hai", "Ba", "Bốn", "A"))));
            var round = exams.activateExam(template.id());
            exams.startAttempt("ST001", round.getId());
            fx(() -> { ((Button) stage.getScene().lookup("#teacher-logout")).fire(); return null; });
            awaitFx(() -> notice("#server-error-notice") != null);
            fx(() -> {
                assertTrue(notice("#server-error-notice").getContentText().contains("Ca thi đang diễn ra"));
                assertNotNull(stage.getScene().lookup("#active-teacher"));
                snapshot(notice("#server-error-notice"), "teacher-switch-blocked");
                ((Button) notice("#server-error-notice").lookupButton(ButtonType.OK)).fire(); return null;
            });
            exams.submit("ST001", new SubmitExamRequest(round.getId(), Map.of()));
            expireUiRound(factory);
            fx(() -> { ((Button) stage.getScene().lookup("#teacher-logout")).fire(); return null; });
            awaitFx(() -> stage.getScene().lookup("#teacher-login-submit") != null);
            createTeacherInUi(stage, "gv02", "Trần Văn Minh");
            loginTeacherInUi(stage, "gv02");
            fx(() -> {
                assertTrue(((Label) stage.getScene().lookup("#active-teacher")).getText().contains("Trần Văn Minh (gv02)"));
                ((Button) stage.getScene().lookup("#teacher-logout")).fire(); return null;
            });
            awaitFx(() -> stage.getScene().lookup("#teacher-login-submit") != null);
            loginTeacherInUi(stage, "gv01");
            fx(() -> {
                assertTrue(((Label) stage.getScene().lookup("#active-teacher")).getText().contains("Nguyễn Thị Lan (gv01)")); return null;
            });
        } finally {
            fx(() -> {
                Window.getWindows().stream().filter(w -> w instanceof Stage child && child.getOwner() == stage)
                        .map(w -> (Stage) w).toList().forEach(Stage::close);
                app.stop(); stage.close(); return null;
            });
            factory.close();
        }
    }

    @Test
    void closingServerWindowIsBlockedByOfflineAttemptAndAllowedAfterDeadline() throws Exception {
        try (EntityManagerFactory factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:close_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            var teacher = new TeacherAccountService(factory::createEntityManager).create("gvclose", "Giáo viên", "Matkhau123!");
            ExamService exams = new ExamService(factory::createEntityManager, teacher.id());
            try (var em = factory.createEntityManager()) {
                em.getTransaction().begin();
                em.persist(new Student("ST001", "Học sinh offline", LocalDate.of(2003, 5, 12)));
                em.getTransaction().commit();
            }
            var template = exams.createExam(new ExamDraft("Đề đang thi", 20, List.of(new ExamDraft.QuestionDraft(
                    "Chọn A", QuestionType.MULTIPLE_CHOICE, "Một", "Hai", "Ba", "Bốn", "A"))));
            var round = exams.activateExam(template.id());
            exams.startAttempt("ST001", round.getId());
            ServerApp app = new ServerApp(factory::createEntityManager, () -> {});
            Stage stage = fx(() -> { Stage created = new Stage(); app.start(created); return created; });
            try {
                awaitFx(() -> stage.getScene().lookup("#teacher-login-submit") != null);
                loginTeacherInUi(stage, "gvclose");
                fx(() -> { stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)); return null; });
                awaitFx(() -> notice("#server-error-notice") != null);
                fx(() -> {
                    assertTrue(stage.isShowing());
                    assertTrue(notice("#server-error-notice").getContentText().contains("Ca thi đang diễn ra"));
                    ((Button) notice("#server-error-notice").lookupButton(ButtonType.OK)).fire();
                    return null;
                });
                try (var em = factory.createEntityManager()) {
                    em.getTransaction().begin();
                    em.createQuery("update ExamAttempt a set a.deadlineAt = :past")
                            .setParameter("past", Instant.now().minusSeconds(1)).executeUpdate();
                    em.createQuery("update Exam e set e.deadlineAt = :past where e.active = true")
                            .setParameter("past", Instant.now().minusSeconds(1)).executeUpdate();
                    em.getTransaction().commit();
                }
                fx(() -> { stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)); return null; });
                awaitFx(() -> !stage.isShowing());
                assertTrue(exams.getExamResult("ST001", round.getId()).isPresent(), "Phải chấm bài hết giờ trước khi đóng.");
            } finally {
                fx(() -> {
                    if (notice("#server-error-notice") != null)
                        ((Button) notice("#server-error-notice").lookupButton(ButtonType.OK)).fire();
                    app.stop(); stage.close(); return null;
                });
            }
        }
    }

    @Test
    void reconnectingSameOrDifferentStudentStartsEmptyAndExpiryGradesOnlineAndOfflineStudents() throws Exception {
        try (EntityManagerFactory factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:restart_ui_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))) {
            try (var em = factory.createEntityManager()) {
                em.getTransaction().begin();
                em.persist(new Student("ST001", "Student A", LocalDate.of(2003, 5, 12)));
                em.persist(new Student("ST002", "Student B", LocalDate.of(2003, 5, 12)));
                em.getTransaction().commit();
            }
            BlockingQueue<String> updates = new LinkedBlockingQueue<>();
            ExamService exams = new ExamService(factory::createEntityManager) {
                @Override public void updateAnswers(String code, SubmitExamRequest request, Instant receivedAt) {
                    super.updateAnswers(code, request, receivedAt);
                    updates.add(code);
                }
            };
            var template = exams.createExam(new ExamDraft("Đề chung", 20, List.of(new ExamDraft.QuestionDraft(
                    "Chọn A", QuestionType.MULTIPLE_CHOICE, "Một", "Hai", "Ba", "Bốn", "A"))));
            var round = exams.activateExam(template.id());
            SessionRegistry registry = new SessionRegistry();
            ExamServer server = new ExamServer(registry, new AuthService(factory::createEntityManager), exams,
                    new AssignmentService(factory::createEntityManager));
            server.start(0);
            server.publishExam(round);
            ClientApp app = new ClientApp();
            Stage stage = fx(() -> { Stage created = new Stage(); app.start(created); return created; });
            try {
                for (String code : List.of("ST001", "ST001", "ST002")) {
                    fx(() -> {
                        ((TextField) stage.getScene().lookup("#login-code")).setText(code);
                        ((TextField) stage.getScene().lookup("#login-port")).setText(Integer.toString(server.getPort()));
                        ((Button) stage.getScene().lookup("#login-connect")).fire(); return null;
                    });
                    awaitFx(() -> stage.getScene().lookup("#join-exam") instanceof Button join && !join.isDisabled());
                    fx(() -> { ((Button) stage.getScene().lookup("#join-exam")).fire(); return null; });
                    awaitFx(() -> stage.getScene().lookup("#exam-submit") != null);
                    fx(() -> {
                        var choices = stage.getScene().getRoot().lookupAll(".radio-button");
                        assertTrue(choices.stream().noneMatch(node -> ((RadioButton) node).isSelected()), "Vào lại phải làm bài trống: " + code);
                        choices.stream().map(node -> (RadioButton) node).filter(button -> button.getText().startsWith("A."))
                                .findFirst().orElseThrow().fire();
                        return null;
                    });
                    assertEquals(code, updates.poll(5, TimeUnit.SECONDS));
                    if (code.equals("ST002")) break;
                    registry.find(code).getHandler().closeQuietly();
                    awaitFx(() -> notice(".dialog-pane") != null);
                    fx(() -> { ((Button) notice(".dialog-pane").lookupButton(ButtonType.OK)).fire(); return null; });
                    awaitFx(() -> stage.getScene().lookup("#login-connect") != null);
                }
                try (var em = factory.createEntityManager()) {
                    em.getTransaction().begin();
                    em.createQuery("update ExamAttempt a set a.deadlineAt = :past")
                            .setParameter("past", Instant.now().minusSeconds(1)).executeUpdate();
                    em.getTransaction().commit();
                }
                awaitFx(() -> stage.getScene().getRoot().lookupAll(".label").stream()
                        .anyMatch(node -> node instanceof Label label && label.getText().startsWith("Điểm:")));
                assertEquals(10.0, exams.getExamResult("ST001", round.getId()).orElseThrow().getScore());
                assertEquals(10.0, exams.getExamResult("ST002", round.getId()).orElseThrow().getScore());
            } finally {
                fx(() -> { app.stop(); stage.close(); return null; });
                server.stop();
            }
        }
    }

    @Test
    void waitingDashboardCountsDownAndDisablesJoiningWhenRoundExpires() throws Exception {
        long deadline = System.currentTimeMillis() + 4000;
        AtomicInteger joins = new AtomicInteger();
        ExamDto round = new ExamDto(99L, "Đề sắp hết giờ", 4, List.of());
        round.setDeadlineEpochMillis(deadline);
        ExamService exams = new ExamService() {
            @Override public void finalizeExpiredAttempts() {}
            @Override public List<ExamHistoryItem> listStudentHistory(String code) { return List.of(); }
            @Override public Optional<ExamOffer> findOffer(String code, Long id) {
                long now = System.currentTimeMillis();
                return now >= deadline ? Optional.empty() : Optional.of(new ExamOffer(99L, round.getTitle(),
                        (int) ((deadline - now + 999) / 1000), false, null, deadline, now));
            }
            @Override public Optional<ExamDto> startAttempt(String code, Long id) { joins.incrementAndGet(); return Optional.of(round); }
        };
        AuthService auth = new AuthService() {
            @Override public Optional<Student> authenticate(String code, String birthDate) {
                return Optional.of(new Student("ST001", "Student", LocalDate.of(2003, 5, 12)));
            }
            @Override public boolean isAccountLocked(String code) { return false; }
        };
        ExamServer server = new ExamServer(new SessionRegistry(), auth, exams, new AssignmentService() {
            @Override public List<AssignmentInfo> listForStudent(String code) { return List.of(); }
        });
        server.start(0); server.publishExam(round);
        ClientApp app = new ClientApp();
        Stage stage = fx(() -> {
            Stage created = new Stage(); app.start(created);
            ((TextField) created.getScene().lookup("#login-code")).setText("ST001");
            ((TextField) created.getScene().lookup("#login-port")).setText(Integer.toString(server.getPort()));
            ((Button) created.getScene().lookup("#login-connect")).fire();
            return created;
        });
        try {
            awaitFx(() -> stage.getScene().lookup("#join-exam") instanceof Button join && !join.isDisabled());
            String initial = fx(() -> ((Label) stage.getScene().lookup("#exam-offer-label")).getText());
            awaitFx(() -> !initial.equals(((Label) stage.getScene().lookup("#exam-offer-label")).getText()));
            awaitFx(() -> ((Button) stage.getScene().lookup("#join-exam")).isDisabled());
            fx(() -> { ((Button) stage.getScene().lookup("#join-exam")).fire(); return null; });
            assertEquals(0, joins.get(), "Đề hết hạn không được gửi yêu cầu tham gia.");
        } finally {
            fx(() -> { app.stop(); stage.close(); return null; });
            server.stop();
        }
    }

    private static void createTeacherInUi(Stage stage, String username, String fullName) throws Exception {
        fx(() -> {
            Platform.runLater(() -> ((Button) stage.getScene().lookup("#teacher-login-create")).fire()); return null;
        });
        awaitFx(() -> notice("#teacher-register-dialog") != null);
        fx(() -> {
            DialogPane pane = notice("#teacher-register-dialog");
            ((TextField) pane.lookup("#teacher-register-username")).setText(username);
            ((TextField) pane.lookup("#teacher-register-name")).setText(fullName);
            ((PasswordField) pane.lookup("#teacher-register-password")).setText("Matkhau123!");
            ((PasswordField) pane.lookup("#teacher-register-confirm")).setText("KhongKhop");
            ((Button) pane.lookup("#teacher-register-save")).fire();
            assertNotNull(notice("#teacher-register-dialog"), "Mật khẩu xác nhận không khớp thì chưa được lưu.");
            ((PasswordField) pane.lookup("#teacher-register-confirm")).setText("Matkhau123!");
            ((Button) pane.lookup("#teacher-register-save")).fire(); return null;
        });
        awaitFx(() -> ((Label) stage.getScene().lookup("#teacher-login-message")).getText().contains("Đã tạo tài khoản"));
    }

    private static void expireUiRound(EntityManagerFactory factory) {
        try (var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            em.createQuery("update Exam e set e.deadlineAt = :past where e.active = true")
                    .setParameter("past", Instant.now().minusSeconds(1)).executeUpdate();
            em.getTransaction().commit();
        }
    }

    private static void loginTeacherInUi(Stage stage, String username) throws Exception {
        fx(() -> {
            ((TextField) stage.getScene().lookup("#teacher-login-username")).setText(username);
            ((PasswordField) stage.getScene().lookup("#teacher-login-password")).setText("Matkhau123!");
            ((Button) stage.getScene().lookup("#teacher-login-submit")).fire(); return null;
        });
        awaitFx(() -> stage.getScene().lookup("#active-teacher") != null);
    }

    private static Button studentAction(StudentSessionTable table, String code, String selector) {
        return table.lookupAll(".table-row-cell").stream().filter(node -> node instanceof TableRow<?> row
                && row.getItem() instanceof ClientSessionInfo info && code.equals(info.studentCode()))
                .map(node -> (Button) node.lookup(selector)).filter(java.util.Objects::nonNull).findFirst().orElseThrow();
    }

    private static DialogPane notice(String selector) {
        return Window.getWindows().stream().map(Window::getScene).filter(java.util.Objects::nonNull)
                .map(scene -> scene.lookup(selector)).filter(java.util.Objects::nonNull)
                .map(node -> (DialogPane) node).findFirst().orElse(null);
    }

    private static Stage lockedAccountStage() {
        return Window.getWindows().stream().filter(w -> w instanceof Stage stage && "Tài khoản bị khóa".equals(stage.getTitle()))
                .map(w -> (Stage) w).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static TableView<BankQuestionInfo> bankTable(Dialog<?> dialog) {
        return (TableView<BankQuestionInfo>) dialog.getDialogPane().lookup("#bank-questions");
    }
    @SuppressWarnings("unchecked")
    private static TableView<BankQuestionInfo> selectedTable(Dialog<?> dialog) {
        return (TableView<BankQuestionInfo>) dialog.getDialogPane().lookup("#exam-selected-questions");
    }
    private static boolean bankReady(Dialog<?> dialog) {
        return ((Label) dialog.getDialogPane().lookup("#bank-load-status")).getText().contains("câu phù hợp");
    }
    private static void awaitFx(Supplier<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            if (fx(condition)) return;
            Thread.sleep(25);
        }
        fail("Giao diện không cập nhật danh sách câu hỏi đúng hạn.");
    }

    private static AssignmentInfo task(AssignmentSubmissionInfo mine) {
        return new AssignmentInfo(1L, "Bài tập 1: Lập trình TCP Socket",
                "Xây dựng ứng dụng chat trong mạng LAN.\nNộp mã nguồn và báo cáo dưới dạng một tệp ZIP.",
                TIME, null, "de-bai.pdf", 32000, 1, mine);
    }

    private static AssignmentSubmissionInfo submission(String content) {
        return new AssignmentSubmissionInfo(9L, "B21DCCN001", "Nguyễn Văn An", content, "source.zip", 64000, TIME);
    }

    private static <T> T fx(Supplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { result.complete(action.get()); }
            catch (Throwable ex) { result.completeExceptionally(ex); }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private static void snapshot(javafx.scene.Parent root, String name) {
        try {
            root.applyCss();
            root.layout();
            WritableImage image = root.snapshot(null, null);
            BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < png.getHeight(); y++)
                for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            Path directory = Path.of("target", "assignment-preview");
            Files.createDirectories(directory);
            ImageIO.write(png, "png", directory.resolve(name + ".png").toFile());
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
}
