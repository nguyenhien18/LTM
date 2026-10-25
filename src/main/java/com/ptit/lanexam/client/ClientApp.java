package com.ptit.lanexam.client;

import com.ptit.lanexam.client.network.LanClient;
import com.ptit.lanexam.common.JsonUtil;
import com.ptit.lanexam.common.MessageType;
import com.ptit.lanexam.common.NetworkMessage;
import com.ptit.lanexam.common.dto.*;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class ClientApp extends Application {
    private final LanClient client = new LanClient();
    private final Map<Long, Supplier<String>> answerSuppliers = new LinkedHashMap<>();
    private final ExecutorService outbound = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "client-outbound");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, String> localAnswers = new LinkedHashMap<>();

    private Stage stage;
    private Label connectionLabel;
    private Timeline timer;
    private PauseTransition draftDebounce;
    private ScheduledExecutorService heartbeat;
    private int remainingSeconds;
    private long deadlineNanos;
    private Long localExamId;
    private volatile int connectionVersion;
    private ExamDto currentExam;
    private Button submitButton;
    private String lastHost = "127.0.0.1";
    private String lastPort = "5000";
    private String studentName;
    private ExamOffer availableExam;
    private List<ExamHistoryItem> history = List.of();
    private boolean dashboardVisible;
    private Label availableLabel;
    private Button joinButton;
    private TableView<ExamHistoryItem> historyTable;
    private boolean joinPending;
    private AssignmentPane assignmentPane;
    private Scene dashboardScene;
    private List<AssignmentInfo> assignments = List.of();
    private final List<Alert> moderationNotices = new ArrayList<>();

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        client.setMessageListener(this::onMessage);
        client.setDisconnectListener(message -> {
            int version = connectionVersion;
            Platform.runLater(() -> {
                if (version != connectionVersion) return;
                connectionVersion++;
                if (currentExam != null) {
                    localExamId = currentExam.getId();
                    localAnswers.clear();
                    localAnswers.putAll(snapshotAnswers());
                }
                currentExam = null;
                if (timer != null) timer.stop();
                if (draftDebounce != null) draftDebounce.stop();
                stopHeartbeat();
                showAlert(Alert.AlertType.WARNING, "Mất kết nối", "Đã mất kết nối với Server: " + message);
                showLoginScene();
            });
        });

        showLoginScene();
        stage.setTitle("LAN Exam Client");
        stage.show();
    }

    private void showLoginScene() {
        dashboardVisible = false;
        if (assignmentPane != null) assignmentPane.dispose();
        assignmentPane = null;
        dashboardScene = null;
        assignments = List.of();
        Label title = new Label("Đăng nhập hệ thống thi LAN");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");

        TextField hostField = new TextField(lastHost);
        TextField portField = new TextField(lastPort);
        TextField studentCodeField = new TextField();
        hostField.setId("login-host");
        portField.setId("login-port");
        studentCodeField.setId("login-code");
        studentCodeField.setPromptText("Ví dụ: B21DCCN001");
        DatePicker birthDatePicker = new DatePicker(LocalDate.of(2003, 5, 12));
        birthDatePicker.setId("login-birth-date");
        Button loginButton = new Button("Kết nối và đăng nhập");
        loginButton.setId("login-connect");
        connectionLabel = new Label("Chưa kết nối");

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.addRow(0, new Label("IP Server:"), hostField);
        form.addRow(1, new Label("Port:"), portField);
        form.addRow(2, new Label("Mã sinh viên:"), studentCodeField);
        form.addRow(3, new Label("Ngày sinh:"), birthDatePicker);

        VBox root = new VBox(18, title, form, loginButton, connectionLabel);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(28));
        root.setMaxWidth(520);

        StackPane wrapper = new StackPane(root);
        wrapper.setPadding(new Insets(20));
        stage.setScene(new Scene(wrapper, 620, 420));

        loginButton.setOnAction(e -> {
            connectionVersion++;
            String host = hostField.getText().trim();
            String code = studentCodeField.getText().trim().toUpperCase();
            LocalDate birthDate = birthDatePicker.getValue();
            int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException ex) {
                showAlert(Alert.AlertType.ERROR, "Port không hợp lệ", "Port phải là số nguyên.");
                return;
            }
            if (host.isBlank() || code.isBlank() || birthDate == null) {
                showAlert(Alert.AlertType.ERROR, "Thiếu dữ liệu", "Vui lòng nhập đủ IP, mã sinh viên và ngày sinh.");
                return;
            }

            lastHost = host;
            lastPort = Integer.toString(port);

            loginButton.setDisable(true);
            connectionLabel.setText("Đang kết nối...");
            final int finalPort = port;
            final int version = connectionVersion;
            Thread thread = new Thread(() -> {
                try {
                    client.connect(host, finalPort);
                    client.send(JsonUtil.message(MessageType.LOGIN,
                            new LoginRequest(code, birthDate.toString())));
                    Platform.runLater(() -> {
                        if (version == connectionVersion) connectionLabel.setText("Đã kết nối, đang xác thực...");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        if (version != connectionVersion) return;
                        loginButton.setDisable(false);
                        connectionLabel.setText("Kết nối thất bại");
                        showAlert(Alert.AlertType.ERROR, "Không kết nối được", ex.getMessage());
                    });
                }
            }, "client-connect");
            thread.setDaemon(true);
            thread.start();
        });
    }

    private void onMessage(NetworkMessage message) {
        int version = connectionVersion;
        Platform.runLater(() -> {
            if (version != connectionVersion) return;
            try {
                switch (message.getType()) {
                    case LOGIN_RESULT -> handleLoginResult(JsonUtil.payload(message, LoginResponse.class));
                    case EXAM_AVAILABLE -> {
                        availableExam = JsonUtil.payload(message, ExamAvailability.class).exam();
                        if (availableExam == null) joinPending = false;
                        refreshDashboard();
                    }
                    case HISTORY_RESULT -> {
                        history = JsonUtil.payload(message, ExamHistoryResponse.class).items();
                        refreshDashboard();
                    }
                    case EXAM_STARTED -> showExam(JsonUtil.payload(message, ExamDto.class));
                    case SUBMIT_RESULT -> showResult(JsonUtil.payload(message, SubmitResult.class));
                    case ASSIGNMENTS_RESULT -> {
                        assignments = JsonUtil.payload(message, AssignmentMessages.ListResult.class).items();
                        if (assignmentPane != null) assignmentPane.setItems(assignments);
                    }
                    case ASSIGNMENTS_CHANGED -> {
                        if (assignmentPane != null) assignmentPane.requestList();
                    }
                    case ASSIGNMENT_SUBMITTED -> {
                        if (assignmentPane != null)
                            assignmentPane.submitted(JsonUtil.payload(message, AssignmentSubmissionInfo.class));
                    }
                    case ASSIGNMENT_FILE_RESULT -> {
                        if (assignmentPane != null)
                            assignmentPane.receivedFile(JsonUtil.payload(message, AssignmentMessages.FileResult.class));
                    }
                    case ASSIGNMENT_ERROR -> {
                        if (assignmentPane != null) assignmentPane.fail(message.getPayload().asText());
                    }
                    case VIOLATION_WARNING -> showModerationNotice(message.getPayload().asText(), false);
                    case ACCOUNT_LOCKED -> {
                        logoutToLogin();
                        showModerationNotice(message.getPayload().asText(), true);
                    }
                    case ERROR -> {
                        joinPending = false;
                        refreshDashboard();
                        showAlert(Alert.AlertType.ERROR, "Server báo lỗi", message.getPayload().asText());
                    }
                    case PONG -> { }
                    default -> { }
                }
            } catch (Exception e) {
                showAlert(Alert.AlertType.ERROR, "Lỗi xử lý dữ liệu", e.getMessage());
            }
        });
    }

    private void handleLoginResult(LoginResponse response) {
        if (!response.isSuccess()) {
            showAlert(Alert.AlertType.ERROR, "Đăng nhập thất bại", response.getMessage());
            client.close();
            showLoginScene();
            return;
        }

        studentName = response.getStudentName();
        availableExam = null;
        history = List.of();
        joinPending = false;
        startHeartbeat();
        showDashboard();
    }

    private void showDashboard() {
        dashboardVisible = true;
        if (dashboardScene != null) {
            stage.setScene(dashboardScene);
            refreshDashboard();
            return;
        }
        Label title = new Label("Xin chào, " + studentName);
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        Label subtitle = new Label("Tham gia bài thi và nộp bài tập do giảng viên giao");

        availableLabel = new Label();
        availableLabel.setWrapText(true);
        joinButton = new Button("Tham gia bài thi");
        joinButton.setId("join-exam");
        joinButton.setOnAction(e -> joinAvailableExam());
        HBox offer = new HBox(20, availableLabel, joinButton);
        offer.setAlignment(Pos.CENTER_LEFT);
        offer.setPadding(new Insets(16));
        offer.setStyle("-fx-border-color: #cccccc; -fx-border-radius: 6;");
        HBox.setHgrow(availableLabel, Priority.ALWAYS);

        historyTable = new TableView<>();
        historyTable.getColumns().add(historyColumn("Mã lượt", 85,
                item -> Long.toString(item.examId())));
        historyTable.getColumns().add(historyColumn("Đề thi", 260, ExamHistoryItem::examTitle));
        historyTable.getColumns().add(historyColumn("Điểm", 95,
                item -> String.format(Locale.ROOT, "%.2f / 10", item.score())));
        historyTable.getColumns().add(historyColumn("Số câu đúng", 105,
                item -> item.correctCount() + "/" + item.totalQuestions()));
        DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        historyTable.getColumns().add(historyColumn("Thời gian nộp", 170,
                item -> item.submittedAt().format(timeFormat)));
        historyTable.setPlaceholder(new Label("Chưa có bài thi đã nộp"));
        Button refreshButton = new Button("Làm mới trang");
        refreshButton.setOnAction(e -> {
            requestHistory();
            if (assignmentPane != null) assignmentPane.requestList();
        });
        Button logoutButton = new Button("Đổi tài khoản / Đăng xuất");
        logoutButton.setOnAction(e -> logoutToLogin());

        VBox examContent = new VBox(14, offer, new Label("Lịch sử làm bài"), historyTable);
        examContent.setPadding(new Insets(12));
        assignmentPane = new AssignmentPane(stage, this::sendAssignmentMessage);
        assignmentPane.setItems(assignments);
        Tab examsTab = new Tab("Bài thi", examContent);
        Tab assignmentsTab = new Tab("Bài tập", assignmentPane);
        TabPane tabs = new TabPane(examsTab, assignmentsTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox root = new VBox(14, title, subtitle, tabs, new HBox(12, refreshButton, logoutButton));
        root.setPadding(new Insets(22));
        VBox.setVgrow(historyTable, Priority.ALWAYS);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        dashboardScene = new Scene(root, 950, 740);
        stage.setScene(dashboardScene);
        refreshDashboard();
    }

    private TableColumn<ExamHistoryItem, String> historyColumn(String title, int width,
            java.util.function.Function<ExamHistoryItem, String> value) {
        TableColumn<ExamHistoryItem, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }

    private void refreshDashboard() {
        if (!dashboardVisible) return;
        historyTable.getItems().setAll(history);
        if (availableExam == null) {
            availableLabel.setText("Chưa có bài thi nào đang mở cho bạn. Hãy chờ giảng viên mở bài.");
            joinButton.setDisable(true);
        } else {
            availableLabel.setText("Đợt #" + availableExam.examId() + " — "
                    + (availableExam.subjectName() == null ? "" : availableExam.subjectName() + " — ") + availableExam.title() + " — "
                    + (availableExam.resumable() ? "Tiếp tục bài đang làm" : "Thời gian: "
                    + (availableExam.durationSeconds() / 60) + " phút"));
            joinButton.setText(availableExam.resumable() ? "Tiếp tục bài thi" : "Tham gia bài thi");
            joinButton.setDisable(joinPending);
        }
    }

    private void requestHistory() {
        int version = connectionVersion;
        outbound.execute(() -> {
            if (version != connectionVersion) return;
            try { client.send(JsonUtil.message(MessageType.HISTORY_REQUEST, null)); }
            catch (Exception ignored) { /* Disconnect listener returns to login when needed. */ }
        });
    }

    private void sendAssignmentMessage(NetworkMessage message) {
        int version = connectionVersion;
        AssignmentPane source = assignmentPane;
        outbound.execute(() -> {
            if (version != connectionVersion) return;
            try { client.send(message); }
            catch (Exception ex) {
                Platform.runLater(() -> {
                    if (version == connectionVersion && source != null) source.fail(ex.getMessage());
                });
            }
        });
    }

    private void joinAvailableExam() {
        if (availableExam == null) return;
        Long examId = availableExam.examId();
        joinPending = true;
        joinButton.setDisable(true);
        int version = connectionVersion;
        outbound.execute(() -> {
            if (version != connectionVersion) return;
            try { client.send(JsonUtil.message(MessageType.JOIN_EXAM, new JoinExamRequest(examId))); }
            catch (Exception ex) {
                Platform.runLater(() -> {
                    if (version != connectionVersion) return;
                    joinPending = false;
                    refreshDashboard();
                    showAlert(Alert.AlertType.ERROR, "Không thể tham gia bài thi", ex.getMessage());
                });
            }
        });
    }

    private void showExam(ExamDto exam) {
        dashboardVisible = false;
        availableExam = null;
        joinPending = false;
        Map<Long, String> currentValues = currentExam != null && exam.getId().equals(currentExam.getId())
                ? snapshotAnswers() : Map.of();
        this.currentExam = exam;
        this.deadlineNanos = System.nanoTime() + Math.max(0, exam.getDurationSeconds()) * 1_000_000_000L;
        this.remainingSeconds = exam.getDurationSeconds();
        answerSuppliers.clear();
        Map<Long, String> restored = new LinkedHashMap<>(exam.getSavedAnswers());
        restored.putAll(currentValues);
        if (exam.getId().equals(localExamId)) restored.putAll(localAnswers);
        localAnswers.clear();
        localExamId = null;
        if (draftDebounce != null) draftDebounce.stop();
        draftDebounce = new PauseTransition(Duration.millis(600));
        draftDebounce.setOnFinished(e -> saveDraft());

        Label title = new Label((exam.getSubjectName() == null ? "" : exam.getSubjectName() + " — ") + exam.getTitle());
        title.setWrapText(true);
        title.setMaxWidth(650);
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        Label timerLabel = new Label();
        timerLabel.setId("exam-timer");
        timerLabel.setMinWidth(Region.USE_PREF_SIZE);
        timerLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        updateTimerLabel(timerLabel);

        VBox questionBox = new VBox(18);
        questionBox.setPadding(new Insets(10));

        int index = 1;
        for (QuestionDto q : exam.getQuestions()) {
            VBox card = new VBox(8);
            card.setPadding(new Insets(12));
            card.setStyle("-fx-border-color: #cccccc; -fx-border-radius: 6; -fx-background-radius: 6;");
            Label content = new Label("Câu " + index++ + ": " + q.getContent());
            content.setWrapText(true);
            content.setStyle("-fx-font-weight: bold;");
            card.getChildren().add(content);

            if ("MULTIPLE_CHOICE".equals(q.getType())) {
                ToggleGroup group = new ToggleGroup();
                String savedAnswer = restored.get(q.getId());
                for (String option : q.getOptions()) {
                    RadioButton rb = new RadioButton(option);
                    rb.setToggleGroup(group);
                    rb.setWrapText(true);
                    if (savedAnswer != null && option.startsWith(savedAnswer + ".")) rb.setSelected(true);
                    card.getChildren().add(rb);
                }
                group.selectedToggleProperty().addListener((obs, old, selected) -> draftDebounce.playFromStart());
                answerSuppliers.put(q.getId(), () -> {
                    Toggle selected = group.getSelectedToggle();
                    if (selected == null) return "";
                    String text = ((RadioButton) selected).getText();
                    return text.isEmpty() ? "" : text.substring(0, 1);
                });
            } else {
                TextField answer = new TextField();
                answer.setText(restored.getOrDefault(q.getId(), ""));
                answer.textProperty().addListener((obs, old, value) -> draftDebounce.playFromStart());
                answer.setPromptText("Nhập đáp án");
                card.getChildren().add(answer);
                answerSuppliers.put(q.getId(), () -> answer.getText().trim());
            }
            questionBox.getChildren().add(card);
        }

        submitButton = new Button("NỘP BÀI");
        submitButton.setId("exam-submit");
        submitButton.setOnAction(e -> submitCurrentExam(false));

        HBox top = new HBox(20, title, new Region(), timerLabel);
        HBox.setHgrow(top.getChildren().get(1), Priority.ALWAYS);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(14));

        ScrollPane scroll = new ScrollPane(questionBox);
        scroll.setFitToWidth(true);
        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(scroll);
        HBox bottom = new HBox(submitButton);
        bottom.setAlignment(Pos.CENTER_RIGHT);
        bottom.setPadding(new Insets(14));
        root.setBottom(bottom);
        stage.setScene(new Scene(root, 860, 650));

        if (timer != null) timer.stop();
        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            remainingSeconds = (int) Math.max(0, Math.ceil((deadlineNanos - System.nanoTime()) / 1_000_000_000.0));
            updateTimerLabel(timerLabel);
            if (remainingSeconds <= 0) {
                timer.stop();
                submitCurrentExam(true);
            }
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        if (!restored.equals(exam.getSavedAnswers())) draftDebounce.playFromStart();
    }

    private void submitCurrentExam(boolean automatic) {
        if (currentExam == null || submitButton == null || submitButton.isDisabled()) return;
        submitButton.setDisable(true);
        submitButton.setText("Đang nộp...");
        if (timer != null) timer.stop();
        if (draftDebounce != null) draftDebounce.stop();

        SubmitExamRequest request = new SubmitExamRequest(currentExam.getId(), snapshotAnswers());
        int version = connectionVersion;
        outbound.execute(() -> {
            if (version != connectionVersion) return;
            try {
                client.send(JsonUtil.message(MessageType.SUBMIT_EXAM, request));
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (version != connectionVersion) return;
                    submitButton.setDisable(false);
                    submitButton.setText("NỘP BÀI");
                    showAlert(Alert.AlertType.ERROR, "Không gửi được bài", ex.getMessage());
                });
            }
        });

        // The server decides whether a late request uses the last saved draft.
    }

    private Map<Long, String> snapshotAnswers() {
        Map<Long, String> answers = new LinkedHashMap<>();
        answerSuppliers.forEach((questionId, supplier) -> answers.put(questionId, supplier.get()));
        return answers;
    }

    private void saveDraft() {
        if (currentExam == null || submitButton == null || submitButton.isDisabled()) return;
        SubmitExamRequest draft = new SubmitExamRequest(currentExam.getId(), snapshotAnswers());
        int version = connectionVersion;
        outbound.execute(() -> {
            if (version == connectionVersion) {
                try { client.send(JsonUtil.message(MessageType.SAVE_DRAFT, draft)); }
                catch (Exception ignored) { /* The disconnect listener handles a lost connection. */ }
            }
        });
    }

    private void showResult(SubmitResult result) {
        dashboardVisible = false;
        if (timer != null) timer.stop();
        if (draftDebounce != null) draftDebounce.stop();
        currentExam = null;
        localAnswers.clear();
        localExamId = null;
        Label title = new Label("Đã nộp bài");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        Label score = new Label(String.format("Điểm: %.2f / 10", result.getScore()));
        score.setStyle("-fx-font-size: 28px; -fx-font-weight: bold;");
        Label detail = new Label("Đúng " + result.getCorrectCount() + "/" + result.getTotalQuestions() + " câu");
        Label message = new Label(result.getMessage());

        Button waitButton = new Button("Về trang chính");
        waitButton.setOnAction(e -> showDashboard());
        Button switchAccount = new Button("Đổi tài khoản / Đăng xuất");
        switchAccount.setOnAction(e -> logoutToLogin());
        HBox actions = new HBox(12, waitButton, switchAccount);
        actions.setAlignment(Pos.CENTER);

        VBox root = new VBox(16, title, score, detail, message, actions);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        stage.setScene(new Scene(root, 650, 420));
    }

    private void logoutToLogin() {
        connectionVersion++;
        for (Alert notice : List.copyOf(moderationNotices)) notice.close();
        if (timer != null) timer.stop();
        if (draftDebounce != null) draftDebounce.stop();
        stopHeartbeat();
        currentExam = null;
        submitButton = null;
        studentName = null;
        availableExam = null;
        history = List.of();
        joinPending = false;
        dashboardVisible = false;
        answerSuppliers.clear();
        localAnswers.clear();
        localExamId = null;
        client.close();
        showLoginScene();
    }

    private void updateTimerLabel(Label label) {
        int safe = Math.max(remainingSeconds, 0);
        int minutes = safe / 60;
        int seconds = safe % 60;
        label.setText(String.format("Còn lại: %02d:%02d", minutes, seconds));
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "client-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            try { client.send(JsonUtil.message(MessageType.PING, null)); } catch (Exception ignored) {}
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeat != null) heartbeat.shutdownNow();
        heartbeat = null;
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message == null ? "" : message);
        alert.showAndWait();
    }

    private void showModerationNotice(String message, boolean locked) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.initOwner(stage);
        alert.setTitle(locked ? "Tài khoản đã bị khóa" : "Cảnh báo vi phạm từ giảng viên");
        alert.setHeaderText(alert.getTitle());
        alert.setContentText(message);
        alert.getDialogPane().setPrefWidth(540);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        alert.getDialogPane().setId(locked ? "account-locked-notice" : "violation-warning-notice");
        moderationNotices.add(alert);
        alert.setOnHidden(event -> moderationNotices.remove(alert));
        alert.show(); // Keep the exam timer and subsequent server messages running.
    }

    @Override
    public void stop() {
        for (Alert notice : List.copyOf(moderationNotices)) notice.close();
        if (timer != null) timer.stop();
        if (assignmentPane != null) assignmentPane.dispose();
        stopHeartbeat();
        client.close();
        outbound.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
