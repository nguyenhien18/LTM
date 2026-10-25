package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.AssignmentUi;
import com.ptit.lanexam.server.service.*;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class QuestionBankBrowser extends VBox {
    private final QuestionBankService service;
    private final ComboBox<SubjectInfo> subject = new ComboBox<>();
    private final ComboBox<Integer> difficulty = new ComboBox<>(FXCollections.observableArrayList(0, 1, 2, 3, 4, 5, 6));
    private final TableView<BankQuestionInfo> table = new TableView<>();
    private final TextArea preview = new TextArea();
    private final Label message = new Label("Đang tải môn học...");
    private final Button create = new Button("Tạo câu hỏi");
    private final Button edit = new Button("Sửa câu hỏi");
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
        table.getColumns().add(AssignmentUi.column("Mã câu", 75, q -> Long.toString(q.id())));
        table.getColumns().add(AssignmentUi.column("Độ khó", 80, q -> Integer.toString(q.difficulty())));
        table.getColumns().add(AssignmentUi.column("Nội dung câu hỏi", 480, q -> q.content().replaceAll("\\s+", " ")));
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
        create.setDisable(true);
        edit.setDisable(true);
        FlowPane subjects = new FlowPane(10, 8, new Label("Môn học"), subject, newSubject, refresh);
        FlowPane filters = new FlowPane(10, 8, difficulty, new Label("Câu trắc nghiệm (4 lựa chọn)"), create, edit);
        getChildren().addAll(subjects, filters, table, preview, message);
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

    private void addSubject() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(owner());
        dialog.setTitle("Thêm môn học");
        dialog.setHeaderText("Tên môn học");
        dialog.setContentText("Tên môn (tối đa 120 ký tự):");
        dialog.showAndWait().ifPresent(name -> {
            setDisable(true);
            AssignmentUi.async("create-subject", () -> service.createSubject(name), saved -> {
                if (disposed) return;
                setDisable(false);
                reloadSubjects(saved.id());
            }, ex -> { if (!disposed) { setDisable(false); AssignmentUi.error(owner(), ex.getMessage()); } });
        });
    }

    private void editQuestion(BankQuestionInfo existing) {
        QuestionEditorDialog.show(owner(), List.copyOf(subject.getItems()), subject.getValue(), existing).ifPresent(draft -> {
            setDisable(true);
            AssignmentUi.async("save-bank-question", () -> existing == null ? service.createQuestion(draft)
                    : service.updateQuestion(existing.id(), draft), saved -> {
                if (disposed) return;
                setDisable(false);
                questionSaved.accept(saved);
                reloadSubjects(saved.subjectId());
            }, ex -> { if (!disposed) { setDisable(false); AssignmentUi.error(owner(), ex.getMessage()); } });
        });
    }

    public void reloadSubjects(Long preferred) {
        long version = ++subjectsVersion;
        AssignmentUi.async("load-subjects", service::listSubjects, subjects -> {
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
        edit.setDisable(true);
        if (selected == null) {
            message.setText("Chưa có môn học. Bấm Thêm môn học để bắt đầu.");
            return;
        }
        Integer level = difficulty.getValue() == null || difficulty.getValue() == 0 ? null : difficulty.getValue();
        message.setText("Đang tải câu hỏi...");
        AssignmentUi.async("load-bank-questions", () -> service.listQuestions(selected.id(), level), questions -> {
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
