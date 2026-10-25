package com.ptit.lanexam.server;

import com.ptit.lanexam.server.entity.QuestionType;
import com.ptit.lanexam.server.service.*;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

public final class QuestionEditorDialog {
    private QuestionEditorDialog() {}

    public static Optional<BankQuestionDraft> show(Window owner, List<SubjectInfo> subjects,
                                                 SubjectInfo defaultSubject, BankQuestionInfo existing) {
        return create(owner, subjects, defaultSubject, existing).showAndWait();
    }

    public static Dialog<BankQuestionDraft> create(Window owner, List<SubjectInfo> subjects,
                                                   SubjectInfo defaultSubject, BankQuestionInfo existing) {
        Dialog<BankQuestionDraft> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(existing == null ? "Tạo câu hỏi" : "Sửa câu hỏi #" + existing.id());
        dialog.setHeaderText("Câu trắc nghiệm có bốn lựa chọn A–D và một đáp án đúng; phân loại theo môn và độ khó.");
        ButtonType saveType = new ButtonType("Lưu câu hỏi", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE));
        ComboBox<SubjectInfo> subject = new ComboBox<>(FXCollections.observableArrayList(subjects));
        subject.setId("question-subject");
        subject.setPromptText("Chọn môn học");
        subject.setMaxWidth(Double.MAX_VALUE);
        subject.setValue(existing == null ? defaultSubject : subjects.stream()
                .filter(s -> s.id().equals(existing.subjectId())).findFirst().orElse(null));
        Spinner<Integer> difficulty = new Spinner<>(1, 6, existing == null ? 1 : existing.difficulty());
        difficulty.setId("question-difficulty");
        difficulty.setEditable(true);
        difficulty.setPrefWidth(90);
        TextArea content = new TextArea(existing == null ? "" : existing.content());
        content.setId("question-content");
        content.setWrapText(true);
        content.setPrefRowCount(4);
        content.setPromptText("Nội dung câu hỏi");
        TextField optionA = new TextField(existing == null ? "" : existing.optionA());
        TextField optionB = new TextField(existing == null ? "" : existing.optionB());
        TextField optionC = new TextField(existing == null ? "" : existing.optionC());
        TextField optionD = new TextField(existing == null ? "" : existing.optionD());
        List<TextField> options = List.of(optionA, optionB, optionC, optionD);
        for (int i = 0; i < options.size(); i++) options.get(i).setId("question-option-" + (char) ('a' + i));
        GridPane choices = new GridPane();
        choices.setHgap(10);
        choices.setVgap(8);
        for (int i = 0; i < options.size(); i++) {
            choices.addRow(i, new Label((char) ('A' + i) + "."), options.get(i));
            GridPane.setHgrow(options.get(i), Priority.ALWAYS);
        }
        ComboBox<String> choiceAnswer = new ComboBox<>(FXCollections.observableArrayList("A", "B", "C", "D"));
        choiceAnswer.setId("question-choice-answer");
        choiceAnswer.setPromptText("Chọn A, B, C hoặc D");
        if (existing != null && existing.type() == QuestionType.MULTIPLE_CHOICE) choiceAnswer.setValue(existing.correctAnswer());
        Label error = new Label();
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #b00020;");
        difficulty.getEditor().textProperty().addListener((obs, old, value) -> error.setText(""));
        content.textProperty().addListener((obs, old, value) -> error.setText(""));
        subject.valueProperty().addListener((obs, old, value) -> error.setText(""));
        choiceAnswer.valueProperty().addListener((obs, old, value) -> error.setText(""));
        options.forEach(field -> field.textProperty().addListener((obs, old, value) -> error.setText("")));
        VBox body = new VBox(10, new Label("Môn học"), subject,
                new HBox(12, new Label("Độ khó (1–6)"), difficulty, new Label("Trắc nghiệm — 4 lựa chọn")),
                new Label("Câu hỏi"), content, choices, new Label("Đáp án đúng"), choiceAnswer, error);
        body.setPadding(new Insets(14));
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().setPrefSize(700, 620);
        BankQuestionDraft[] result = {null};
        dialog.getDialogPane().lookupButton(saveType).setId("question-save");
        dialog.getDialogPane().lookupButton(saveType).addEventFilter(ActionEvent.ACTION, event -> {
            try {
                int level;
                try { level = Integer.parseInt(difficulty.getEditor().getText().trim()); }
                catch (NumberFormatException ex) { throw new IllegalArgumentException("Độ khó phải là số từ 1 đến 6."); }
                String answer = choiceAnswer.getValue();
                result[0] = new BankQuestionDraft(subject.getValue() == null ? null : subject.getValue().id(), level,
                        new ExamDraft.QuestionDraft(content.getText(), QuestionType.MULTIPLE_CHOICE, optionA.getText(), optionB.getText(),
                                optionC.getText(), optionD.getText(), answer));
                QuestionBankService.validateDraft(result[0]);
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == saveType ? result[0] : null);
        return dialog;
    }
}
