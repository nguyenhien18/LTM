package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.AssignmentUi;
import com.ptit.lanexam.server.service.*;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class ExamEditorDialog {
    private ExamEditorDialog() {}

    public static Optional<BankExamDraft> show(Window owner, QuestionBankService service) {
        return create(owner, service).showAndWait();
    }

    public static Dialog<BankExamDraft> create(Window owner, QuestionBankService service) {
        Dialog<BankExamDraft> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Tạo đề thi từ ngân hàng câu hỏi");
        dialog.setHeaderText("Chọn câu từ ngân hàng chung. Một câu hỏi có thể được chọn cho nhiều đề thi.");
        dialog.setResizable(true);
        ButtonType saveType = new ButtonType("Lưu đề thi", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE));
        TextField title = new TextField();
        title.setId("exam-title");
        title.setPromptText("Tên đề thi (tối đa 200 ký tự)");
        HBox.setHgrow(title, Priority.ALWAYS);
        Spinner<Integer> duration = new Spinner<>(1, 360, 20);
        duration.setId("exam-duration");
        duration.setEditable(true);
        duration.setPrefWidth(90);
        HBox form = new HBox(10, new Label("Tên đề"), title, new Label("Thời gian (phút)"), duration);
        QuestionBankBrowser bank = new QuestionBankBrowser(service);
        ObservableList<BankQuestionInfo> chosen = FXCollections.observableArrayList();
        TableView<BankQuestionInfo> selected = new TableView<>(chosen);
        selected.setId("exam-selected-questions");
        selected.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        selected.getColumns().add(AssignmentUi.column("Mã", 55, q -> Long.toString(q.id())));
        selected.getColumns().add(AssignmentUi.column("Độ khó", 65, q -> Integer.toString(q.difficulty())));
        selected.getColumns().add(AssignmentUi.column("Câu hỏi", 220, q -> q.content().replaceAll("\\s+", " ")));
        selected.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        selected.setPlaceholder(new Label("Chưa chọn câu hỏi nào."));
        TextArea selectedPreview = new TextArea();
        selectedPreview.setEditable(false);
        selectedPreview.setWrapText(true);
        selectedPreview.setPrefRowCount(3);
        selectedPreview.setPromptText("Chọn một câu trong đề để xem đầy đủ nội dung.");
        selected.getSelectionModel().selectedItemProperty().addListener((obs, old, question) ->
                selectedPreview.setText(question == null ? "" : question.preview()));
        Label count = new Label();
        count.setId("exam-question-count");
        count.setWrapText(true);
        Label error = new Label();
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #b00020;");
        chosen.addListener((ListChangeListener<BankQuestionInfo>) change -> {
            count.setText("Đã chọn " + chosen.size() + " câu trắc nghiệm.");
        });
        count.setText("Chưa chọn câu hỏi.");
        bank.setOnSubjectChanged(subject -> {
            chosen.clear();
            error.setText("");
        });
        bank.setOnQuestionSaved(saved -> {
            for (int i = 0; i < chosen.size(); i++)
                if (chosen.get(i).id().equals(saved.id())) {
                    if (bank.subject() != null && bank.subject().id().equals(saved.subjectId())) chosen.set(i, saved);
                    else chosen.remove(i);
                    break;
                }
        });
        Button add = new Button("Thêm câu đã chọn →");
        add.setId("exam-add-selected");
        add.setOnAction(event -> {
            List<BankQuestionInfo> additions = bank.selectedQuestions().stream()
                    .filter(question -> chosen.stream().noneMatch(q -> q.id().equals(question.id()))).toList();
            if (bank.selectedQuestions().isEmpty()) { error.setText("Chọn câu hỏi trong ngân hàng trước."); return; }
            if (chosen.size() + additions.size() > ExamService.MAX_BANK_EXAM_QUESTIONS) {
                error.setText("Mỗi đề tối đa " + ExamService.MAX_BANK_EXAM_QUESTIONS + " câu."); return;
            }
            chosen.addAll(additions);
            error.setText(additions.isEmpty() ? "Các câu đã chọn đều có trong đề." : "");
        });
        Button remove = new Button("Bỏ câu đã chọn");
        remove.setId("exam-remove-selected");
        remove.setTooltip(new Tooltip("Chỉ bỏ khỏi đề đang tạo; câu hỏi vẫn còn trong ngân hàng để chọn cho các đề khác."));
        remove.setOnAction(event -> chosen.removeAll(List.copyOf(selected.getSelectionModel().getSelectedItems())));
        Button up = new Button("Lên");
        Button down = new Button("Xuống");
        up.setId("exam-move-up");
        down.setId("exam-move-down");
        up.setOnAction(event -> move(selected, chosen, -1));
        down.setOnAction(event -> move(selected, chosen, 1));
        VBox left = new VBox(6, new Label("Ngân hàng câu hỏi chung — chọn môn và lọc độ khó"), bank, add);
        VBox.setVgrow(bank, Priority.ALWAYS);
        VBox right = new VBox(8, new Label("Câu hỏi trong đề (theo thứ tự hiển thị)"),
                selected, new HBox(8, remove, up, down), selectedPreview);
        right.setPadding(new Insets(8));
        VBox.setVgrow(selected, Priority.ALWAYS);
        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.60);
        VBox body = new VBox(10, form, split, count, new Label("Đổi môn thi sẽ bỏ các câu đã chọn trong đề."), error);
        body.setPadding(new Insets(12));
        VBox.setVgrow(split, Priority.ALWAYS);
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().setPrefSize(1180, 750);
        BankExamDraft[] result = {null};
        dialog.getDialogPane().lookupButton(saveType).setId("exam-save");
        dialog.getDialogPane().lookupButton(saveType).addEventFilter(ActionEvent.ACTION, event -> {
            try {
                if (title.getText().isBlank() || title.getText().trim().length() > 200)
                    throw new IllegalArgumentException("Tên đề phải có từ 1 đến 200 ký tự.");
                int minutes;
                try { minutes = Integer.parseInt(duration.getEditor().getText().trim()); }
                catch (NumberFormatException ex) { throw new IllegalArgumentException("Thời gian phải là số phút từ 1 đến 360."); }
                if (minutes < 1 || minutes > 360) throw new IllegalArgumentException("Thời gian làm bài phải từ 1 đến 360 phút.");
                if (bank.subject() == null) throw new IllegalArgumentException("Chọn môn thi.");
                if (chosen.isEmpty()) throw new IllegalArgumentException("Chọn ít nhất một câu hỏi để tạo đề.");
                result[0] = new BankExamDraft(title.getText().trim(), minutes, bank.subject().id(),
                        chosen.stream().map(BankQuestionInfo::id).toList());
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == saveType ? result[0] : null);
        dialog.setOnHidden(event -> bank.dispose());
        return dialog;
    }

    private static void move(TableView<BankQuestionInfo> table, ObservableList<BankQuestionInfo> questions, int delta) {
        int index = table.getSelectionModel().getSelectedIndex();
        int next = index + delta;
        if (index < 0 || next < 0 || next >= questions.size()) return;
        Collections.swap(questions, index, next);
        table.getSelectionModel().clearAndSelect(next);
        table.scrollTo(next);
    }
}
