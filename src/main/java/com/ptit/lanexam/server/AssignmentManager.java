package com.ptit.lanexam.server;

import com.ptit.lanexam.common.dto.AssignmentInfo;
import com.ptit.lanexam.common.dto.AssignmentMessages.FileResult;
import com.ptit.lanexam.common.dto.AssignmentSubmissionInfo;
import com.ptit.lanexam.common.ui.AssignmentUi;
import com.ptit.lanexam.server.service.AssignmentDraft;
import com.ptit.lanexam.server.service.AssignmentService;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.util.function.Supplier;

public final class AssignmentManager {
    private final AssignmentService service;
    private final Runnable notifyStudents;
    private final Stage window = new Stage();
    private final TableView<AssignmentInfo> assignments = new TableView<>();
    private final TableView<AssignmentSubmissionInfo> submissions = new TableView<>();
    private final TextArea requirement = new TextArea();
    private final TextArea answer = new TextArea();
    private final Label detail = new Label("Chọn một bài tập để xem bài nộp.");
    private final Label message = new Label();
    private final Button refresh = new Button("Làm mới");
    private final Button create = new Button("Giao bài tập");
    private final Button downloadTask = new Button("Tải đề bài");
    private final Button downloadAnswer = new Button("Tải tệp bài nộp");
    private long selectionVersion;
    private boolean loadingList;

