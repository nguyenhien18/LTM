package com.ptit.lanexam.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class JsonUtil {
    public static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private JsonUtil() {}

    public static NetworkMessage message(MessageType type, Object payload) {
        JsonNode node = payload == null ? MAPPER.nullNode() : MAPPER.valueToTree(payload);
        return new NetworkMessage(type, node);
    }

    public static String toJson(NetworkMessage message) {
        try {
            return MAPPER.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không thể chuyển message sang JSON", e);
        }
    }

    public static NetworkMessage fromJson(String json) {
        try {
            return MAPPER.readValue(json, NetworkMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON không hợp lệ", e);
        }
    }

    public static <T> T payload(NetworkMessage message, Class<T> clazz) {
        return MAPPER.convertValue(message.getPayload(), clazz);
    }
}
