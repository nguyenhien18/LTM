package com.ptit.lanexam.common.dto;

/** No student identity or client timestamp: both are supplied by the Server. */
public record ExamFocusChange(Long examId, String episodeId, boolean focused) {
    public static final long AWAY_THRESHOLD_MILLIS = 5_000;
}
