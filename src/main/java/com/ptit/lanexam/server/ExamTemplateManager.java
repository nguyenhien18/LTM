package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.AppUi;
import com.ptit.lanexam.common.ui.UiSupport;
import com.ptit.lanexam.server.service.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/** Shared exam templates; publishing a round belongs to ExamOrganizer. */
public final class ExamTemplateManager extends BorderPane {
    private record SubjectFilter(String name, boolean all) {
        @Override public String toString() { return all ? "Tất cả môn" : name == null ? "Chưa phân loại" : name; }
        boolean matches(ExamSummary exam) { return all || Objects.equals(name, exam.subjectName()); }
    }
    private static final SubjectFilter ALL_SUBJECTS = new SubjectFilter(null, true);
    private final ExamService exams;
    private final ObservableList<ExamSummary> all = FXCollections.observableArrayList();
    private final FilteredList<ExamSummary> filtered = new FilteredList<>(all);
    private final TableView<ExamSummary> table = new TableView<>(filtered);
    private final TextField search = new TextField();
    private final ComboBox<SubjectFilter> subjectFilter = new ComboBox<>(FXCollections.observableArrayList(ALL_SUBJECTS));
    private final Button create = new Button("Tạo đề mẫu"), refresh = new Button("Làm mới");
    private final Label message = AppUi.label("", "muted");
    private boolean disposed, saving;
    private long loadVersion;

    public ExamTemplateManager(Window owner, ExamService exams, QuestionBankService bank, Consumer<String> announce) {
        AppUi.desktopStyle(this);
        this.exams = exams;
        table.setId("teacher-exam-templates");
        table.getColumns().add(UiSupport.column("Tên đề", 340, ExamSummary::title));
        table.getColumns().add(UiSupport.column("Môn", 220, e -> e.subjectName() == null ? "Chưa phân loại" : e.subjectName()));
        table.getColumns().add(UiSupport.column("Số câu", 90, e -> Integer.toString(e.questionCount())));
        table.getColumns().add(UiSupport.column("Thời gian", 110, e -> e.durationMinutes() + " phút"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        search.setId("exam-template-search"); search.setPromptText("Tìm tên đề..."); search.setPrefWidth(280);
        search.textProperty().addListener((o, a, b) -> updateFilter());
        subjectFilter.setId("exam-template-subject-filter");
        subjectFilter.setAccessibleText("Lọc đề thi theo môn học");
        subjectFilter.setPrefWidth(240);
        subjectFilter.setMinWidth(Region.USE_PREF_SIZE);
        subjectFilter.setValue(ALL_SUBJECTS);
        subjectFilter.valueProperty().addListener((o, a, b) -> updateFilter());
        create.setId("exam-template-create"); create.getStyleClass().add("primary");
        create.setOnAction(event -> ExamEditorDialog.show(owner, bank).ifPresent(draft -> {
            saving = true; create.setDisable(true);
            UiSupport.async("save-exam-template", () -> exams.createExamFromBank(draft), saved -> {
                if (disposed) return;
                saving = false; create.setDisable(false); search.clear(); reload(saved.id());
                announce.accept("Đã lưu " + saved.title() + ". Chuyển sang tab Ca thi để sử dụng đề.");
            }, ex -> {
                if (disposed) return;
                saving = false; create.setDisable(false); UiSupport.error(owner, ex.getMessage());
            });
        }));
        refresh.setId("exam-templates-refresh"); refresh.setOnAction(event -> reload(null));
        VBox body = new VBox(14, AppUi.toolbar(create, AppUi.spacer(), subjectFilter, search, refresh), table, message);
        VBox.setVgrow(table, Priority.ALWAYS); setCenter(body); reload(null);
    }

    private static String searchText(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT).strip();
    }

    private void updateFilter() {
        String query = searchText(search.getText());
        SubjectFilter subject = subjectFilter.getValue();
        filtered.setPredicate(exam -> (subject == null || subject.matches(exam))
                && searchText(exam.title()).contains(query));
        boolean filtering = !query.isEmpty() || subject != null && !subject.all();
        message.setText(filtering ? "Hiển thị " + filtered.size() + "/" + all.size() + " đề thi" : all.size() + " đề thi");
        table.setPlaceholder(new Label(all.isEmpty() ? "Chưa có đề thi. Chọn Tạo đề mẫu để thêm."
                : "Không có đề phù hợp với bộ lọc."));
    }

    private void updateSubjects(List<ExamSummary> rows, Long createdId) {
        SubjectFilter selected = subjectFilter.getValue();
        List<SubjectFilter> choices = new ArrayList<>(); choices.add(ALL_SUBJECTS);
        rows.stream().map(ExamSummary::subjectName).distinct()
                .sorted(Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .map(name -> new SubjectFilter(name, false)).forEach(choices::add);
        // A newly saved template must remain visible even when another subject was selected.
        if (createdId != null && selected != null && !selected.all()) {
            ExamSummary created = rows.stream().filter(exam -> exam.id().equals(createdId)).findFirst().orElse(null);
            if (created != null && !selected.matches(created)) selected = new SubjectFilter(created.subjectName(), false);
        }
        subjectFilter.getItems().setAll(choices);
        subjectFilter.setValue(choices.contains(selected) ? selected : ALL_SUBJECTS);
        updateFilter();
    }

    private void reload(Long selectedId) {
        if (disposed) return;
        long request = ++loadVersion; refresh.setDisable(true); create.setDisable(true);
        ExamSummary selected = table.getSelectionModel().getSelectedItem();
        Long id = selectedId != null ? selectedId : selected == null ? null : selected.id();
        UiSupport.async("load-exam-templates", exams::listExams, rows -> {
            if (disposed || request != loadVersion) return;
            all.setAll(rows);
            updateSubjects(rows, selectedId);
            table.getSelectionModel().select(filtered.stream().filter(e -> e.id().equals(id)).findFirst().orElse(filtered.isEmpty() ? null : filtered.getFirst()));
            refresh.setDisable(false); create.setDisable(saving);
        }, ex -> {
            if (disposed || request != loadVersion) return;
            refresh.setDisable(false); create.setDisable(saving); message.setText("Không tải được đề: " + ex.getMessage());
        });
    }

    public void dispose() { disposed = true; loadVersion++; }
}
