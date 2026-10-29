package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.UiSupport;
import com.ptit.lanexam.common.ui.AppUi;
import com.ptit.lanexam.server.service.*;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.nio.file.*;
import java.io.IOException;

public final class QuestionBankBrowser extends VBox {
    private final QuestionBankService service;
    private final ComboBox<SubjectInfo> subject = new ComboBox<>();
    private final ComboBox<Integer> difficulty = new ComboBox<>(FXCollections.observableArrayList(0, 1, 2, 3, 4, 5, 6));
    private final TableView<BankQuestionInfo> table = new TableView<>();
    private final TextArea preview = new TextArea();
    private final Label message = new Label("Đang tải môn học...");
    private final Button create = new Button("Tạo câu hỏi");
    private final Button edit = new Button("Sửa câu hỏi");
    private final Button importWord = new Button("Nhập Word");
    private Consumer<SubjectInfo> subjectChanged = ignored -> {};
    private Consumer<BankQuestionInfo> questionSaved = ignored -> {};
    private boolean updatingSubjects;
    private boolean disposed;
    private long subjectsVersion;
    private long questionsVersion;

    public QuestionBankBrowser(QuestionBankService service) {
        super(8);
        this.service = service;
        subject.setId("bank-subject");
        subject.setPrefWidth(270);
        subject.setPromptText("Chọn môn học");
        difficulty.setId("bank-difficulty");
        difficulty.setConverter(new StringConverter<>() {
            @Override public String toString(Integer value) { return value == null || value == 0 ? "Tất cả độ khó" : "Mức " + value; }
            @Override public Integer fromString(String value) { throw new UnsupportedOperationException(); }
        });
        difficulty.setValue(0);
        Button newSubject = new Button("Thêm môn học");
        newSubject.setId("bank-add-subject");
        create.setId("bank-create-question");
        edit.setId("bank-edit-question");
        importWord.setId("bank-import-word"); importWord.setDisable(true); importWord.setOnAction(e -> chooseWord());
        Button wordTemplate = new Button("Tải Word mẫu"); wordTemplate.setId("bank-word-template"); wordTemplate.setOnAction(e -> saveWordTemplate());
        Button refresh = new Button("Làm mới");
        refresh.setOnAction(event -> reloadSubjects(subject.getValue() == null ? null : subject.getValue().id()));
        newSubject.setOnAction(event -> addSubject());
        create.setOnAction(event -> editQuestion(null));
        edit.setOnAction(event -> {
            BankQuestionInfo selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) editQuestion(selected);
        });
        subject.valueProperty().addListener((obs, old, value) -> {
            if (updatingSubjects) return;
            if (!Objects.equals(id(old), id(value))) subjectChanged.accept(value);
            loadQuestions();
        });
        difficulty.valueProperty().addListener((obs, old, value) -> loadQuestions());
        table.setId("bank-questions");
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getColumns().add(UiSupport.column("Mã câu", 75, q -> Long.toString(q.id())));
        table.getColumns().add(UiSupport.column("Độ khó", 80, q -> Integer.toString(q.difficulty())));
        table.getColumns().add(UiSupport.column("Nội dung câu hỏi", 480, q -> q.content().replaceAll("\\s+", " ")));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Chọn môn học; nếu chưa có câu hỏi, bấm Tạo câu hỏi."));
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> {
            preview.setText(value == null ? "" : value.preview());
            edit.setDisable(value == null);
        });
        table.setPrefHeight(220);
        preview.setEditable(false);
        preview.setWrapText(true);
        preview.setPrefRowCount(3);
        preview.setPromptText("Chọn một dòng để xem đầy đủ câu hỏi và đáp án.");
        preview.setId("bank-question-preview");
        message.setWrapText(true);
        message.setId("bank-load-status");
        message.getStyleClass().add("muted");
        create.getStyleClass().add("primary");
        create.setDisable(true);
        edit.setDisable(true);
        FlowPane subjects = new FlowPane(10, 8, subject, difficulty, newSubject);
        HBox actions = AppUi.toolbar(create, edit, importWord, wordTemplate, AppUi.spacer(), refresh);
        getChildren().addAll(subjects, actions, table, preview, message);
        VBox.setVgrow(table, Priority.ALWAYS);
        setPadding(new Insets(8));
        reloadSubjects(null);
    }

    public SubjectInfo subject() { return subject.getValue(); }
    public List<BankQuestionInfo> selectedQuestions() { return List.copyOf(table.getSelectionModel().getSelectedItems()); }
    public void setOnSubjectChanged(Consumer<SubjectInfo> listener) { subjectChanged = listener; }
    public void setOnQuestionSaved(Consumer<BankQuestionInfo> listener) { questionSaved = listener; }

    private Window owner() { return getScene() == null ? null : getScene().getWindow(); }
    private static Long id(SubjectInfo value) { return value == null ? null : value.id(); }

    private void chooseWord() {
        if (subject.getValue() == null) return;
        FileChooser chooser = new FileChooser(); chooser.setTitle("Nhập câu hỏi Word vào " + subject.getValue().name());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Word (.docx)", "*.docx"));
        var file = chooser.showOpenDialog(owner()); if (file != null) importWordFile(file.toPath());
    }
    public void importWordFile(Path file) {
        SubjectInfo selected = subject.getValue(); if (disposed || selected == null) return;
        setDisable(true); message.setText("Đang đọc Word và kiểm tra câu hỏi...");
        record Read(QuestionWordImport.Parsed parsed, QuestionBankService.WordPreview preview) {}
        UiSupport.async("preview-word-questions", () -> {
            var parsed = QuestionWordImport.read(file); return new Read(parsed, service.previewWord(selected.id(), parsed));
        }, read -> {
            if (disposed) return;
            if (!QuestionWordPreviewDialog.show(owner(), selected, read.preview())) {
                setDisable(false); message.setText("Đã đóng xem trước Word; chưa nhập dữ liệu."); return;
            }
            UiSupport.async("import-word-questions", () -> service.importWord(selected.id(), read.parsed()), result -> {
                if (disposed) return; setDisable(false); difficulty.setValue(0); reloadSubjects(selected.id());
                message.setText("Đã nhập " + result.imported() + " câu; bỏ qua " + result.skipped() + " câu trùng.");
            }, ex -> { if (!disposed) { setDisable(false); message.setText(ex.getMessage()); UiSupport.error(owner(), ex.getMessage()); } });
        }, ex -> { if (!disposed) { setDisable(false); message.setText(ex.getMessage()); UiSupport.error(owner(), ex.getMessage()); } });
    }
    private void saveWordTemplate() {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Lưu mẫu Word ngân hàng câu hỏi"); chooser.setInitialFileName("mau-ngan-hang-cau-hoi.docx");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Word (.docx)", "*.docx"));
        var file = chooser.showSaveDialog(owner()); if (file == null) return;
        UiSupport.async("save-word-question-template", () -> {
            try (var input = QuestionBankBrowser.class.getResourceAsStream("/templates/question-bank-template.docx")) {
                if (input == null) throw new IOException("Không tìm thấy mẫu Word trong ứng dụng.");
                Files.copy(input, file.toPath(), StandardCopyOption.REPLACE_EXISTING); return file.getAbsolutePath();
            }
        }, path -> { if (!disposed) message.setText("Đã lưu Word mẫu: " + path); },
                ex -> { if (!disposed) UiSupport.error(owner(), ex.getMessage()); });
    }

    private void addSubject() {
        TextInputDialog dialog = new TextInputDialog();
        AppUi.style(dialog.getDialogPane());
        dialog.initOwner(owner());
        dialog.setTitle("Thêm môn học");
        dialog.setHeaderText("Tên môn học");
        dialog.setContentText("Tên môn (tối đa 120 ký tự):");
        dialog.showAndWait().ifPresent(name -> {
            setDisable(true);
            UiSupport.async("create-subject", () -> service.createSubject(name), saved -> {
                if (disposed) return;
                setDisable(false);
                reloadSubjects(saved.id());
            }, ex -> { if (!disposed) { setDisable(false); UiSupport.error(owner(), ex.getMessage()); } });
        });
    }

    private void editQuestion(BankQuestionInfo existing) {
        QuestionEditorDialog.show(owner(), List.copyOf(subject.getItems()), subject.getValue(), existing).ifPresent(draft -> {
            setDisable(true);
            UiSupport.async("save-bank-question", () -> existing == null ? service.createQuestion(draft)
                    : service.updateQuestion(existing.id(), draft), saved -> {
                if (disposed) return;
                setDisable(false);
                questionSaved.accept(saved);
                reloadSubjects(saved.subjectId());
            }, ex -> { if (!disposed) { setDisable(false); UiSupport.error(owner(), ex.getMessage()); } });
        });
    }

    public void reloadSubjects(Long preferred) {
        long version = ++subjectsVersion;
        UiSupport.async("load-subjects", service::listSubjects, subjects -> {
            if (disposed || version != subjectsVersion) return;
            Long oldId = id(subject.getValue());
            Long desired = preferred == null ? oldId : preferred;
            SubjectInfo next = subjects.stream().filter(s -> s.id().equals(desired)).findFirst()
                    .orElse(subjects.isEmpty() ? null : subjects.getFirst());
            updatingSubjects = true;
            subject.getItems().setAll(subjects);
            subject.setValue(next);
            updatingSubjects = false;
            if (!Objects.equals(oldId, id(next))) subjectChanged.accept(next);
            loadQuestions();
        }, ex -> { if (!disposed && version == subjectsVersion) message.setText("Không tải được môn học: " + ex.getMessage()); });
    }

    private void loadQuestions() {
        long version = ++questionsVersion;
        SubjectInfo selected = subject.getValue();
        table.getItems().clear();
        preview.clear();
        create.setDisable(selected == null);
        importWord.setDisable(selected == null);
        edit.setDisable(true);
        if (selected == null) {
            message.setText("Chưa có môn học. Bấm Thêm môn học để bắt đầu.");
            return;
        }
        Integer level = difficulty.getValue() == null || difficulty.getValue() == 0 ? null : difficulty.getValue();
        message.setText("Đang tải câu hỏi...");
        UiSupport.async("load-bank-questions", () -> service.listQuestions(selected.id(), level), questions -> {
            if (disposed || version != questionsVersion) return;
            table.getItems().setAll(questions);
            message.setText(selected.name() + ": " + questions.size() + " câu phù hợp bộ lọc. Chọn nhiều dòng bằng Ctrl hoặc Shift.");
        }, ex -> { if (!disposed && version == questionsVersion) message.setText("Không tải được câu hỏi: " + ex.getMessage()); });
    }

    public void dispose() {
        disposed = true;
        subjectsVersion++;
        questionsVersion++;
    }
}
