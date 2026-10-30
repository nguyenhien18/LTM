package com.ptit.lanexam.server;

import com.ptit.lanexam.common.ui.*;
import com.ptit.lanexam.server.service.*;
import javafx.collections.*;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

final class ExamResultsPane extends BorderPane {
    private record ClassFilter(Long id, String label) { @Override public String toString() { return label; } }
    private record RoundFilter(Long id, String label) { @Override public String toString() { return label; } }
    private final ObservableList<ExamResultRow> all = FXCollections.observableArrayList();
    private final FilteredList<ExamResultRow> filtered = new FilteredList<>(all);
    private final ComboBox<ClassFilter> group = new ComboBox<>();
    private final ComboBox<RoundFilter> round = new ComboBox<>();
    private final Label status = AppUi.label("", "muted");
    private final Button refresh = new Button("Làm mới"), export = new Button("Xuất CSV");
    private final ExamService exams;
    private final ClassService classes;
    private boolean disposed;
    ExamResultsPane(Window owner, ExamService exams, ClassService classes) {
        this.exams = exams; this.classes = classes;
        group.setId("results-class"); round.setId("results-round"); export.setId("results-export"); refresh.setId("results-refresh");
        group.getItems().add(new ClassFilter(null, "Tất cả lớp")); group.getSelectionModel().selectFirst();
        round.getItems().add(new RoundFilter(null, "Tất cả ca thi")); round.getSelectionModel().selectFirst();
        group.valueProperty().addListener((o, a, b) -> filter()); round.valueProperty().addListener((o, a, b) -> filter());
        TableView<ExamResultRow> table = new TableView<>(filtered); table.setId("teacher-results");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(UiSupport.column("Ca thi", 70, r -> "#" + r.roundId()));
        table.getColumns().add(UiSupport.column("Mã SV", 130, ExamResultRow::studentCode));
        table.getColumns().add(UiSupport.column("Mã đề", 70, r -> r.variantCode() == null ? "Gốc" : r.variantCode()));
        table.getColumns().add(UiSupport.column("Họ tên", 180, ExamResultRow::fullName));
        table.getColumns().add(UiSupport.column("Trạng thái", 170, r -> r.status().toString()));
        table.getColumns().add(UiSupport.column("Đề / Lớp", 270, ExamResultRow::examTitle));
        table.getColumns().add(UiSupport.column("Điểm", 85, r -> r.score() == null ? "" : String.format(Locale.ROOT, "%.2f", r.score())));
        table.getColumns().add(UiSupport.column("Đúng", 70, r -> r.correct() == null ? "" : r.correct() + "/" + r.total()));
        table.getColumns().add(UiSupport.column("Nộp lúc", 160, r -> r.submittedAt() == null ? "" : r.submittedAt().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))));
        table.setPlaceholder(new Label("Chưa có danh sách ca thi phù hợp với bộ lọc."));
        refresh.setOnAction(e -> reload());
        export.setOnAction(e -> {
            FileChooser chooser = new FileChooser(); chooser.setTitle("Xuất bảng điểm đang lọc"); chooser.setInitialFileName("ket-qua-thi.csv");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV UTF-8", "*.csv"));
            var file = chooser.showSaveDialog(owner); if (file == null) return;
            List<ExamResultRow> rows = List.copyOf(filtered); export.setDisable(true);
            UiSupport.async("export-exam-results", () -> { Files.writeString(file.toPath(), ExamResultsCsv.format(rows), StandardCharsets.UTF_8); return file.getAbsolutePath(); },
                    path -> { if (!disposed) { status.setText("Đã xuất " + rows.size() + " dòng thí sinh: " + path); export.setDisable(filtered.isEmpty()); } },
                    ex -> { if (!disposed) { status.setText(ex.getMessage()); export.setDisable(filtered.isEmpty()); } });
        });
        VBox content = new VBox(14, AppUi.toolbar(group, round, AppUi.spacer(), refresh, export), table, status);
        VBox.setVgrow(table, Priority.ALWAYS); setCenter(content); reload();
    }
    void reload() {
        if (disposed) return; refresh.setDisable(true);
        record Data(List<ClassInfo> groups, List<ExamResultRow> results) {}
        UiSupport.async("load-exam-results", () -> new Data(classes.list(), exams.listRoundResults()), data -> {
            if (disposed) return;
            Long classId = group.getValue() == null ? null : group.getValue().id(), roundId = round.getValue() == null ? null : round.getValue().id();
            group.getItems().setAll(new ClassFilter(null, "Tất cả lớp"));
            data.groups().forEach(g -> group.getItems().add(new ClassFilter(g.id(), g.code())));
            group.setValue(group.getItems().stream().filter(g -> Objects.equals(g.id(), classId)).findFirst().orElse(group.getItems().getFirst()));
            round.getItems().setAll(new RoundFilter(null, "Tất cả ca thi"));
            data.results().stream().map(ExamResultRow::roundId).distinct().sorted(Comparator.reverseOrder()).forEach(id -> round.getItems().add(new RoundFilter(id, "Ca #" + id)));
            round.setValue(round.getItems().stream().filter(r -> Objects.equals(r.id(), roundId)).findFirst().orElse(round.getItems().getFirst()));
            all.setAll(data.results()); refresh.setDisable(false); filter();
        }, ex -> { if (!disposed) { status.setText(ex.getMessage()); refresh.setDisable(false); } });
    }
    private void filter() {
        ClassFilter g = group.getValue(); RoundFilter r = round.getValue();
        filtered.setPredicate(row -> (g == null || g.id() == null || g.id().equals(row.classId())) && (r == null || r.id() == null || r.id().equals(row.roundId())));
        long graded = filtered.stream().filter(row -> row.score() != null).count();
        long absent = filtered.stream().filter(row -> row.status() == ExamResultRow.Status.ABSENT).count();
        status.setText(filtered.size() + " dòng thí sinh · " + graded + " đã có điểm · " + absent + " vắng thi"); export.setDisable(filtered.isEmpty());
    }
    void dispose() { disposed = true; }
}
