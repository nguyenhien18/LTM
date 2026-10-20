package com.ptit.lanexam.common;

import com.ptit.lanexam.common.dto.AssignmentMessages.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AssignmentProtocolTest {
    @Test
    void maximumSizeAttachmentSurvivesJsonAndLineFraming() throws Exception {
        byte[] data = new byte[TransferLimits.MAX_ATTACHMENT_BYTES];
        new Random(23).nextBytes(data);
        SubmitRequest request = new SubmitRequest(42L, "Dòng 1\nDòng 2: Tiếng Việt", "bài làm.zip", data, false);
        String json = JsonUtil.toJson(JsonUtil.message(MessageType.ASSIGNMENT_SUBMIT, request));
        assertTrue(json.length() < TransferLimits.MAX_MESSAGE_CHARS);
        StringReader reader = new StringReader(json + "\r\n" + JsonUtil.toJson(JsonUtil.message(MessageType.PING, null)) + "\n");
        SubmitRequest restored = JsonUtil.payload(JsonUtil.fromJson(TransferLimits.readLine(reader)), SubmitRequest.class);
        assertEquals(request.content(), restored.content());
        assertEquals(request.fileName(), restored.fileName());
        assertArrayEquals(data, restored.fileData());
        assertEquals(MessageType.PING, JsonUtil.fromJson(TransferLimits.readLine(reader)).getType());
        assertNull(TransferLimits.readLine(reader));
    }

    @Test
    void rejectsOversizedProtocolFrames() {
        assertThrows(IOException.class,
                () -> TransferLimits.readLine(new StringReader("x".repeat(TransferLimits.MAX_MESSAGE_CHARS + 1))));
    }
}