    public AssignmentManager(Window owner, AssignmentService service, Runnable notifyStudents) {
        this.service = service;
        this.notifyStudents = notifyStudents;
        window.initOwner(owner);
        window.setTitle("Bài tập - Giảng viên");
        assignments.setId("teacher-assignments");
        submissions.setId("teacher-submissions");
        answer.setId("teacher-submission-content");
        assignments.getColumns().add(AssignmentUi.column("Bài tập", 310, AssignmentInfo::title));
        assignments.getColumns().add(AssignmentUi.column("Hạn nộp", 160, a -> AssignmentUi.time(a.dueAt())));
        assignments.getColumns().add(AssignmentUi.column("Số bài nộp", 100, a -> Long.toString(a.submissionCount())));
        assignments.getColumns().add(AssignmentUi.column("Tệp đề bài", 220, a -> a.fileName() == null ? "" : a.fileName()));
        assignments.setPlaceholder(new Label("Chưa có bài tập. Bấm Giao bài tập để tạo."));
        assignments.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (!loadingList) selectAssignment(selected);
        });
        submissions.getColumns().add(AssignmentUi.column("Mã sinh viên", 130, AssignmentSubmissionInfo::studentCode));
        submissions.getColumns().add(AssignmentUi.column("Họ tên", 180, AssignmentSubmissionInfo::studentName));
        submissions.getColumns().add(AssignmentUi.column("Lần nộp gần nhất", 165, s -> AssignmentUi.time(s.submittedAt())));
        submissions.getColumns().add(AssignmentUi.column("Tệp đã nộp", 230, s -> AssignmentUi.fileLabel(s.fileName(), s.fileSize())));
        submissions.setPlaceholder(new Label("Chưa có học viên nộp bài tập này."));
        submissions.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            answer.setText(selected == null ? "" : selected.content());
            downloadAnswer.setDisable(selected == null || selected.fileName() == null);
        });
        requirement.setEditable(false);
        requirement.setWrapText(true);
        requirement.setPrefRowCount(3);
        answer.setEditable(false);
        answer.setWrapText(true);
        answer.setPrefRowCount(4);
        downloadTask.setDisable(true);
        downloadAnswer.setDisable(true);
        downloadTask.setOnAction(e -> {
            AssignmentInfo selected = assignments.getSelectionModel().getSelectedItem();
            if (selected != null) download(selected.fileName(), () -> service.downloadAssignment(selected.id()));
        });
        downloadAnswer.setOnAction(e -> {
            AssignmentSubmissionInfo selected = submissions.getSelectionModel().getSelectedItem();
            if (selected != null) download(selected.fileName(), () -> service.downloadSubmission(selected.id()));
        });
        create.setOnAction(e -> createAssignment());
        refresh.setOnAction(e -> loadAssignments(null));
        VBox upper = new VBox(8, new HBox(12, create, refresh, message), assignments);
        VBox.setVgrow(assignments, Priority.ALWAYS);
        VBox lower = new VBox(8, detail, requirement, downloadTask, new Label("Bài nộp của học viên"),
                submissions, new Label("Nội dung bài nộp đã chọn"), answer, downloadAnswer);
        VBox.setVgrow(submissions, Priority.ALWAYS);
        SplitPane split = new SplitPane(upper, lower);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.35);
        BorderPane root = new BorderPane(split);
        root.setPadding(new Insets(14));
        window.setScene(new Scene(root, 980, 800));
        window.show();
        loadAssignments(null);
    }

    private void createAssignment() {
        AssignmentEditorDialog.show(window).ifPresent(input -> {
            create.setDisable(true);
            message.setText("Đang giao bài tập...");
            AssignmentUi.async("create-assignment", () -> {
                File file = input.attachment();
                Long id = service.create(new AssignmentDraft(input.title(), input.description(), input.dueAt(),
                        file == null ? null : file.getName(), AssignmentUi.readAttachment(file)));
                notifyStudents.run();
                return id;
            }, id -> {
                create.setDisable(false);
                message.setText("Đã giao bài tập cho học viên.");
                loadAssignments(id);
            }, ex -> {
                create.setDisable(false);
                message.setText("Không giao được bài tập.");
                AssignmentUi.error(window, ex.getMessage());
            });
        });
    }

    private void loadAssignments(Long selectId) {
        AssignmentInfo selected = assignments.getSelectionModel().getSelectedItem();
        Long target = selectId != null ? selectId : selected == null ? null : selected.id();
        refresh.setDisable(true);
        AssignmentUi.async("load-assignments", service::listForTeacher, items -> {
            loadingList = true;
            assignments.getItems().setAll(items);
            AssignmentInfo next = items.stream().filter(a -> a.id().equals(target)).findFirst()
                    .orElse(items.isEmpty() ? null : items.getFirst());
            assignments.getSelectionModel().select(next);
            loadingList = false;
            refresh.setDisable(false);
            selectAssignment(next);
        }, ex -> {
            refresh.setDisable(false);
            AssignmentUi.error(window, ex.getMessage());
        });
    }

    private void selectAssignment(AssignmentInfo selected) {
        long version = ++selectionVersion;
        submissions.getItems().clear();
        answer.clear();
        downloadAnswer.setDisable(true);
        downloadTask.setDisable(selected == null || selected.fileName() == null);
        requirement.setText(selected == null ? "" : selected.description());
        detail.setText(selected == null ? "Chưa có bài tập." :
                selected.title() + " — Hạn nộp: " + AssignmentUi.time(selected.dueAt())
                        + " — " + AssignmentUi.fileLabel(selected.fileName(), selected.fileSize()));
        if (selected == null) return;
        AssignmentUi.async("load-assignment-submissions", () -> service.listSubmissions(selected.id()), items -> {
            if (selectionVersion != version) return;
            submissions.getItems().setAll(items);
            message.setText("Có " + items.size() + " học viên đã nộp.");
        }, ex -> {
            if (selectionVersion == version) AssignmentUi.error(window, ex.getMessage());
        });
    }

    private void download(String fileName, Supplier<FileResult> loader) {
        File destination = AssignmentUi.chooseDestination(window, fileName);
        if (destination == null) return;
        AssignmentUi.async("download-assignment", loader::get, file ->
                AssignmentUi.save(window, destination, file.data(), path -> message.setText("Đã lưu: " + path)),
                ex -> AssignmentUi.error(window, ex.getMessage()));
    }
}
