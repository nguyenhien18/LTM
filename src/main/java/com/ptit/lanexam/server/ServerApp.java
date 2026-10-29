package com.ptit.lanexam.server;

import com.ptit.lanexam.server.network.*;
import com.ptit.lanexam.server.persistence.JpaUtil;
import com.ptit.lanexam.server.service.AuthService;
import com.ptit.lanexam.server.service.ExamService;
import com.ptit.lanexam.server.service.QuestionBankService;
import com.ptit.lanexam.server.service.TeacherAccountService;
import com.ptit.lanexam.server.service.TeacherIdentity;
import com.ptit.lanexam.server.service.ClassService;
import com.ptit.lanexam.server.service.ExamShiftService;
import com.ptit.lanexam.common.ui.UiSupport;
import com.ptit.lanexam.common.ui.AppUi;
import jakarta.persistence.EntityManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ServerApp extends Application {
    private final ObservableList<ClientSessionInfo> rows = FXCollections.observableArrayList();
    private final SessionRegistry registry = new SessionRegistry();
    private final Supplier<EntityManager> entityManagers;
    private final Runnable initializeDatabase;
    private final boolean ownsJpa;
    private final int requestedPort;
    private final TeacherAccountService teacherAccounts;
    private TeacherIdentity teacher;
    private TeacherLoginPane teacherLogin;
    private Button logoutButton;
    private volatile long workspaceVersion;

    private ExamServer server;
    private ExamService examService;
    private ClassService classService;
    private ClassManager classManager;
    private ExamOrganizer organizer;
    private ExamResultsPane examResults;
    private QuestionBankService questionBankService;
    private AuthService authService;
    private Label statusLabel;
    private Label addressLabel;
    private Button startButton;
    private Button stopButton;
    private Button resultsButton;
    private Button questionBankButton;
    private Button lockedAccountsButton;
    private Stage mainStage;
    private boolean closePending;
    private BorderPane pageHost;
    private final Map<String, Node> pages = new LinkedHashMap<>();
    private final Map<String, Button> navigation = new LinkedHashMap<>();
    private QuestionBankBrowser bankBrowser;
    private ExamTemplateManager templateManager;
    private Label connectionState;

    public ServerApp() {
        this(JpaUtil::em, () -> {
            JpaUtil.init();
        }, true);
    }
    public ServerApp(Supplier<EntityManager> managers, Runnable initializeDatabase) {
        this(managers, initializeDatabase, false);
    }
    public ServerApp(Supplier<EntityManager> managers, Runnable initializeDatabase, int port) {
        this(managers, initializeDatabase, false, port);
    }
    private ServerApp(Supplier<EntityManager> managers, Runnable initializeDatabase, boolean ownsJpa) {
        this(managers, initializeDatabase, ownsJpa, 5000);
    }
    private ServerApp(Supplier<EntityManager> managers, Runnable initializeDatabase, boolean ownsJpa, int port) {
        entityManagers = managers; this.initializeDatabase = initializeDatabase; this.ownsJpa = ownsJpa;
        requestedPort = port;
        teacherAccounts = new TeacherAccountService(managers);
    }

    public int getListeningPort() { return server == null ? -1 : server.getPort(); }

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
        mainStage.setScene(AppUi.scene(new StackPane(new Label("Đang kết nối dữ liệu...")), 660, 560));
        UiSupport.async("teacher-database-startup", () -> { initializeDatabase.run(); return true; }, ready -> {
            if (version == workspaceVersion) showTeacherLogin();
        }, ex -> {
            if (version != workspaceVersion) return;
            Label error = new Label("Không kết nối được database: " + ex.getMessage()); error.setWrapText(true);
            Button retry = new Button("Thử lại"); retry.setOnAction(event -> loadDatabase());
            VBox content = new VBox(16, error, retry); content.setPadding(new Insets(28));
            mainStage.setScene(AppUi.scene(content, 660, 560));
        });
    }

    private void showTeacherLogin() {
        teacherLogin = new TeacherLoginPane(teacherAccounts, this::enterWorkspace);
        StackPane root = new StackPane(teacherLogin); root.setId("teacher-login-root");
        mainStage.setScene(AppUi.scene(root, 660, 600));
        mainStage.setMinWidth(620);
        mainStage.setMinHeight(520);
        mainStage.setTitle("LAN Exam Server - Đăng nhập giáo viên");
        mainStage.sizeToScene();
    }

    private void enterWorkspace(TeacherIdentity identity) {
        teacherLogin.dispose(); teacherLogin = null;
        teacher = identity; workspaceVersion++;
        examService = new ExamService(entityManagers, identity.id());
        questionBankService = new QuestionBankService(entityManagers, identity.id());
        classService = new ClassService(entityManagers, identity.id());
        authService = new AuthService(entityManagers);
        showWorkspace();
    }

    private void showWorkspace() {
        pages.clear();
        navigation.clear();
        statusLabel = new Label("Khởi động Server để sinh viên kết nối vào phòng học.");
        statusLabel.setWrapText(true);
        addressLabel = new Label("Địa chỉ kết nối sẽ hiển thị khi Server sẵn sàng.");
        addressLabel.setId("server-address");
        addressLabel.setWrapText(true);
        connectionState = AppUi.label("Chưa kết nối", "badge");
        Label teacherLabel = new Label(teacher.fullName() + " (" + teacher.username() + ")");
        teacherLabel.setId("active-teacher");
        teacherLabel.setWrapText(true);
        logoutButton = new Button("Đăng xuất");
        logoutButton.setId("teacher-logout"); logoutButton.setOnAction(event -> logoutTeacher());
        Button passwordButton = new Button("Đổi mật khẩu"); passwordButton.setOnAction(event -> changeTeacherPassword());
        passwordButton.getStyleClass().add("small");
        logoutButton.getStyleClass().add("small");

        startButton = new Button("Khởi động Server");
        startButton.setId("server-start");
        startButton.getStyleClass().add("primary");
        stopButton = new Button("Dừng Server");
        stopButton.setId("server-stop");
        stopButton.getStyleClass().add("danger");

        resultsButton = navButton("results", "Kết quả");
        questionBankButton = navButton("bank", "Ngân hàng câu hỏi");
        lockedAccountsButton = new Button("Mở khóa SV");
        lockedAccountsButton.setOnAction(e -> LockedAccountManager.show(mainStage, authService));
        lockedAccountsButton.setId("manage-locked-accounts");
        stopButton.setDisable(true);

        startButton.setOnAction(e -> startServer());
        stopButton.setOnAction(e -> stopServer());

        Button room = navButton("room", "Tổ chức thi");
        Button classes = navButton("classes", "Lớp & sinh viên");
        Region sidebarSpace = new Region();
        VBox.setVgrow(sidebarSpace, Priority.ALWAYS);
        VBox sidebar = new VBox(7, AppUi.label("LAN Exam", "brand"),
                AppUi.label("Không gian giảng viên", "brand-caption"), classes, questionBankButton, room,
                resultsButton, sidebarSpace,
                AppUi.label("Thi trắc nghiệm\ntrong mạng nội bộ", "sidebar-note"));
        sidebar.setPrefWidth(220);
        sidebar.setMinWidth(220);
        sidebar.getStyleClass().add("sidebar");

        HBox topbar = AppUi.toolbar(connectionState, AppUi.spacer(), teacherLabel, lockedAccountsButton, passwordButton, logoutButton);
        topbar.getStyleClass().add("topbar");
        pageHost = new BorderPane();
        BorderPane workspace = new BorderPane(pageHost);
        HBox controls = AppUi.toolbar(startButton, stopButton, addressLabel); controls.getStyleClass().add("connection-controls");
        VBox connection = new VBox(0, topbar, controls);
        connection.getStyleClass().add("connection-header"); workspace.setTop(connection);
        HBox footer = AppUi.toolbar(statusLabel);
        footer.getStyleClass().add("status-bar");
        workspace.setBottom(footer);
        BorderPane root = new BorderPane(workspace);
        root.setLeft(sidebar);

        pages.put("room", roomPage());
        showPage("classes");
        mainStage.setScene(AppUi.scene(root, 1280, 780));
        mainStage.setMinWidth(1080);
        mainStage.setMinHeight(680);
        mainStage.setTitle("LAN Exam — " + teacher.fullName());
        mainStage.sizeToScene();
        mainStage.show();
    }

    private Button navButton(String key, String text) {
        Button button = new Button(text);
        button.setId("nav-" + key);
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("nav-button");
        button.setOnAction(event -> showPage(key));
        navigation.put(key, button);
        return button;
    }

    private void showPage(String key) {
        Node selected = pages.get(key);
        if (selected == null) {
            selected = switch (key) {
                case "bank" -> {
                    bankBrowser = new QuestionBankBrowser(questionBankService);
                    yield page("Ngân hàng câu hỏi", "Soạn câu hỏi theo môn để dùng chung cho các đề thi.", bankBrowser);
                }
                case "results" -> {
                    examResults = new ExamResultsPane(mainStage, examService, classService);
                    yield page("Kết quả", "Lọc theo lớp, ca thi và xuất bảng điểm CSV.", examResults);
                }
                case "classes" -> {
                    classManager = new ClassManager(mainStage, classService, questionBankService, rows, () -> {
                        if (server != null && server.isRunning()) server.refreshEligibility();
                        if (organizer != null) organizer.reload();
                    });
                    yield page("Lớp & sinh viên", "Tạo danh sách thi và nhập CSV. Một sinh viên có thể thuộc nhiều lớp.", classManager);
                }
                default -> pages.get("room");
            };
            pages.put(key, selected);
        }
        pageHost.setCenter(selected);
        if (key.equals("results") && examResults != null) examResults.reload();
        if (key.equals("room") && organizer != null) organizer.serverStateChanged();
        navigation.forEach((name, button) -> {
            button.getStyleClass().remove("active");
            if (name.equals(key)) button.getStyleClass().add("active");
        });
    }
    private VBox page(String title, String description, Node content) {
        VBox result = new VBox(18, AppUi.heading(title, description), content);
        result.getStyleClass().add("page");
        VBox.setVgrow(content, Priority.ALWAYS);
        return result;
    }

    private VBox roomPage() {
        organizer = new ExamOrganizer(classService, examService, new ExamShiftService(entityManagers, teacher.id()), () -> server, rows, this::warnStudent, this::lockStudent,
                message -> statusLabel.setText(message));
        templateManager = new ExamTemplateManager(mainStage, examService, questionBankService,
                message -> { statusLabel.setText(message); organizer.reload(); });
        Tab sessions = new Tab("Ca thi", organizer); sessions.setId("organizer-session-tab");
        Tab templates = new Tab("Đề thi", templateManager); templates.setId("organizer-templates-tab");
        TabPane tabs = new TabPane(sessions, templates); tabs.setId("exam-organizer-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().selectedItemProperty().addListener((o, old, next) -> { if (next == sessions) organizer.serverStateChanged(); });
        return page("Tổ chức thi", "Chuẩn bị danh sách và đề trước; bấm Bắt đầu thi khi phòng máy sẵn sàng.", tabs);
    }
    private void startServer() {
        long version = workspaceVersion;
        ExamService currentExams = examService;
        AuthService currentAuth = authService;
        startButton.setDisable(true);
        logoutButton.setDisable(true);
        statusLabel.setText("Đang khởi động database và server...");
        connectionState.setText("Đang kết nối...");

        Thread thread = new Thread(() -> {
            try {
                if (version != workspaceVersion) return;
                ExamServer candidate = new ExamServer(registry, currentAuth, currentExams);
                server = candidate;
                candidate.start(requestedPort);
                if (version != workspaceVersion) { candidate.stop(); return; }
                String connectionInfo = ServerAddress.connectionInfo(candidate.getPort());
                Platform.runLater(() -> {
                    if (version != workspaceVersion) return;
                    logoutButton.setDisable(false);
                    statusLabel.setText("Server đang chạy tại cổng " + candidate.getPort());
                    connectionState.setText("Server đang chạy • " + candidate.getPort());
                    addressLabel.setText(connectionInfo);
                    stopButton.setDisable(false);
                    resultsButton.setDisable(false);
                    questionBankButton.setDisable(false);
                    lockedAccountsButton.setDisable(false);
                    if (organizer != null) organizer.serverStateChanged();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (version != workspaceVersion) return;
                    logoutButton.setDisable(false);
                    startButton.setDisable(false);
                    statusLabel.setText("Khởi động thất bại");
                    connectionState.setText("Chưa kết nối");
                    showError("Không thể khởi động Server", ex.getMessage());
                });
            }
        }, "server-startup");
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
        AppUi.style(dialog.getDialogPane());
        dialog.initOwner(mainStage);
        dialog.setTitle(lock ? "Khóa tài khoản" : "Cảnh báo vi phạm");
        dialog.setHeaderText((lock ? "Khóa " : "Cảnh báo ") + student.studentCode() + " — " + student.fullName());
        dialog.setContentText(lock ? "Lý do khóa (tối đa 500 ký tự):" : "Nội dung gửi đến Client (tối đa 1.000 ký tự):");
        if (lock) dialog.getEditor().setTooltip(new Tooltip("Ngắt kết nối và chặn đăng nhập đến khi mở khóa. Đáp án đã nhận vẫn được lưu và chấm theo hạn thi."));
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText(lock ? "Khóa tài khoản" : "Gửi cảnh báo");
        dialog.showAndWait().ifPresent(reason -> {
            organizer.setDisable(true);
            com.ptit.lanexam.common.ui.UiSupport.async("student-moderation", () -> {
                if (lock) activeServer.lockAccount(student.studentCode(), reason);
                else activeServer.warnViolation(student.studentCode(), reason);
                return student.studentCode();
            }, code -> {
                if (version != workspaceVersion) return;
                organizer.setDisable(false);
                statusLabel.setText(lock ? "Đã khóa tài khoản " + code
                        + ". Có thể mở lại trong mục Tài khoản bị khóa."
                        : "Đã gửi cảnh báo đến " + code);
            }, ex -> {
                if (version != workspaceVersion) return;
                organizer.setDisable(false);
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
        UiSupport.async("server-window-close", () -> {
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
        UiSupport.async("teacher-session-change", () -> {
            if (currentServer != null && currentServer.isRunning()) currentServer.stopIfIdle();
            else currentExams.ensureCanStop();
            return true;
        }, done -> {
            if (version != workspaceVersion) return;
            workspace.setDisable(false);
            markServerStopped(); server = null;
            if (logout) {
                workspaceVersion++;
                disposePages();
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
        TeacherAccountDialogs.changePassword(mainStage).ifPresent(input -> UiSupport.async("change-teacher-password", () -> {
            teacherAccounts.changePassword(teacherId, input.currentPassword(), input.newPassword()); return true;
        }, done -> { if (version == workspaceVersion) statusLabel.setText("Đã đổi mật khẩu giáo viên."); },
                ex -> { if (version == workspaceVersion) showError("Không đổi được mật khẩu", ex.getMessage()); }));
    }

    private void markServerStopped() {
        showPage("room");
        if (organizer != null) organizer.serverStateChanged();
        rows.clear();
        logoutButton.setDisable(false);
        statusLabel.setText("Server đã dừng");
        connectionState.setText("Chưa kết nối");
        addressLabel.setText("IP Server sẽ hiển thị khi khởi động Server.");
        startButton.setDisable(false);
        stopButton.setDisable(true);
    }

    private void disposePages() {
        if (classManager != null) classManager.dispose();
        if (bankBrowser != null) bankBrowser.dispose();
        if (templateManager != null) templateManager.dispose();
        if (organizer != null) organizer.dispose();
        if (examResults != null) examResults.dispose();
        classManager = null; bankBrowser = null; templateManager = null; organizer = null; examResults = null;
        pages.clear();
    }
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        AppUi.style(alert.getDialogPane());
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
        disposePages();
        if (teacherLogin != null) teacherLogin.dispose();
        if (server != null) server.stop();
        if (ownsJpa) JpaUtil.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
