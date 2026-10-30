package com.ptit.lanexam.client;

import com.ptit.lanexam.common.ui.AppUi;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.function.IntConsumer;

/** Navigation never recreates question controls, so jumping preserves answers. */
public final class ExamQuestionNavigator extends VBox {
    private final Button[] buttons;
    private final boolean[] answered;
    private final boolean[] flagged;
    private final Label progress = AppUi.label("", "section-title");

    public ExamQuestionNavigator(int count, IntConsumer jump) {
        super(14);
        buttons = new Button[count]; answered = new boolean[count]; flagged = new boolean[count];
        getStyleClass().add("question-navigator");
        setPrefWidth(185); setMinWidth(185);
        progress.setId("exam-progress");
        TilePane grid = new TilePane(8, 8);
        grid.setPrefColumns(3);
        for (int index = 0; index < count; index++) {
            int target = index;
            Button button = new Button();
            button.setId("question-jump-" + (index + 1));
            button.getStyleClass().add("question-link");
            button.setMinSize(44, 34); button.setPrefSize(44, 34);
            button.setOnAction(event -> jump.accept(target));
            buttons[index] = button; grid.getChildren().add(button);
            update(index);
        }
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(AppUi.label("Câu hỏi", "eyebrow"), progress, scroll,
                AppUi.label("✓ Đã trả lời\n⚑ Đánh dấu xem lại", "muted"));
        updateProgress();
    }

    public void setAnswered(int index, boolean value) {
        answered[index] = value; update(index); updateProgress();
    }

    public void setFlagged(int index, boolean value) { flagged[index] = value; update(index); }

    private void update(int index) {
        Button button = buttons[index];
        button.setText(Integer.toString(index + 1) + (flagged[index] ? " ⚑" : answered[index] ? " ✓" : ""));
        button.getStyleClass().removeAll("answered", "flagged");
        if (answered[index]) button.getStyleClass().add("answered");
        if (flagged[index]) button.getStyleClass().add("flagged");
        String state = "Câu " + (index + 1) + (answered[index] ? ", đã trả lời" : ", chưa trả lời")
                + (flagged[index] ? ", đánh dấu xem lại" : "");
        button.setAccessibleText(state); button.setTooltip(new Tooltip(state));
    }

    private void updateProgress() {
        int completed = 0;
        for (boolean value : answered) if (value) completed++;
        progress.setText(completed + " / " + answered.length + " đã trả lời");
    }
}
