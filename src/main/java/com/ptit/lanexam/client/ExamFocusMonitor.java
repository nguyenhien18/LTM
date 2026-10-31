package com.ptit.lanexam.client;

import com.ptit.lanexam.common.dto.ExamFocusChange;
import javafx.application.Platform;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.stage.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

/** Treat owned dialogs as part of the exam application, so they cannot create false warnings. */
final class ExamFocusMonitor implements AutoCloseable {
    private final Stage stage;
    private final Consumer<ExamFocusChange> send;
    private final Consumer<Boolean> awayChanged;
    private final Set<Window> observed = new HashSet<>();
    private Long examId;
    private String episode;
    private boolean closed;
    private long leftNanos, lastReportNanos;
    private final Timeline polling = new Timeline(new KeyFrame(Duration.millis(250), e -> evaluate()));
    private final ChangeListener<Boolean> changed = (o, a, b) -> Platform.runLater(this::evaluate);
    private final ListChangeListener<Window> windows = change -> {
        while (change.next()) {
            for (Window window : change.getRemoved()) unobserve(window);
            for (Window window : change.getAddedSubList()) observe(window);
        }
        Platform.runLater(this::evaluate);
    };
    ExamFocusMonitor(Stage stage, Consumer<ExamFocusChange> send) {
        this(stage, send, away -> {});
    }
    ExamFocusMonitor(Stage stage, Consumer<ExamFocusChange> send, Consumer<Boolean> awayChanged) {
        this.stage = stage; this.send = send; this.awayChanged = awayChanged;
        Window.getWindows().forEach(this::observe); Window.getWindows().addListener(windows);
        stage.iconifiedProperty().addListener(changed);
        stage.fullScreenProperty().addListener(changed);
        polling.setCycleCount(Timeline.INDEFINITE);
    }
    void start(Long id) { stop(); examId = id; polling.playFromStart(); Platform.runLater(this::evaluate); }
    void stop() { polling.stop(); examId = null; episode = null; awayChanged.accept(false); }
    private void observe(Window window) { if (observed.add(window)) window.focusedProperty().addListener(changed); }
    private void unobserve(Window window) { if (observed.remove(window)) window.focusedProperty().removeListener(changed); }
    private boolean owned(Window window) {
        for (Window current = window; current != null;) {
            if (current == stage) return true;
            current = current instanceof Stage child ? child.getOwner()
                    : current instanceof PopupWindow popup ? popup.getOwnerWindow() : null;
        }
        return false;
    }
    private void evaluate() {
        if (closed || examId == null) return;
        boolean ownedDialogFocused = Window.getWindows().stream()
                .anyMatch(window -> window != stage && owned(window) && window.isFocused());
        boolean focused = !stage.isIconified() && (ownedDialogFocused || (stage.isFocused() && stage.isFullScreen()));
        long now = System.nanoTime();
        if (!focused && episode == null) {
            episode = UUID.randomUUID().toString(); send.accept(new ExamFocusChange(examId, episode, false));
            leftNanos = lastReportNanos = now;
            awayChanged.accept(true);
        } else if (!focused && now - leftNanos >= TimeUnit.MILLISECONDS.toNanos(ExamFocusChange.AWAY_THRESHOLD_MILLIS)
                && now - lastReportNanos >= TimeUnit.SECONDS.toNanos(1)) {
            // Retry with the same ID: a delayed first TCP frame must not lose the only threshold confirmation.
            lastReportNanos = now; send.accept(new ExamFocusChange(examId, episode, false));
        } else if (focused && episode != null) {
            String previous = episode; episode = null; send.accept(new ExamFocusChange(examId, previous, true));
            awayChanged.accept(false);
        }
    }
    @Override public void close() {
        closed = true; stop(); Window.getWindows().removeListener(windows);
        stage.iconifiedProperty().removeListener(changed); List.copyOf(observed).forEach(this::unobserve);
        stage.fullScreenProperty().removeListener(changed);
    }
}
