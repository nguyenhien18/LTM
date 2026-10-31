package com.ptit.lanexam.client;

import com.ptit.lanexam.common.dto.ExamFocusChange;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.stage.*;
import java.util.*;
import java.util.function.Consumer;

/** Treat owned dialogs as part of the exam application, so they cannot create false warnings. */
final class ExamFocusMonitor implements AutoCloseable {
    private final Stage stage;
    private final Consumer<ExamFocusChange> send;
    private final Set<Window> observed = new HashSet<>();
    private Long examId;
    private String episode;
    private boolean closed;
    private final PauseTransition confirmAway = new PauseTransition(Duration.seconds(3.1));
    private final ChangeListener<Boolean> changed = (o, a, b) -> Platform.runLater(this::evaluate);
    private final ListChangeListener<Window> windows = change -> {
        while (change.next()) {
            for (Window window : change.getRemoved()) unobserve(window);
            for (Window window : change.getAddedSubList()) observe(window);
        }
        Platform.runLater(this::evaluate);
    };
    ExamFocusMonitor(Stage stage, Consumer<ExamFocusChange> send) {
        this.stage = stage; this.send = send;
        Window.getWindows().forEach(this::observe); Window.getWindows().addListener(windows);
        stage.iconifiedProperty().addListener(changed);
        confirmAway.setOnFinished(e -> {
            if (!closed && examId != null && episode != null) send.accept(new ExamFocusChange(examId, episode, false));
        });
    }
    void start(Long id) { stop(); examId = id; Platform.runLater(this::evaluate); }
    void stop() { confirmAway.stop(); examId = null; episode = null; }
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
        boolean focused = !stage.isIconified() && Window.getWindows().stream().anyMatch(window -> owned(window) && window.isFocused());
        if (!focused && episode == null) {
            episode = UUID.randomUUID().toString(); send.accept(new ExamFocusChange(examId, episode, false));
            confirmAway.playFromStart();
        } else if (focused && episode != null) {
            confirmAway.stop();
            String previous = episode; episode = null; send.accept(new ExamFocusChange(examId, previous, true));
        }
    }
    @Override public void close() {
        closed = true; stop(); Window.getWindows().removeListener(windows);
        stage.iconifiedProperty().removeListener(changed); List.copyOf(observed).forEach(this::unobserve);
    }
}
