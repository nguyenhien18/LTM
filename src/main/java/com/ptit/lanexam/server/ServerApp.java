package com.ptit.lanexam.server;

import com.ptit.lanexam.common.dto.ExamDto;
import com.ptit.lanexam.server.network.*;
import com.ptit.lanexam.server.persistence.JpaUtil;
import com.ptit.lanexam.server.service.AuthService;
import com.ptit.lanexam.server.service.AssignmentService;
import com.ptit.lanexam.server.service.ExamService;
import com.ptit.lanexam.server.service.ExamResultRow;
import com.ptit.lanexam.server.service.ExamSummary;
import com.ptit.lanexam.server.service.SeedData;
import com.ptit.lanexam.server.service.QuestionBankService;
import com.ptit.lanexam.server.service.TeacherAccountService;
import com.ptit.lanexam.server.service.TeacherIdentity;
import com.ptit.lanexam.common.ui.AssignmentUi;
import jakarta.persistence.EntityManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public class ServerApp extends Application {
    private final ObservableList<ClientSessionInfo> rows = FXCollections.observableArrayList();
    private final SessionRegistry registry = new SessionRegistry();
    private final Supplier<EntityManager> entityManagers;
    private final Runnable initializeDatabase;
    private final boolean ownsJpa;
    private final TeacherAccountService teacherAccounts;
    private TeacherIdentity teacher;
    private TeacherLoginPane teacherLogin;
    private Button logoutButton;
    private volatile long workspaceVersion;

    private ExamServer server;
    private ExamService examService;
    private AssignmentService assignmentService;
    private QuestionBankService questionBankService;
    private AuthService authService;
    private StudentSessionTable studentTable;
    private Label statusLabel;
    private Label addressLabel;
    private Button startButton;
    private Button stopButton;
    private Button publishButton;
    private Button createButton;
    private Button resultsButton;
    private Button assignmentsButton;
    private Button questionBankButton;
    private Button lockedAccountsButton;
    private Stage mainStage;
    private boolean closePending;

    public ServerApp() {
        this(JpaUtil::em, () -> { JpaUtil.init(); SeedData.ensureSeeded(); }, true);
    }
    public ServerApp(Supplier<EntityManager> managers, Runnable initializeDatabase) {
        this(managers, initializeDatabase, false);
    }
    private ServerApp(Supplier<EntityManager> managers, Runnable initializeDatabase, boolean ownsJpa) {
        entityManagers = managers; this.initializeDatabase = initializeDatabase; this.ownsJpa = ownsJpa;
        teacherAccounts = new TeacherAccountService(managers);
    }

    @Override
    public void start(Stage stage) {
        mainStage = stage;
        stage.setOnCloseRequest(event -> {
            if (examService == null) return;
            event.consume();
            closeServerWindow();
        });
        registry.setListener(snapshot -> {
            long version = workspaceVersion;
            Platform.runLater(() -> { if (version == workspaceVersion) rows.setAll(snapshot); });
        });
        stage.setTitle("LAN Exam Server - Đăng nhập giáo viên");
        stage.setMinWidth(620); stage.setMinHeight(520);
        loadDatabase();
        stage.show();
    }

    private void loadDatabase() {
        long version = workspaceVersion;
        mainStage.setScene(new Scene(new StackPane(new Label("Đang kết nối database...")), 660, 560));
        AssignmentUi.async("teacher-database-startup", () -> { initializeDatabase.run(); return true; }, ready -> {
            if (version == workspaceVersion) showTeacherLogin();
        }, ex -> {
            if (version != workspaceVersion) return;
            Label error = new Label("Không kết nối được database: " + ex.getMessage()); error.setWrapText(true);
            Button retry = new Button("Thử lại"); retry.setOnAction(event -> loadDatabase());
            VBox content = new VBox(16, error, retry); content.setPadding(new Insets(28));
            mainStage.setScene(new Scene(content, 660, 560));
        });
    }

    private void showTeacherLogin() {
        teacherLogin = new TeacherLoginPane(teacherAccounts, this::enterWorkspace);
        StackPane root = new StackPane(teacherLogin); root.setId("teacher-login-root");
        mainStage.setScene(new Scene(root, 660, 560));
        mainStage.setTitle("LAN Exam Server - Đăng nhập giáo viên");
        mainStage.sizeToScene();
    }

    private void enterWorkspace(TeacherIdentity identity) {
        teacherLogin.dispose(); teacherLogin = null;
        teacher = identity; workspaceVersion++;
        examService = new ExamService(entityManagers, identity.id());
        questionBankService = new QuestionBankService(entityManagers, identity.id());
        assignmentService = new AssignmentService(entityManagers, Clock.systemUTC(), identity.id());
        authService = new AuthService(entityManagers);
        showWorkspace();
    }

    private void showWorkspace() {
        Stage stage = mainStage;

        Label title = new Label("LAN Exam Server - Giảng viên");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        statusLabel = new Label("Server chưa chạy");
        statusLabel.setWrapText(true);
        addressLabel = new Label("IP Server sẽ hiển thị khi khởi động Server.");
        addressLabel.setId("server-address");
        addressLabel.setWrapText(true);
        Label teacherLabel = new Label("Giáo viên: " + teacher.fullName() + " (" + teacher.username() + ")");
        teacherLabel.setId("active-teacher"); teacherLabel.setStyle("-fx-font-weight: bold;");
        teacherLabel.setWrapText(true);
        logoutButton = new Button("Đăng xuất / Đổi giáo viên");
        logoutButton.setId("teacher-logout"); logoutButton.setOnAction(event -> logoutTeacher());
        Button passwordButton = new Button("Đổi mật khẩu"); passwordButton.setOnAction(event -> changeTeacherPassword());

        studentTable = new StudentSessionTable(rows, this::warnStudent, this::lockStudent);

        startButton = new Button("Khởi động Server");
        startButton.setId("server-start");
        stopButton = new Button("Dừng Server");
        publishButton = new Button("Chọn bài thi để mở");
        createButton = new Button("Tạo đề thi");
        resultsButton = new Button("Xem điểm");
        assignmentsButton = new Button("Bài tập / Bài nộp");
        questionBankButton = new Button("Ngân hàng câu hỏi");
        lockedAccountsButton = new Button("Tài khoản bị khóa / Mở khóa");
        lockedAccountsButton.setId("manage-locked-accounts");
        lockedAccountsButton.setStyle("-fx-font-weight: bold;");
        lockedAccountsButton.setDisable(true);
        stopButton.setDisable(true);
        publishButton.setDisable(true);
        createButton.setDisable(true);
        resultsButton.setDisable(true);
        assignmentsButton.setDisable(true);
        questionBankButton.setDisable(true);

        startButton.setOnAction(e -> startServer());
        stopButton.setOnAction(e -> stopServer());
        publishButton.setOnAction(e -> publishExam());
        createButton.setOnAction(e -> createExam());
        questionBankButton.setOnAction(e -> QuestionBankManager.show(mainStage, questionBankService));
        lockedAccountsButton.setOnAction(e -> LockedAccountManager.show(mainStage, authService));
        resultsButton.setOnAction(e -> showResults());
        assignmentsButton.setOnAction(e -> {
            ExamServer activeServer = server;
            new AssignmentManager(mainStage, assignmentService, activeServer::notifyAssignmentsChanged);
        });

        FlowPane actions = new FlowPane(10, 10, startButton, stopButton, lockedAccountsButton,
                createButton, publishButton, questionBankButton, resultsButton, assignmentsButton);
        VBox header = new VBox(10, title, new FlowPane(12, 8, teacherLabel, passwordButton, logoutButton), statusLabel, addressLabel, actions);
        header.setPadding(new Insets(16));

        BorderPane root = new BorderPane();
        root.setTop(header);
        VBox students = new VBox(8, studentTable,
                new Label("ONLINE: đã đăng nhập • DOING_EXAM: đang làm đề • SUBMITTED: đã nộp đề trong phiên này"));
        VBox.setVgrow(studentTable, javafx.scene.layout.Priority.ALWAYS);
        students.setPadding(new Insets(0, 16, 0, 16));
        root.setCenter(students);

        stage.setScene(new Scene(root, 1180, 640));
        stage.setTitle("LAN Exam Server - " + teacher.fullName());
        stage.sizeToScene();
        stage.show();
    }

    private void startServer() {
        long version = workspaceVersion;
        ExamService currentExams = examService;
        AssignmentService currentAssignments = assignmentService;
        AuthService currentAuth = authService;
        startButton.setDisable(true);
        logoutButton.setDisable(true);
        statusLabel.setText("Đang khởi động database và server...");

        Thread thread = new Thread(() -> {
            try {
                if (version != workspaceVersion) return;
                ExamServer candidate = new ExamServer(registry, currentAuth, currentExams, currentAssignments);
                server = candidate;
                candidate.start(5000);
                if (version != workspaceVersion) { candidate.stop(); return; }
                String connectionInfo = ServerAddress.connectionInfo(candidate.getPort());
                Platform.runLater(() -> {
                    if (version != workspaceVersion) return;
                    logoutButton.setDisable(false);
                    statusLabel.setText("Server đang chạy tại cổng 5000");
                    addressLabel.setText(connectionInfo);
                    stopButton.setDisable(false);
                    publishButton.setDisable(false);
                    createButton.setDisable(false);
                    resultsButton.setDisable(false);
                    assignmentsButton.setDisable(false);
                    questionBankButton.setDisable(false);
                    lockedAccountsButton.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (version != workspaceVersion) return;
                    logoutButton.setDisable(false);
                    startButton.setDisable(false);
                    statusLabel.setText("Khởi động thất bại");
                    showError("Không thể khởi động Server", ex.getMessage());
                });
            }
        }, "server-startup");
        thread.setDaemon(true);
        thread.start();
    }

    private void publishExam() {
        long version = workspaceVersion;
        ExamService currentExams = examService;
        publishButton.setDisable(true);
        createButton.setDisable(true);
        ExamServer activeServer = server;
        Thread loader = new Thread(() -> {
            try {
                List<ExamSummary> exams = currentExams.listExams();
                Platform.runLater(() -> {
                    if (version != workspaceVersion) return;
                    if (!activeServer.isRunning()) return;
                    try {
                        var selected = ExamSelectionDialog.show(mainStage, exams);
                        if (selected.isEmpty()) { finishPublishAction(activeServer); return; }
                        activateAndPublish(activeServer, selected.get().id());
                    } catch (Exception ex) {
                        finishPublishAction(activeServer);
                        showError("Không thể chọn bài thi", ex.getMessage());
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (version != workspaceVersion) return;
                    finishPublishAction(activeServer);
                    showError("Không thể tải danh sách bài thi", e.getMessage());
                });
            }
        }, "load-exams");
        loader.setDaemon(true);
        loader.start();
    }

    private void activateAndPublish(ExamServer activeServer, Long examId) {
        long version = workspaceVersion;
        ExamService currentExams = examService;
        Thread thread = new Thread(() -> {
            try {
                ExamServer.Publication publication = activeServer.openExam(examId);
                ExamDto exam = publication.exam();
                int count = publication.notifiedStudents();
                Platform.runLater(() -> {
                    if (version != workspaceVersion) return;
                    statusLabel.setText("Đã mở đợt #" + exam.getId() + ": " + exam.getTitle()
                            + "; đã thông báo cho " + count + " sinh viên online");
                    finishPublishAction(activeServer);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (version != workspaceVersion) return;
                    finishPublishAction(activeServer);
                    showError("Không thể mở bài thi", ex.getMessage());
                });
            }
        }, "publish-exam");
        thread.setDaemon(true);
        thread.start();
    }

    private void finishPublishAction(ExamServer activeServer) {
        if (activeServer.isRunning()) {
            publishButton.setDisable(false);
            createButton.setDisable(false);
        }
    }

    private void createExam() {
        long version = workspaceVersion;
        ExamService currentExams = examService;
        ExamEditorDialog.show(mainStage, questionBankService).ifPresent(draft -> {
            createButton.setDisable(true);
            publishButton.setDisable(true);
            Thread thread = new Thread(() -> {
                try {
                    ExamSummary created = currentExams.createExamFromBank(draft);
                    Platform.runLater(() -> {
                        if (version != workspaceVersion) return;
                        statusLabel.setText("Đã lưu đề: " + created.title() + ". Bài đang mở vẫn tiếp tục; chọn đề khi muốn mở bài mới.");
                        if (server != null && server.isRunning()) {
                            createButton.setDisable(false);
                            publishButton.setDisable(false);
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        if (version != workspaceVersion) return;
                        if (server != null && server.isRunning()) {
                            createButton.setDisable(false);
                            publishButton.setDisable(false);
                        }
                        showError("Không thể tạo đề thi", ex.getMessage());
                    });
                }
            }, "create-exam");
            thread.setDaemon(true);
            thread.start();
        });
    }

    private void showResults() {
        Stage window = new Stage();
        window.initOwner(mainStage);
        window.setTitle("Bảng điểm sinh viên");
        TableView<ExamResultRow> table = new TableView<>();
        table.getColumns().add(resultColumn("Mã lượt", 80, row -> Long.toString(row.roundId())));
        table.getColumns().add(resultColumn("Mã sinh viên", 130, row -> row.studentCode()));
        table.getColumns().add(resultColumn("Họ tên", 160, row -> row.fullName()));
        table.getColumns().add(resultColumn("Đề thi", 190, row -> row.examTitle()));
        table.getColumns().add(resultColumn("Điểm", 75, row -> String.format(Locale.ROOT, "%.2f", row.score())));
        table.getColumns().add(resultColumn("Số câu đúng", 100, row -> row.correct() + "/" + row.total()));
        DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        table.getColumns().add(resultColumn("Thời gian nộp", 160, row -> row.submittedAt().format(timeFormat)));
        table.setPlaceholder(new Label("Chưa có bài nộp"));
        Label count = new Label("Đang tải kết quả...");
        Button refresh = new Button("Làm mới");
        refresh.setOnAction(e -> loadResults(table, count, refresh));
        VBox root = new VBox(10, table, new HBox(10, refresh, count));
        root.setPadding(new Insets(14));
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        window.setScene(new Scene(root, 950, 520));
        window.show();
        loadResults(table, count, refresh);
    }

    private TableColumn<ExamResultRow, String> resultColumn(String title, int width,
            java.util.function.Function<ExamResultRow, String> value) {
        TableColumn<ExamResultRow, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }

    private void loadResults(TableView<ExamResultRow> table, Label count, Button refresh) {
        long version = workspaceVersion;
        ExamService currentExams = examService;
        refresh.setDisable(true);
        Thread thread = new Thread(() -> {
            try {
                List<ExamResultRow> results = currentExams.listResults();
                Platform.runLater(() -> {
                    if (version != workspaceVersion) return;
                    table.getItems().setAll(results);
                    count.setText("Tổng số bài đã nộp: " + results.size());
                    refresh.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (version != workspaceVersion) return;
                    refresh.setDisable(false);
                    count.setText("Không tải được bảng điểm");
                    showError("Không thể xem điểm", ex.getMessage());
                });
            }
        }, "load-results");
        thread.setDaemon(true);
        thread.start();
    }

    private void warnStudent(ClientSessionInfo student) {
        moderationDialog(student, false);
    }

    private void lockStudent(ClientSessionInfo student) {
        moderationDialog(student, true);
    }

    private void moderationDialog(ClientSessionInfo student, boolean lock) {
        long version = workspaceVersion;
        ExamServer activeServer = server;
        if (activeServer == null || !activeServer.isRunning()) return;
        TextInputDialog dialog = new TextInputDialog(lock ? "Vi phạm quy chế thi" : "Bạn đang vi phạm quy chế thi. Vui lòng dừng hành vi vi phạm.");
        dialog.initOwner(mainStage);
        dialog.setTitle(lock ? "Khóa tài khoản" : "Cảnh báo vi phạm");
        dialog.setHeaderText((lock ? "Khóa " : "Cảnh báo ") + student.studentCode() + " — " + student.fullName());
        dialog.setContentText(lock ? "Lý do khóa (tối đa 500 ký tự):" : "Nội dung gửi đến Client (tối đa 1.000 ký tự):");
        if (lock) dialog.getEditor().setTooltip(new Tooltip("Ngắt kết nối và chặn đăng nhập đến khi mở khóa. Hết giờ chấm các lựa chọn Server đã nhận; vào lại làm bài trống."));
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText(lock ? "Khóa tài khoản" : "Gửi cảnh báo");
        dialog.showAndWait().ifPresent(reason -> {
            studentTable.setDisable(true);
            com.ptit.lanexam.common.ui.AssignmentUi.async("student-moderation", () -> {
                if (lock) activeServer.lockAccount(student.studentCode(), reason);
                else activeServer.warnViolation(student.studentCode(), reason);
                return student.studentCode();
            }, code -> {
                if (version != workspaceVersion) return;
                studentTable.setDisable(false);
                statusLabel.setText(lock ? "Đã khóa tài khoản " + code
                        + ". Bấm Tài khoản bị khóa / Mở khóa ở phía trên để mở lại."
                        : "Đã gửi cảnh báo đến " + code);
            }, ex -> {
                if (version != workspaceVersion) return;
                studentTable.setDisable(false);
                showError("Không thể " + (lock ? "khóa tài khoản" : "gửi cảnh báo"), ex.getMessage());
            });
        });
    }

    private void stopServer() {
        changeSession(false);
    }

    private void closeServerWindow() {
        if (closePending) return;
        closePending = true;
        javafx.scene.Parent workspace = mainStage.getScene().getRoot();
        workspace.setDisable(true);
        ExamServer currentServer = server;
        ExamService currentExams = examService;
        AssignmentUi.async("server-window-close", () -> {
            if (currentServer != null && currentServer.isRunning()) currentServer.stopIfIdle();
            else currentExams.ensureCanStop();
            return true;
        }, done -> {
            stop();
            mainStage.close();
        }, ex -> {
            closePending = false;
            workspace.setDisable(false);
            showError("Chưa thể đóng Server", ex.getMessage());
        });
    }

    private void logoutTeacher() { changeSession(true); }

    private void changeSession(boolean logout) {
        long version = workspaceVersion;
        ExamServer currentServer = server;
        ExamService currentExams = examService;
        javafx.scene.Parent workspace = mainStage.getScene().getRoot();
        workspace.setDisable(true);
        logoutButton.setDisable(true); stopButton.setDisable(true); startButton.setDisable(true);
        AssignmentUi.async("teacher-session-change", () -> {
            if (currentServer != null && currentServer.isRunning()) currentServer.stopIfIdle();
            else currentExams.ensureCanStop();
            return true;
        }, done -> {
            if (version != workspaceVersion) return;
            workspace.setDisable(false);
            markServerStopped(); server = null;
            if (logout) {
                workspaceVersion++;
                Window.getWindows().stream().filter(window -> window instanceof Stage child && child != mainStage && child.getOwner() == mainStage)
                        .map(window -> (Stage) window).toList().forEach(Stage::close);
                teacher = null; rows.clear(); showTeacherLogin();
            }
        }, ex -> {
            if (version != workspaceVersion) return;
            workspace.setDisable(false);
            logoutButton.setDisable(false);
            boolean running = currentServer != null && currentServer.isRunning();
            startButton.setDisable(running); stopButton.setDisable(!running);
            showError("Chưa thể " + (logout ? "đổi giáo viên" : "dừng Server"), ex.getMessage());
        });
    }

    private void changeTeacherPassword() {
        long version = workspaceVersion;
        Long teacherId = teacher.id();
        TeacherAccountDialogs.changePassword(mainStage).ifPresent(input -> AssignmentUi.async("change-teacher-password", () -> {
            teacherAccounts.changePassword(teacherId, input.currentPassword(), input.newPassword()); return true;
        }, done -> { if (version == workspaceVersion) statusLabel.setText("Đã đổi mật khẩu giáo viên."); },
                ex -> { if (version == workspaceVersion) showError("Không đổi được mật khẩu", ex.getMessage()); }));
    }

    private void markServerStopped() {
        rows.clear();
        logoutButton.setDisable(false);
        statusLabel.setText("Server đã dừng");
        addressLabel.setText("IP Server sẽ hiển thị khi khởi động Server.");
        startButton.setDisable(false);
        stopButton.setDisable(true);
        publishButton.setDisable(true);
        createButton.setDisable(true);
        resultsButton.setDisable(true);
        assignmentsButton.setDisable(true);
        questionBankButton.setDisable(true);
        lockedAccountsButton.setDisable(true);
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(mainStage);
        alert.getDialogPane().setId("server-error-notice");
        alert.getDialogPane().setPrefWidth(560);
        alert.getDialogPane().setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message == null ? "Lỗi không xác định" : message);
        alert.showAndWait();
    }

    @Override
    public void stop() {
        workspaceVersion++;
        if (teacherLogin != null) teacherLogin.dispose();
        if (server != null) server.stop();
        if (ownsJpa) JpaUtil.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
