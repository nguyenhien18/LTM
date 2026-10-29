package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.UiSupport;
import com.ptit.lanexam.common.ui.AppUi;
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
        dialog.getDialogPane().setId("exam-editor");
        AppUi.style(dialog.getDialogPane());
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
        selected.getColumns().add(UiSupport.column("Mã", 55, q -> Long.toString(q.id())));
        selected.getColumns().add(UiSupport.column("Độ khó", 65, q -> Integer.toString(q.difficulty())));
        selected.getColumns().add(UiSupport.column("Câu hỏi", 220, q -> q.content().replaceAll("\\s+", " ")));
        selected.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        selected.setPlaceholder(new Label("Chưa chọn câu hỏi nào."));
        TextArea selectedPreview = new TextArea();
        selectedPreview.setEditable(false);
        selectedPreview.setWrapText(true);
        selectedPreview.setPrefRowCount(3);
        selectedPreview.setMinHeight(130);
        selectedPreview.setPromptText("Chọn một câu trong đề để xem đầy đủ nội dung.");
        selected.getSelectionModel().selectedItemProperty().addListener((obs, old, question) ->
                selectedPreview.setText(question == null ? "" : question.preview()));
        Label count = new Label();
        count.setId("exam-question-count");
        count.setWrapText(true);
        Label error = new Label();
        error.setId("exam-editor-error");
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #b00020;");
        chosen.addListener((ListChangeListener<BankQuestionInfo>) change -> {
            count.setText("Đã chọn " + chosen.size() + " câu trắc nghiệm.");
        });
        count.setText("Chưa chọn câu hỏi.");
        java.util.Map<Integer, Spinner<Integer>> levels = new java.util.LinkedHashMap<>();
        HBox matrix = new HBox(10);
        for (int level = 1; level <= 6; level++) {
            Spinner<Integer> amount = new Spinner<>(0, ExamService.MAX_BANK_EXAM_QUESTIONS, 0);
            amount.setEditable(true); amount.setPrefWidth(78); amount.setId("exam-level-" + level + "-count");
            levels.put(level, amount); matrix.getChildren().add(new VBox(4, new Label("Mức " + level), amount));
        }
        Button draw = new Button("Lấy câu ngẫu nhiên"), replace = new Button("Đổi câu đang chọn");
        draw.setId("exam-random-draw"); replace.setId("exam-random-replace");
        Label randomNote = AppUi.label("Lấy bộ mới sẽ thay danh sách bên phải. Đổi một câu giữ nguyên mức và tránh câu đã chọn.", "muted");
        HBox quickControls = new HBox(18, matrix, new VBox(7, draw, replace));
        TitledPane quick = new TitledPane("Tạo đề nhanh theo số câu từng mức", new VBox(8, quickControls, randomNote)); quick.setId("exam-quick-create"); quick.setExpanded(true);
        draw.setOnAction(event -> {
            try {
                if (bank.subject() == null) throw new IllegalArgumentException("Chọn môn thi trước.");
                java.util.Map<Integer, Integer> counts = new java.util.LinkedHashMap<>();
                for (var entry : levels.entrySet()) {
                    int value;
                    try { value = Integer.parseInt(entry.getValue().getEditor().getText().strip()); }
                    catch (NumberFormatException ex) { throw new IllegalArgumentException("Số câu ở mức " + entry.getKey() + " phải là số nguyên không âm."); }
                    counts.put(entry.getKey(), value);
                }
                Long subjectId = bank.subject().id();
                dialog.getDialogPane().getContent().setDisable(true);
                UiSupport.async("draw-random-exam", () -> service.drawQuestions(subjectId, counts, java.util.Set.of()), questions -> {
                    if (!dialog.isShowing()) return;
                    dialog.getDialogPane().getContent().setDisable(false); chosen.setAll(questions); selected.getSelectionModel().selectFirst(); error.setText("");
                }, ex -> { if (dialog.isShowing()) { dialog.getDialogPane().getContent().setDisable(false); error.setText(ex.getMessage()); } });
            } catch (IllegalArgumentException ex) { error.setText(ex.getMessage()); }
        });
        replace.setOnAction(event -> {
            BankQuestionInfo question = selected.getSelectionModel().getSelectedItem();
            if (question == null || bank.subject() == null) { error.setText("Chọn một câu trong đề để đổi."); return; }
            Long subjectId = bank.subject().id(); int index = chosen.indexOf(question);
            var excluded = chosen.stream().map(BankQuestionInfo::id).collect(java.util.stream.Collectors.toSet());
            dialog.getDialogPane().getContent().setDisable(true);
            UiSupport.async("replace-random-exam-question", () -> service.drawQuestions(subjectId, java.util.Map.of(question.difficulty(), 1), excluded), questions -> {
                if (!dialog.isShowing()) return;
                dialog.getDialogPane().getContent().setDisable(false); chosen.set(index, questions.getFirst()); selected.getSelectionModel().clearAndSelect(index); error.setText("");
            }, ex -> { if (dialog.isShowing()) { dialog.getDialogPane().getContent().setDisable(false); error.setText(ex.getMessage()); } });
        });
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
        VBox body = new VBox(10, form, quick, split, count, new Label("Đổi môn thi sẽ bỏ các câu đã chọn trong đề."), error);
        body.setPadding(new Insets(12));
        VBox.setVgrow(split, Priority.ALWAYS);
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().setPrefSize(1180, 750);
        BankExamDraft[] result = {null};
        dialog.getDialogPane().lookupButton(saveType).setId("exam-save");
        dialog.getDialogPane().lookupButton(saveType).addEventFilter(ActionEvent.ACTION, event -> {
            try {
                if (body.isDisabled()) throw new IllegalArgumentException("Chờ lấy câu hỏi hoàn tất trước khi lưu.");
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
