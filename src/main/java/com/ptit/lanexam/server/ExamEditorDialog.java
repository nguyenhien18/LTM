package com.ptit.lanexam.server;

import com.ptit.lanexam.server.entity.QuestionType;
import com.ptit.lanexam.server.service.ExamDraft;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

final class ExamEditorDialog {
    private ExamEditorDialog() {}

    static Optional<ExamDraft> show(Window owner) {
        Dialog<ExamDraft> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Tạo đề thi");
        dialog.setHeaderText("Tạo đề mới trong MySQL. Sau khi lưu, chọn đề trong danh sách để mở cho sinh viên.");
        ButtonType saveType = new ButtonType("Lưu đề thi", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField title = new TextField();
        title.setPromptText("Tên đề thi");
        Spinner<Integer> duration = new Spinner<>(1, 360, 20);
        duration.setEditable(true);

        TextArea content = new TextArea();
        content.setPromptText("Nội dung câu hỏi");
        content.setPrefRowCount(2);
        ComboBox<QuestionType> type = new ComboBox<>(FXCollections.observableArrayList(QuestionType.values()));
        type.setValue(QuestionType.MULTIPLE_CHOICE);
        TextField optionA = new TextField();
        TextField optionB = new TextField();
        TextField optionC = new TextField();
        TextField optionD = new TextField();
        TextField answer = new TextField();
        answer.setPromptText("A, B, C, D hoặc đáp án văn bản");
        List<TextField> options = List.of(optionA, optionB, optionC, optionD);
        type.valueProperty().addListener((obs, oldValue, value) -> {
            boolean text = value == QuestionType.TEXT;
            options.forEach(field -> field.setDisable(text));
            answer.setPromptText(text ? "Đáp án văn bản" : "A, B, C hoặc D");
        });

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.addRow(0, new Label("Tên đề"), title);
        form.addRow(1, new Label("Thời gian (phút)"), duration);
        form.addRow(2, new Label("Loại câu"), type);
        form.addRow(3, new Label("Câu hỏi"), content);
        form.addRow(4, new Label("A"), optionA);
        form.addRow(5, new Label("B"), optionB);
        form.addRow(6, new Label("C"), optionC);
        form.addRow(7, new Label("D"), optionD);
        form.addRow(8, new Label("Đáp án đúng"), answer);
        title.setPrefWidth(520);
        content.setPrefWidth(520);

        ObservableList<ExamDraft.QuestionDraft> questions = FXCollections.observableArrayList();
        ListView<ExamDraft.QuestionDraft> list = new ListView<>(questions);
        list.setPrefHeight(145);
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(ExamDraft.QuestionDraft item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : (getIndex() + 1) + ". " + item.content());
            }
        });
        Label error = new Label();
        error.setStyle("-fx-text-fill: #b00020;");
        Button add = new Button("Thêm câu hỏi");
        Button remove = new Button("Xóa câu đã chọn");
        add.setOnAction(event -> {
            String problem = validateQuestion(content, type, options, answer);
            if (problem != null) { error.setText(problem); return; }
            boolean choice = type.getValue() == QuestionType.MULTIPLE_CHOICE;
            questions.add(new ExamDraft.QuestionDraft(content.getText().trim(), type.getValue(),
                    choice ? optionA.getText().trim() : null, choice ? optionB.getText().trim() : null,
                    choice ? optionC.getText().trim() : null, choice ? optionD.getText().trim() : null,
                    answer.getText().trim()));
            content.clear();
            options.forEach(TextField::clear);
            answer.clear();
            error.setText("");
        });
        remove.setOnAction(event -> questions.remove(list.getSelectionModel().getSelectedItem()));

        VBox body = new VBox(10, form, new HBox(10, add, remove),
                new Label("Các câu hỏi đã thêm:"), list, error);
        body.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().setPrefWidth(750);
        Node saveButton = dialog.getDialogPane().lookupButton(saveType);
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                int minutes = Integer.parseInt(duration.getEditor().getText().trim());
                if (minutes < 1 || minutes > 360) throw new IllegalArgumentException();
                duration.getValueFactory().setValue(minutes);
            } catch (RuntimeException ex) {
                error.setText("Thời gian làm bài không hợp lệ."); event.consume(); return;
            }
            if (title.getText().isBlank() || title.getText().trim().length() > 200) {
                error.setText("Tên đề phải có từ 1 đến 200 ký tự."); event.consume();
            } else if (questions.isEmpty()) {
                error.setText("Hãy bấm Thêm câu hỏi trước khi lưu."); event.consume();
            } else if (!content.getText().isBlank()) {
                error.setText("Còn câu hỏi chưa được thêm vào danh sách."); event.consume();
            }
        });
        dialog.setResultConverter(button -> button == saveType
                ? new ExamDraft(title.getText().trim(), duration.getValue(), List.copyOf(questions)) : null);
        return dialog.showAndWait();
    }

    private static String validateQuestion(TextArea content, ComboBox<QuestionType> type,
                                           List<TextField> options, TextField answer) {
        if (content.getText().isBlank()) return "Nhập nội dung câu hỏi.";
        if (answer.getText().isBlank() || answer.getText().trim().length() > 500)
            return "Nhập đáp án đúng, tối đa 500 ký tự.";
        if (type.getValue() == QuestionType.MULTIPLE_CHOICE) {
            if (options.stream().anyMatch(field -> field.getText().isBlank() || field.getText().trim().length() > 500))
                return "Nhập đủ bốn lựa chọn, mỗi lựa chọn tối đa 500 ký tự.";
            if (!List.of("A", "B", "C", "D").contains(answer.getText().trim().toUpperCase()))
                return "Đáp án trắc nghiệm phải là A, B, C hoặc D.";
        }
        return null;
    }
}
