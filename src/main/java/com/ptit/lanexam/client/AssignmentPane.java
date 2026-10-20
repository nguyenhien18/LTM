package com.ptit.lanexam.client;

import com.ptit.lanexam.common.JsonUtil;
import com.ptit.lanexam.common.MessageType;
import com.ptit.lanexam.common.NetworkMessage;
import com.ptit.lanexam.common.TransferLimits;
import com.ptit.lanexam.common.dto.AssignmentInfo;
import com.ptit.lanexam.common.dto.AssignmentMessages.*;
import com.ptit.lanexam.common.dto.AssignmentSubmissionInfo;
import com.ptit.lanexam.common.ui.AssignmentUi;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

public final class AssignmentPane extends BorderPane {
    private final Window owner;
    private final Consumer<NetworkMessage> send;
    private final TableView<AssignmentInfo> table = new TableView<>();
    private final Label details = new Label("Chọn bài tập để xem yêu cầu.");
    private final TextArea description = new TextArea();
    private final TextArea answer = new TextArea();
    private final Label fileLabel = new Label("Chưa chọn tệp bài làm");
    private final Label status = new Label();
    private final Button submit = new Button("Nộp bài tập");
    private final Button downloadTask = new Button("Tải đề bài");
    private final Button downloadOwn = new Button("Tải bài đã nộp");
    private final Button chooseFile = new Button("Chọn tệp bài làm");
    private final Button removeFile = new Button("Bỏ tệp");
    private final Button refresh = new Button("Làm mới bài tập");
    private AssignmentInfo selected;
    private File attachment;
    private boolean keepExistingFile = true;
    private boolean busy;
    private boolean disposed;
    private boolean updating;
    private boolean reloadEditor;
    private File pendingDestination;
    private FileRequest pendingDownload;

