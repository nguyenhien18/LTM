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
import java.util.Locale;
import java.util.function.Consumer;

/** Shared exam templates; publishing a round belongs to ExamOrganizer. */
public final class ExamTemplateManager extends BorderPane {
    private final ExamService exams;
    private final ObservableList<ExamSummary> all = FXCollections.observableArrayList();
    private final FilteredList<ExamSummary> filtered = new FilteredList<>(all);
    private final TableView<ExamSummary> table = new TableView<>(filtered);
    private final TextField search = new TextField();
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
        table.setPlaceholder(new Label("Chưa có đề phù hợp. Tạo đề mẫu hoặc đổi từ khóa tìm kiếm."));
        search.setId("exam-template-search"); search.setPromptText("Tìm tên đề..."); search.setPrefWidth(280);
        search.textProperty().addListener((o, a, b) -> filtered.setPredicate(e -> e.title().toLowerCase(Locale.ROOT).contains(b.strip().toLowerCase(Locale.ROOT))));
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
        VBox body = new VBox(14, AppUi.toolbar(create, AppUi.spacer(), search, refresh), table, message);
        VBox.setVgrow(table, Priority.ALWAYS); setCenter(body); reload(null);
    }

    private void reload(Long selectedId) {
        if (disposed) return;
        long request = ++loadVersion; refresh.setDisable(true); create.setDisable(true);
        ExamSummary selected = table.getSelectionModel().getSelectedItem();
        Long id = selectedId != null ? selectedId : selected == null ? null : selected.id();
        UiSupport.async("load-exam-templates", exams::listExams, rows -> {
            if (disposed || request != loadVersion) return;
            all.setAll(rows);
            table.getSelectionModel().select(filtered.stream().filter(e -> e.id().equals(id)).findFirst().orElse(filtered.isEmpty() ? null : filtered.getFirst()));
            refresh.setDisable(false); create.setDisable(saving);
            message.setText(rows.size() + " đề thi");
        }, ex -> {
            if (disposed || request != loadVersion) return;
            refresh.setDisable(false); create.setDisable(saving); message.setText("Không tải được đề: " + ex.getMessage());
        });
    }

    public void dispose() { disposed = true; loadVersion++; }
}
