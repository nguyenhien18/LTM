package com.ptit.lanexam.common;

import com.ptit.lanexam.common.dto.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExamProtocolTest {
    @Test
    void dashboardMessagesRoundTripAsJson() {
        ExamAvailability offered = new ExamAvailability(new ExamOffer(12L, "Bài kiểm tra", 120, false));
        NetworkMessage offerMessage = JsonUtil.fromJson(JsonUtil.toJson(
                JsonUtil.message(MessageType.EXAM_AVAILABLE, offered)));
        assertEquals(offered, JsonUtil.payload(offerMessage, ExamAvailability.class));

        ExamAvailability empty = new ExamAvailability(null);
        NetworkMessage emptyMessage = JsonUtil.fromJson(JsonUtil.toJson(
                JsonUtil.message(MessageType.EXAM_AVAILABLE, empty)));
        assertNull(JsonUtil.payload(emptyMessage, ExamAvailability.class).exam());

        ExamHistoryItem item = new ExamHistoryItem(12L, "Bài kiểm tra", 8.0, 4, 5,
                LocalDateTime.of(2026, 10, 15, 20, 30));
        NetworkMessage historyMessage = JsonUtil.fromJson(JsonUtil.toJson(
                JsonUtil.message(MessageType.HISTORY_RESULT, new ExamHistoryResponse(List.of(item)))));
        assertEquals(item, JsonUtil.payload(historyMessage, ExamHistoryResponse.class).items().getFirst());

        NetworkMessage joinMessage = JsonUtil.fromJson(JsonUtil.toJson(
                JsonUtil.message(MessageType.JOIN_EXAM, new JoinExamRequest(12L))));
        assertEquals(12L, JsonUtil.payload(joinMessage, JoinExamRequest.class).examId());
    }
}
