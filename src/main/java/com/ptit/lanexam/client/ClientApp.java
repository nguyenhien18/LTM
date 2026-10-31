package com.ptit.lanexam.client;

import com.ptit.lanexam.client.network.LanClient;
import com.ptit.lanexam.common.JsonUtil;
import com.ptit.lanexam.common.MessageType;
import com.ptit.lanexam.common.NetworkMessage;
import com.ptit.lanexam.common.dto.*;
import com.ptit.lanexam.common.ui.AppUi;
import com.ptit.lanexam.common.ui.UiSupport;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
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
    private static final DateTimeFormatter BIRTH_DATE_INPUT = DateTimeFormatter.ofPattern("d/M/uuuu")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private static final DateTimeFormatter BIRTH_DATE_DISPLAY = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private final LanClient client = new LanClient();
    private final Map<Long, Supplier<String>> answerSuppliers = new LinkedHashMap<>();
    private final ExecutorService outbound = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "client-outbound");
        thread.setDaemon(true);
        return thread;
    });

    private Stage stage;
    private Label connectionLabel;
    private Timeline timer;
    private Timeline dashboardTimer;
    private long offerDeadlineNanos;
    private ScheduledExecutorService heartbeat;
    private int remainingSeconds;
    private long deadlineNanos;
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
    private boolean joinPending;
    private Scene dashboardScene;
    private final List<Alert> moderationNotices = new ArrayList<>();
    private BorderPane dashboardHost;
    private FlowPane assessmentCards;
    private VBox examCard;
    private VBox assessmentList;
    private VBox resultsContent;
    private final Map<String, Button> dashboardNavigation = new LinkedHashMap<>();
    private TableView<StudentResult> combinedResults;
    private Alert submitConfirmation;
    private ExamFocusMonitor focusMonitor;
    private Label warningBanner;
    private HBox examWindowWarning;
    private boolean disqualificationPending;
    private record StudentResult(String title, String score, java.time.LocalDateTime submittedAt, String detail) {}

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        focusMonitor = new ExamFocusMonitor(stage, this::sendFocusChange, away -> {
            if (examWindowWarning != null) {
                examWindowWarning.setVisible(away); examWindowWarning.setManaged(away);
            }
        });
        client.setMessageListener(this::onMessage);
        client.setDisconnectListener(message -> {
            int version = connectionVersion;
            Platform.runLater(() -> {
                if (version != connectionVersion) return;
                if (submitConfirmation != null) submitConfirmation.close();
                focusMonitor.stop();
                connectionVersion++;
                answerSuppliers.clear();
                currentExam = null;
                if (timer != null) timer.stop();
                if (dashboardTimer != null) dashboardTimer.stop();
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
        disqualificationPending = false;
        if (focusMonitor != null) focusMonitor.stop();
        stage.setFullScreen(false);
        dashboardVisible = false;
        dashboardScene = null;
        assessmentCards = null;
        combinedResults = null;
        dashboardHost = null;
        Label title = AppUi.label("Đăng nhập sinh viên", "page-title");

        TextField hostField = new TextField(lastHost);
        TextField portField = new TextField(lastPort);
        TextField studentCodeField = new TextField();
        hostField.setId("login-host");
        portField.setId("login-port");
        studentCodeField.setId("login-code");
        studentCodeField.setPromptText("Ví dụ: B21DCCN001");
        DatePicker birthDatePicker = new DatePicker();
        birthDatePicker.setId("login-birth-date");
        birthDatePicker.setPromptText("dd/MM/yyyy");
        birthDatePicker.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(LocalDate date) {
                return date == null ? "" : BIRTH_DATE_DISPLAY.format(date);
            }
            @Override public LocalDate fromString(String text) {
                return text == null || text.isBlank() ? null : LocalDate.parse(text.strip(), BIRTH_DATE_INPUT);
            }
        });
        Button loginButton = new Button("Kết nối và đăng nhập");
        loginButton.setId("login-connect");
        loginButton.getStyleClass().add("primary");
        loginButton.setMaxWidth(Double.MAX_VALUE);
        loginButton.setDefaultButton(true);
        connectionLabel = new Label("Chưa kết nối");
        connectionLabel.getStyleClass().add("muted");

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(12);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(100);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labels, fields);
        birthDatePicker.setMaxWidth(Double.MAX_VALUE);
        form.addRow(0, new Label("Địa chỉ Server"), hostField);
        form.addRow(1, new Label("Cổng kết nối"), portField);
        form.addRow(2, new Label("Mã sinh viên"), studentCodeField);
        form.addRow(3, new Label("Ngày sinh"), birthDatePicker);

        VBox root = new VBox(16, title, form, loginButton, connectionLabel);
        root.setAlignment(Pos.CENTER_LEFT);
        root.getStyleClass().add("login-card");
        root.setMaxWidth(530);
        root.setMaxHeight(Region.USE_PREF_SIZE);

        StackPane wrapper = new StackPane(root);
        wrapper.setPadding(new Insets(20));
        stage.setMinWidth(580);
        stage.setMinHeight(580);
        stage.setScene(AppUi.desktopScene(wrapper, 660, 620));

        loginButton.setOnAction(e -> {
            connectionVersion++;
            String host = hostField.getText().trim();
            String code = studentCodeField.getText().strip().toUpperCase(Locale.ROOT);
            String birthDateText = birthDatePicker.getEditor().getText().strip();
            int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException ex) {
                showAlert(Alert.AlertType.ERROR, "Port không hợp lệ", "Port phải là số nguyên.");
                return;
            }
            if (host.isBlank() || code.isBlank() || birthDateText.isBlank()) {
                String missing = host.isBlank() ? "địa chỉ Server" : code.isBlank() ? "mã sinh viên" : "ngày sinh";
                showAlert(Alert.AlertType.ERROR, "Thiếu dữ liệu", "Chưa nhập " + missing + ".");
                return;
            }

            final LocalDate birthDate;
            try {
                birthDate = LocalDate.parse(birthDateText, BIRTH_DATE_INPUT);
                birthDatePicker.setValue(birthDate);
            } catch (java.time.format.DateTimeParseException ex) {
                showAlert(Alert.AlertType.ERROR, "Ngày sinh không hợp lệ", "Nhập ngày sinh theo dd/MM/yyyy, ví dụ 25/05/2005.");
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
                            new LoginRequest(code, birthDate.toString(), true, true)));
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
                        if (availableExam != null) offerDeadlineNanos = localDeadline(availableExam.durationSeconds(),
                                availableExam.deadlineEpochMillis(), availableExam.serverTimeEpochMillis());
                        if (availableExam == null) joinPending = false;
                        refreshDashboard();
                    }
                    case HISTORY_RESULT -> {
                        history = JsonUtil.payload(message, ExamHistoryResponse.class).items();
                        refreshDashboard();
                    }
                    case EXAM_STARTED -> showExam(JsonUtil.payload(message, ExamDto.class));
                    case SUBMIT_RESULT -> showResult(JsonUtil.payload(message, SubmitResult.class));
                    case VIOLATION_WARNING -> showModerationNotice(message.getPayload().asText(), false);
                    case EXAM_DISQUALIFIED -> {
                        var notice = JsonUtil.payload(message, ExamDisqualification.class);
                        if (currentExam != null && currentExam.getId().equals(notice.examId())) {
                            disqualificationPending = true;
                            if (submitConfirmation != null) submitConfirmation.close();
                            focusMonitor.stop(); if (timer != null) timer.stop();
                            stage.getScene().getRoot().setDisable(true);
                            showModerationNotice("Bài thi bị đình chỉ. Lý do: " + notice.reason(), false);
                        }
                    }
                    case EXAM_WARNING_STATUS -> {
                        var warning = JsonUtil.payload(message, ExamWarningStatus.class);
                        if (currentExam != null && currentExam.getId().equals(warning.examId()) && warningBanner != null) {
                            warningBanner.setText(warning.message() == null || warning.message().isBlank()
                                    ? "Cảnh báo rời màn hình thi: " + warning.count() + " lần"
                                    : warning.message());
                            warningBanner.setVisible(warning.count() > 0); warningBanner.setManaged(warning.count() > 0);
                        }
                    }
                    case ACCOUNT_LOCKED -> {
                        logoutToLogin();
                        showModerationNotice(message.getPayload().asText(), true);
                    }
                    case ERROR -> {
                        if (disqualificationPending) return;
                        if (stage.getScene().lookup("#login-connect") != null) {
                            rejectLogin(message.getPayload().asText());
                            return;
                        }
                        if (currentExam != null && submitButton != null) {
                            submitButton.setDisable(false);
                            submitButton.setText("Nộp bài thi");
                            enterExamScreen(); focusMonitor.start(currentExam.getId());
                        }
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
            rejectLogin(response.getMessage());
            return;
        }

        studentName = response.getStudentName();
        availableExam = null;
        history = List.of();
        joinPending = false;
        startHeartbeat();
        showDashboard();

    }

    private void rejectLogin(String message) {
        connectionVersion++;
        client.close();
        Button login = (Button) stage.getScene().lookup("#login-connect");
        if (login != null) login.setDisable(false);
        connectionLabel.setText("Đăng nhập thất bại");
        showAlert(Alert.AlertType.ERROR, "Đăng nhập thất bại", message);
    }

    private void showDashboard() {
        dashboardVisible = true;
        if (dashboardTimer == null) {
            dashboardTimer = new Timeline(new KeyFrame(Duration.millis(250), event -> refreshOffer()));
            dashboardTimer.setCycleCount(Timeline.INDEFINITE);
        }
        dashboardTimer.play();
        if (dashboardScene != null) {
            stage.setScene(dashboardScene); showStudentPage("assessments"); refreshDashboard(); return;
        }
        dashboardNavigation.clear();
        Button waiting = studentNav("assessments", "Phòng chờ thi"), results = studentNav("results", "Kết quả của tôi");
        Region space = new Region(); VBox.setVgrow(space, Priority.ALWAYS);
        VBox sidebar = new VBox(7, AppUi.label("LAN Exam", "brand"), waiting, results, space);
        sidebar.getStyleClass().add("sidebar"); sidebar.setPrefWidth(185); sidebar.setMinWidth(185);
        Button logout = new Button("Đăng xuất"); logout.setOnAction(event -> logoutToLogin());
        logout.getStyleClass().add("account-button"); logout.setMaxWidth(Double.MAX_VALUE); sidebar.getChildren().add(logout);
        HBox identity = AppUi.toolbar(AppUi.label("Phòng thi LAN", "section-title"), AppUi.spacer(),
                AppUi.label(studentName, "muted"), AppUi.label("Đã kết nối", "badge")); identity.getStyleClass().add("topbar");
        availableLabel = AppUi.label("", "section-title"); availableLabel.setId("exam-offer-label");
        joinButton = new Button("Tham gia bài thi"); joinButton.setId("join-exam"); joinButton.getStyleClass().add("primary");
        joinButton.setOnAction(event -> joinAvailableExam());
        examCard = AppUi.card(availableLabel, joinButton);
        examCard.getStyleClass().add("offer-panel");
        examCard.setMaxWidth(700);
        assessmentCards = new FlowPane(16, 16, examCard); assessmentCards.setId("student-assessments");
        Button refresh = new Button("Làm mới"); refresh.setOnAction(event -> requestHistory());
        assessmentList = new VBox(20, AppUi.toolbar(AppUi.label("Phòng chờ thi", "page-title"),
                AppUi.spacer(), refresh), new Separator(), assessmentCards); assessmentList.getStyleClass().add("page");
        combinedResults = new TableView<>(); combinedResults.setId("student-results");
        combinedResults.getColumns().add(UiSupport.column("Đề / Ca thi", 330, StudentResult::title));
        combinedResults.getColumns().add(UiSupport.column("Điểm", 95, StudentResult::score));
        combinedResults.getColumns().add(UiSupport.column("Số câu đúng", 130, StudentResult::detail));
        combinedResults.getColumns().add(UiSupport.column("Nộp lúc", 170, row -> row.submittedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        combinedResults.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        combinedResults.setPlaceholder(new Label("Bạn chưa có bài thi đã nộp."));
        Button resultRefresh = new Button("Làm mới"); resultRefresh.setOnAction(event -> requestHistory());
        resultsContent = new VBox(18, AppUi.toolbar(AppUi.label("Kết quả của tôi", "page-title"), AppUi.spacer(), resultRefresh), new Separator(), combinedResults);
        resultsContent.getStyleClass().add("page"); VBox.setVgrow(combinedResults, Priority.ALWAYS);
        dashboardHost = new BorderPane(); BorderPane workspace = new BorderPane(dashboardHost); workspace.setTop(identity);
        BorderPane root = new BorderPane(workspace); root.setLeft(sidebar);
        dashboardScene = AppUi.desktopScene(root, 1160, 780); stage.setMinWidth(960); stage.setMinHeight(650); stage.setScene(dashboardScene);
        showStudentPage("assessments"); refreshDashboard();
    }
    private Button studentNav(String key, String title) {
        Button button = new Button(title); button.setId("student-nav-" + key);
        button.getStyleClass().add("nav-button"); button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(event -> showStudentPage(key)); dashboardNavigation.put(key, button);
        return button;
    }

    private void showStudentPage(String key) {
        dashboardHost.setCenter(key.equals("results") ? resultsContent : assessmentList);
        dashboardNavigation.forEach((name, button) -> {
            button.getStyleClass().remove("active");
            if (key.equals(name)) button.getStyleClass().add("active");
        });
    }

    private void refreshDashboard() {
        if (!dashboardVisible || combinedResults == null) return;
        combinedResults.getItems().setAll(history.stream().map(item -> new StudentResult(
                item.examTitle() + " · Ca #" + item.examId(), String.format(Locale.ROOT, "%.2f / 10", item.score()),
                item.submittedAt(), item.correctCount() + "/" + item.totalQuestions())).toList());
        refreshOffer();
    }
    private void refreshOffer() {
        if (!dashboardVisible || joinButton == null) return;
        if (availableExam == null) {
            availableLabel.setText("Chưa có ca thi đang mở. Chờ giảng viên bắt đầu.");
            joinButton.setDisable(true);
        } else {
            int seconds = remaining(offerDeadlineNanos);
            availableLabel.setText(availableExam.title() + "\n"
                    + (availableExam.className() == null ? "" : availableExam.className() + "\n")
                    + (availableExam.subjectName() == null ? "" : availableExam.subjectName() + " · ") + "Ca #" + availableExam.examId()
                    + "\n" + (seconds == 0 ? "Đã hết giờ." : "Còn " + (seconds / 60) + " phút " + (seconds % 60) + " giây"));
            joinButton.setText(seconds == 0 ? "Đã hết giờ" : availableExam.resumable() ? "Tiếp tục làm bài" : "Tham gia bài thi");
            joinButton.setDisable(joinPending || seconds == 0);
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

    private void joinAvailableExam() {
        if (availableExam == null) return;
        if (remaining(offerDeadlineNanos) == 0) { refreshOffer(); return; }
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
        disqualificationPending = false;
        dashboardVisible = false;
        if (dashboardTimer != null) dashboardTimer.stop();
        availableExam = null;
        joinPending = false;
        this.currentExam = exam;
        this.deadlineNanos = localDeadline(exam.getDurationSeconds(), exam.getDeadlineEpochMillis(), exam.getServerTimeEpochMillis());
        this.remainingSeconds = remaining(deadlineNanos);
        answerSuppliers.clear();

        Label title = new Label((exam.getSubjectName() == null ? "" : exam.getSubjectName() + " — ") + exam.getTitle());
        title.setWrapText(true);
        title.setMaxWidth(650);
        title.getStyleClass().add("section-title");
        Label timerLabel = new Label();
        timerLabel.setId("exam-timer");
        timerLabel.setMinWidth(Region.USE_PREF_SIZE);
        timerLabel.getStyleClass().add("timer");
        updateTimerLabel(timerLabel);

        VBox questionBox = new VBox(18);
        questionBox.setPadding(new Insets(18, 24, 24, 24));
        ScrollPane scroll = new ScrollPane(questionBox);
        scroll.setId("exam-questions-scroll");
        scroll.setFitToWidth(true);
        List<VBox> questionCards = new ArrayList<>();
        ExamQuestionNavigator navigator = new ExamQuestionNavigator(exam.getQuestions().size(), target -> {
            questionBox.applyCss(); questionBox.layout();
            double range = questionBox.getHeight() - scroll.getViewportBounds().getHeight();
            double position = questionCards.get(target).getBoundsInParent().getMinY();
            scroll.setVvalue(range <= 0 ? 0 : Math.max(0, Math.min(1, position / range)));
        });

        int index = 1;
        for (QuestionDto q : exam.getQuestions()) {
            int questionIndex = index - 1;
            VBox card = new VBox(8);
            card.setId("question-card-" + index);
            card.getStyleClass().addAll("card", "question-card");
            Label content = new Label("Câu " + index++ + ": " + q.getContent());
            content.setWrapText(true);
            content.setStyle("-fx-font-weight: bold;");
            content.setMinWidth(0);
            HBox.setHgrow(content, Priority.ALWAYS);
            CheckBox review = new CheckBox("Xem lại");
            review.setId("question-flag-" + (questionIndex + 1));
            review.setMinWidth(Region.USE_PREF_SIZE);
            review.setOnAction(event -> navigator.setFlagged(questionIndex, review.isSelected()));
            card.getChildren().add(AppUi.toolbar(content, AppUi.spacer(), review));

            if ("MULTIPLE_CHOICE".equals(q.getType())) {
                ToggleGroup group = new ToggleGroup();
                for (String option : q.getOptions()) {
                    RadioButton rb = new RadioButton(option);
                    rb.setToggleGroup(group);
                    rb.setWrapText(true);
                    String saved = exam.getSavedAnswers().get(q.getId());
                    if (saved != null && !saved.isEmpty() && option.startsWith(saved + ".")) rb.setSelected(true);
                    card.getChildren().add(rb);
                }
                navigator.setAnswered(questionIndex, group.getSelectedToggle() != null);
                group.selectedToggleProperty().addListener((obs, old, selected) -> {
                    navigator.setAnswered(questionIndex, selected != null); updateAnswers();
                });
                answerSuppliers.put(q.getId(), () -> {
                    Toggle selected = group.getSelectedToggle();
                    if (selected == null) return "";
                    String text = ((RadioButton) selected).getText();
                    return text.isEmpty() ? "" : text.substring(0, 1);
                });
            } else {
                TextField answer = new TextField();
                answer.setText(exam.getSavedAnswers().getOrDefault(q.getId(), ""));
                navigator.setAnswered(questionIndex, !answer.getText().isBlank());
                answer.setPromptText("Nhập đáp án");
                card.getChildren().add(answer);
                answer.textProperty().addListener((obs, old, value) -> {
                    navigator.setAnswered(questionIndex, !value.isBlank()); updateAnswers();
                });
                answerSuppliers.put(q.getId(), () -> answer.getText().trim());
            }
            questionBox.getChildren().add(card);
            questionCards.add(card);
        }

        submitButton = new Button("Nộp bài thi");
        submitButton.getStyleClass().add("primary");
        submitButton.setId("exam-submit");
        submitButton.setOnAction(e -> submitCurrentExam(false));

        HBox top = new HBox(20, title, new Region(), timerLabel);
        HBox.setHgrow(top.getChildren().get(1), Priority.ALWAYS);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getStyleClass().add("topbar");

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setLeft(navigator);
        root.setCenter(scroll);
        HBox bottom = AppUi.toolbar(AppUi.label("Kiểm tra đáp án trước khi nộp. Bài đã nộp không thể sửa.", "muted"),
                AppUi.spacer(), submitButton);
        bottom.getStyleClass().add("status-bar");
        warningBanner = new Label(); warningBanner.setId("exam-warning-banner"); warningBanner.setWrapText(true);
        warningBanner.setStyle("-fx-text-fill: #9a5b00; -fx-padding: 8 16;");
        warningBanner.setVisible(false); warningBanner.setManaged(false);
        Button restoreScreen = new Button("Trở lại toàn màn hình");
        restoreScreen.setId("exam-restore-fullscreen"); restoreScreen.setOnAction(e -> enterExamScreen());
        Label screenMessage = new Label("Rời màn hình thi từ 5 giây sẽ báo ngay cho giảng viên."); screenMessage.setWrapText(true);
        examWindowWarning = AppUi.toolbar(screenMessage, AppUi.spacer(), restoreScreen);
        examWindowWarning.setId("exam-window-warning");
        examWindowWarning.setVisible(false); examWindowWarning.setManaged(false);
        root.setBottom(new VBox(examWindowWarning, warningBanner, bottom));
        stage.setScene(AppUi.desktopScene(root, 1000, 760));
        enterExamScreen();
        focusMonitor.start(exam.getId());

        if (timer != null) timer.stop();
        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            remainingSeconds = remaining(deadlineNanos);
            updateTimerLabel(timerLabel);
            if (remainingSeconds <= 0) {
                timer.stop();
                submitCurrentExam(true);
            }
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private void enterExamScreen() {
        stage.setIconified(false);
        stage.setFullScreenExitHint("Esc: thoát toàn màn hình. Rời màn hình thi từ 5 giây sẽ báo ngay cho giảng viên.");
        stage.setFullScreen(true);
        stage.requestFocus();
    }

    private void submitCurrentExam(boolean automatic) {
        if (currentExam == null || submitButton == null || submitButton.isDisabled()) return;
        if (automatic && submitConfirmation != null) submitConfirmation.close();
        if (!automatic) {
            long unanswered = snapshotAnswers().values().stream().filter(value -> value == null || value.isBlank()).count();
            if (unanswered > 0) {
                ExamDto pendingExam = currentExam;
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.initOwner(stage); AppUi.desktopStyle(confirm.getDialogPane());
                confirm.getDialogPane().setId("exam-submit-confirmation");
                confirm.setTitle("Kiểm tra trước khi nộp");
                confirm.setHeaderText("Bạn còn " + unanswered + " câu chưa trả lời.");
                confirm.setContentText("Nộp bài ngay hoặc quay lại hoàn thành các câu còn thiếu.");
                ButtonType submit = new ButtonType("Nộp bài", ButtonBar.ButtonData.OK_DONE);
                ButtonType back = new ButtonType("Quay lại", ButtonBar.ButtonData.CANCEL_CLOSE);
                confirm.getButtonTypes().setAll(submit, back);
                submitConfirmation = confirm;
                var selected = confirm.showAndWait();
                submitConfirmation = null;
                if (selected.orElse(back) != submit || currentExam != pendingExam || submitButton.isDisabled()) return;
            }
        }
        submitButton.setDisable(true);
        focusMonitor.stop();
        submitButton.setText("Đang nộp...");
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
                    submitButton.setText("Nộp bài thi");
                    enterExamScreen(); focusMonitor.start(currentExam.getId());
                    showAlert(Alert.AlertType.ERROR, "Không gửi được bài", ex.getMessage());
                });
            }
        });

        // The server grades the current session at the common deadline.
    }

    private Map<Long, String> snapshotAnswers() {
        Map<Long, String> answers = new LinkedHashMap<>();
        answerSuppliers.forEach((questionId, supplier) -> answers.put(questionId, supplier.get()));
        return answers;
    }

    private void updateAnswers() {
        if (currentExam == null) return;
        SubmitExamRequest answers = new SubmitExamRequest(currentExam.getId(), snapshotAnswers());
        int version = connectionVersion;
        outbound.execute(() -> {
            if (version == connectionVersion) {
                try { client.send(JsonUtil.message(MessageType.ANSWER_UPDATE, answers)); }
                catch (Exception ignored) { /* The disconnect listener handles a lost connection. */ }
            }
        });
    }

    private void showResult(SubmitResult result) {
        focusMonitor.stop();
        stage.setFullScreen(false);
        if (submitConfirmation != null) submitConfirmation.close();
        dashboardVisible = false;
        if (dashboardTimer != null) dashboardTimer.stop();
        if (timer != null) timer.stop();
        currentExam = null;
        Label title = new Label(disqualificationPending ? "Vi phạm quy chế" : "Đã nộp bài");
        disqualificationPending = false;
        title.getStyleClass().add("page-title");
        Label score = new Label(String.format("Điểm: %.2f / 10", result.getScore()));
        score.getStyleClass().add("score");
        Label detail = new Label("Đúng " + result.getCorrectCount() + "/" + result.getTotalQuestions() + " câu");
        Label message = new Label(result.getMessage());
        message.setWrapText(true); message.setMaxWidth(560);

        Button waitButton = new Button("Về trang chính");
        waitButton.getStyleClass().add("primary");
        waitButton.setOnAction(e -> showDashboard());
        Button switchAccount = new Button("Đăng xuất");
        switchAccount.setOnAction(e -> logoutToLogin());
        HBox actions = new HBox(12, waitButton, switchAccount);
        actions.setAlignment(Pos.CENTER);

        VBox root = AppUi.card(title, score, detail, message, actions);
        root.setAlignment(Pos.CENTER);
        root.setMaxSize(620, Region.USE_PREF_SIZE);
        StackPane wrapper = new StackPane(root);
        wrapper.setPadding(new Insets(28));
        stage.setScene(AppUi.desktopScene(wrapper, 900, 650));
    }

    private void logoutToLogin() {
        focusMonitor.stop();
        if (submitConfirmation != null) submitConfirmation.close();
        connectionVersion++;
        for (Alert notice : List.copyOf(moderationNotices)) notice.close();
        if (timer != null) timer.stop();
        if (dashboardTimer != null) dashboardTimer.stop();
        stopHeartbeat();
        currentExam = null;
        submitButton = null;
        studentName = null;
        availableExam = null;
        history = List.of();
        joinPending = false;
        dashboardVisible = false;
        answerSuppliers.clear();
        client.close();
        showLoginScene();
    }

    private void updateTimerLabel(Label label) {
        int safe = Math.max(remainingSeconds, 0);
        int minutes = safe / 60;
        int seconds = safe % 60;
        label.setText(String.format("Còn lại: %02d:%02d", minutes, seconds));
        label.getStyleClass().remove("urgent");
        if (safe <= 60) label.getStyleClass().add("urgent");
    }

    private static long localDeadline(int seconds, long deadlineMillis, long serverMillis) {
        long remainingMillis = deadlineMillis > 0 && serverMillis > 0
                ? Math.max(0, deadlineMillis - serverMillis) : Math.max(0L, seconds) * 1000;
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingMillis);
    }

    private static int remaining(long deadline) {
        return (int) Math.max(0, Math.ceil((deadline - System.nanoTime()) / 1_000_000_000.0));
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
        alert.initOwner(stage);
        alert.getDialogPane().setId("client-alert");
        AppUi.desktopStyle(alert.getDialogPane());
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message == null ? "" : message);
        alert.showAndWait();
    }

    private void showModerationNotice(String message, boolean locked) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        AppUi.desktopStyle(alert.getDialogPane());
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
        if (focusMonitor != null) focusMonitor.close();
        if (submitConfirmation != null) submitConfirmation.close();
        for (Alert notice : List.copyOf(moderationNotices)) notice.close();
        if (timer != null) timer.stop();
        if (dashboardTimer != null) dashboardTimer.stop();
        stopHeartbeat();
        client.close();
        outbound.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private void sendFocusChange(ExamFocusChange change) {
        int version = connectionVersion;
        outbound.execute(() -> {
            if (version != connectionVersion) return;
            try { client.send(JsonUtil.message(MessageType.EXAM_FOCUS_CHANGED, change)); }
            catch (Exception ignored) { /* Connection failures are displayed by the connection listener. */ }
        });
    }
}