    public AssignmentPane(Window owner, Consumer<NetworkMessage> send) {
        this.owner = owner;
        this.send = send;
        table.setId("assignment-list");
        answer.setId("assignment-answer");
        submit.setId("assignment-submit");
        table.getColumns().add(AssignmentUi.column("Bài tập", 280, AssignmentInfo::title));
        table.getColumns().add(AssignmentUi.column("Hạn nộp", 160, a -> AssignmentUi.time(a.dueAt())));
        table.getColumns().add(AssignmentUi.column("Trạng thái", 190, a -> a.mySubmission() != null
                ? "Đã nộp: " + AssignmentUi.time(a.mySubmission().submittedAt())
                : a.dueAt() != null && !a.dueAt().isAfter(Instant.now()) ? "Đã hết hạn - Chưa nộp" : "Chưa nộp"));
        table.setPlaceholder(new Label("Giảng viên chưa giao bài tập."));
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> {
            if (!updating) select(value, true);
        });
        table.setPrefHeight(180);
        description.setEditable(false);
        description.setWrapText(true);
        description.setPrefRowCount(3);
        answer.setWrapText(true);
        answer.setPrefRowCount(4);
        answer.setPromptText("Nhập nội dung bài làm hoặc đính kèm tệp (tối đa 5 MB).");
        details.setWrapText(true);
        fileLabel.setWrapText(true);
        status.setWrapText(true);
        refresh.setOnAction(e -> requestList());
        downloadTask.setOnAction(e -> download(false));
        downloadOwn.setOnAction(e -> download(true));
        chooseFile.setOnAction(e -> {
            File chosen = AssignmentUi.chooseAttachment(owner);
            if (chosen != null) {
                attachment = chosen;
                keepExistingFile = false;
                fileLabel.setText(AssignmentUi.fileLabel(chosen.getName(), chosen.length()));
            }
        });
        removeFile.setOnAction(e -> {
            attachment = null;
            keepExistingFile = false;
            fileLabel.setText("Không đính kèm tệp.");
        });
        submit.setOnAction(e -> submit());
        VBox form = new VBox(8, details, description, new HBox(10, downloadTask, downloadOwn),
                new Label("Bài làm của bạn"), answer, new HBox(10, chooseFile, removeFile), fileLabel,
                new Label("Có thể nộp lại trước hạn. Bản nộp mới thay thế bản trước."), submit, status);
        form.setPadding(new Insets(10, 0, 0, 0));
        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        VBox top = new VBox(8, refresh, table);
        setTop(top);
        setCenter(scroll);
        setPadding(new Insets(12));
        updateControls();
    }

    public void setItems(List<AssignmentInfo> items) {
        if (disposed) return;
        Long id = selected == null ? null : selected.id();
        updating = true;
        table.getItems().setAll(items);
        AssignmentInfo next = items.stream().filter(a -> a.id().equals(id)).findFirst()
                .orElse(items.isEmpty() ? null : items.getFirst());
        table.getSelectionModel().select(next);
        updating = false;
        select(next, reloadEditor || id == null || next == null || !next.id().equals(id));
        reloadEditor = false;
    }

    private void select(AssignmentInfo item, boolean resetEditor) {
        selected = item;
        description.setText(item == null ? "" : item.description());
        details.setText(item == null ? "Chưa có bài tập." : item.title() + " — Hạn nộp: "
                + AssignmentUi.time(item.dueAt()) + " — " + AssignmentUi.fileLabel(item.fileName(), item.fileSize()));
        if (resetEditor) {
            attachment = null;
            keepExistingFile = true;
            AssignmentSubmissionInfo previous = item == null ? null : item.mySubmission();
            answer.setText(previous == null ? "" : previous.content());
            fileLabel.setText(previous == null ? "Chưa chọn tệp bài làm"
                    : AssignmentUi.fileLabel(previous.fileName(), previous.fileSize()));
            status.setText(previous == null ? "" : "Đã nộp lúc " + AssignmentUi.time(previous.submittedAt()));
        }
        updateControls();
    }

    public void requestList() {
        if (!disposed) send.accept(JsonUtil.message(MessageType.ASSIGNMENTS_REQUEST, null));
    }

    private void submit() {
        if (selected == null || busy) return;
        String content = answer.getText();
        if (content.length() > TransferLimits.MAX_TEXT_LENGTH) { fail("Nội dung tối đa 20.000 ký tự."); return; }
        boolean existing = keepExistingFile && selected.mySubmission() != null && selected.mySubmission().fileName() != null;
        if (content.isBlank() && attachment == null && !existing) {
            fail("Nhập bài làm hoặc chọn tệp để nộp."); return;
        }
        Long assignmentId = selected.id();
        File chosen = attachment;
        boolean keep = keepExistingFile;
        setBusy(true, "Đang gửi bài tập...");
        AssignmentUi.async("read-submission-file", () -> new SubmitRequest(assignmentId, content,
                chosen == null ? null : chosen.getName(), AssignmentUi.readAttachment(chosen), keep),
                request -> { if (!disposed) send.accept(JsonUtil.message(MessageType.ASSIGNMENT_SUBMIT, request)); },
                ex -> fail(ex.getMessage()));
    }

    public void submitted(AssignmentSubmissionInfo result) {
        if (disposed) return;
        reloadEditor = true;
        setBusy(false, "Đã lưu bài nộp lúc " + AssignmentUi.time(result.submittedAt()) + ".");
    }

    private void download(boolean own) {
        if (selected == null || busy) return;
        String name = own ? selected.mySubmission() == null ? null : selected.mySubmission().fileName() : selected.fileName();
        if (name == null) return;
        File destination = AssignmentUi.chooseDestination(owner, name);
        if (destination == null) return;
        pendingDownload = new FileRequest(selected.id(), own);
        pendingDestination = destination;
        setBusy(true, "Đang tải tệp...");
        send.accept(JsonUtil.message(MessageType.ASSIGNMENT_FILE_REQUEST, pendingDownload));
    }

    public void receivedFile(FileResult file) {
        if (disposed || pendingDownload == null || !pendingDownload.assignmentId().equals(file.assignmentId())
                || pendingDownload.submission() != file.submission()) return;
        File destination = pendingDestination;
        pendingDownload = null;
        pendingDestination = null;
        AssignmentUi.async("save-submission-file", () -> {
            TransferLimits.validateAttachment(file.fileName(), file.data());
            java.nio.file.Files.write(destination.toPath(), file.data());
            return destination.getAbsolutePath();
        }, path -> { if (!disposed) setBusy(false, "Đã lưu tệp: " + path); }, ex -> fail(ex.getMessage()));
    }

    public void fail(String message) {
        if (disposed) return;
        pendingDownload = null;
        pendingDestination = null;
        setBusy(false, message);
        status.setStyle("-fx-text-fill: #b00020;");
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        status.setStyle("");
        status.setText(message);
        updateControls();
    }

    private void updateControls() {
        table.setDisable(busy);
        refresh.setDisable(busy);
        answer.setDisable(busy || selected == null);
        chooseFile.setDisable(busy || selected == null);
        removeFile.setDisable(busy || selected == null);
        submit.setDisable(busy || selected == null);
        submit.setText(selected != null && selected.mySubmission() != null ? "Nộp lại bài tập" : "Nộp bài tập");
        downloadTask.setDisable(busy || selected == null || selected.fileName() == null);
        downloadOwn.setDisable(busy || selected == null || selected.mySubmission() == null || selected.mySubmission().fileName() == null);
    }

    public void dispose() { disposed = true; }
}
