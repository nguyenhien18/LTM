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
import javafx.stage.Stage;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public class ServerApp extends Application {
    private final ObservableList<ClientSessionInfo> rows = FXCollections.observableArrayList();
    private final SessionRegistry registry = new SessionRegistry();

    private ExamServer server;
    private ExamService examService;
    private AssignmentService assignmentService;
    private Label statusLabel;
    private Button startButton;
    private Button stopButton;
    private Button publishButton;
    private Button createButton;
    private Button resultsButton;
    private Button assignmentsButton;
    private Stage mainStage;

    @Override
    public void start(Stage stage) {
        mainStage = stage;
        registry.setListener(snapshot -> Platform.runLater(() -> rows.setAll(snapshot)));

        Label title = new Label("LAN Exam Server - Giảng viên");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        statusLabel = new Label("Server chưa chạy");

        VBox header = new VBox(6, title, statusLabel);
        header.setPadding(new Insets(16));

        TableView<ClientSessionInfo> table = new TableView<>(rows);
        TableColumn<ClientSessionInfo, String> codeCol = new TableColumn<>("Mã sinh viên");
        codeCol.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().studentCode()));
        TableColumn<ClientSessionInfo, String> nameCol = new TableColumn<>("Họ tên");
        nameCol.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().fullName()));
        TableColumn<ClientSessionInfo, String> ipCol = new TableColumn<>("IP");
        ipCol.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().ip()));
        TableColumn<ClientSessionInfo, String> stateCol = new TableColumn<>("Trạng thái");
        stateCol.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().status()));
        codeCol.setPrefWidth(150);
        nameCol.setPrefWidth(220);
        ipCol.setPrefWidth(160);
        stateCol.setPrefWidth(150);
        table.getColumns().addAll(codeCol, nameCol, ipCol, stateCol);
        table.setPlaceholder(new Label("Chưa có sinh viên online"));

        startButton = new Button("Khởi động Server");
        stopButton = new Button("Dừng Server");
        publishButton = new Button("Chọn bài thi để mở");
        createButton = new Button("Tạo đề thi");
        resultsButton = new Button("Xem điểm");
        assignmentsButton = new Button("Bài tập / Bài nộp");
        stopButton.setDisable(true);
        publishButton.setDisable(true);
        createButton.setDisable(true);
        resultsButton.setDisable(true);
        assignmentsButton.setDisable(true);

        startButton.setOnAction(e -> startServer());
        stopButton.setOnAction(e -> stopServer());
        publishButton.setOnAction(e -> publishExam());
        createButton.setOnAction(e -> createExam());
        resultsButton.setOnAction(e -> showResults());
        assignmentsButton.setOnAction(e -> {
            ExamServer activeServer = server;
            new AssignmentManager(mainStage, assignmentService, activeServer::notifyAssignmentsChanged);
        });

        FlowPane actions = new FlowPane(10, 10, startButton, stopButton, createButton, publishButton,
                resultsButton, assignmentsButton);
        actions.setPadding(new Insets(16));

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(table);
        root.setBottom(actions);
        BorderPane.setMargin(table, new Insets(0, 16, 0, 16));

        stage.setScene(new Scene(root, 920, 540));
        stage.setTitle("LAN Exam Server");
        stage.show();
    }

    private void startServer() {
        startButton.setDisable(true);
        statusLabel.setText("Đang khởi động database và server...");

        Thread thread = new Thread(() -> {
            try {
                JpaUtil.init();
                SeedData.ensureSeeded();
                examService = new ExamService();
                assignmentService = new AssignmentService();
                server = new ExamServer(registry, new AuthService(), examService, assignmentService);
                server.start(5000);
                Platform.runLater(() -> {
                    statusLabel.setText("Server đang chạy tại cổng 5000");
                    stopButton.setDisable(false);
                    publishButton.setDisable(false);
                    createButton.setDisable(false);
                    resultsButton.setDisable(false);
                    assignmentsButton.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
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
        publishButton.setDisable(true);
        createButton.setDisable(true);
        ExamServer activeServer = server;
        Thread loader = new Thread(() -> {
            try {
                List<ExamSummary> exams = examService.listExams();
                Platform.runLater(() -> {
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
                    finishPublishAction(activeServer);
                    showError("Không thể tải danh sách bài thi", e.getMessage());
                });
            }
        }, "load-exams");
        loader.setDaemon(true);
        loader.start();
    }

    private void activateAndPublish(ExamServer activeServer, Long examId) {
        Thread thread = new Thread(() -> {
            try {
                ExamDto exam = examService.activateExam(examId);
                if (!activeServer.isRunning()) return;
                int count = activeServer.publishExam(exam);
                Platform.runLater(() -> {
                    statusLabel.setText("Đã mở đợt #" + exam.getId() + ": " + exam.getTitle()
                            + "; đã thông báo cho " + count + " sinh viên online");
                    finishPublishAction(activeServer);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
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
        ExamEditorDialog.show(mainStage).ifPresent(draft -> {
            createButton.setDisable(true);
            publishButton.setDisable(true);
            Thread thread = new Thread(() -> {
                try {
                    ExamSummary created = examService.createExam(draft);
                    Platform.runLater(() -> {
                        statusLabel.setText("Đã lưu đề: " + created.title() + ". Bài đang mở vẫn tiếp tục; chọn đề khi muốn mở bài mới.");
                        if (server != null && server.isRunning()) {
                            createButton.setDisable(false);
                            publishButton.setDisable(false);
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
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
        refresh.setDisable(true);
        Thread thread = new Thread(() -> {
            try {
                List<ExamResultRow> results = examService.listResults();
                Platform.runLater(() -> {
                    table.getItems().setAll(results);
                    count.setText("Tổng số bài đã nộp: " + results.size());
                    refresh.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    refresh.setDisable(false);
                    count.setText("Không tải được bảng điểm");
                    showError("Không thể xem điểm", ex.getMessage());
                });
            }
        }, "load-results");
        thread.setDaemon(true);
        thread.start();
    }

    private void stopServer() {
        if (server != null) server.stop();
        rows.clear();
        statusLabel.setText("Server đã dừng");
        startButton.setDisable(false);
        stopButton.setDisable(true);
        publishButton.setDisable(true);
        createButton.setDisable(true);
        resultsButton.setDisable(true);
        assignmentsButton.setDisable(true);
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message == null ? "Lỗi không xác định" : message);
        alert.showAndWait();
    }

    @Override
    public void stop() {
        if (server != null) server.stop();
        JpaUtil.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
