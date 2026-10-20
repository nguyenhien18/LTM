package com.ptit.lanexam.common;

import java.io.IOException;
import java.io.Reader;

public final class TransferLimits {
    public static final int MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;
    // A 5 MiB attachment occupies about 7 MiB after Base64 encoding in JSON.
    public static final int MAX_MESSAGE_CHARS = 8 * 1024 * 1024;
    public static final int MAX_TEXT_LENGTH = 20_000;

    private TransferLimits() {}

    public static void validateAttachment(String name, byte[] data) {
        if (data == null) {
            if (name != null && !name.isBlank()) throw new IllegalArgumentException("Thiếu dữ liệu tệp.");
            return;
        }
        if (name == null || name.isBlank() || name.length() > 255 || name.equals(".") || name.equals("..")
                || name.chars().anyMatch(c -> c < 32 || c == 127 || c == 34 || c == 92 || "/:*?<>|".indexOf(c) >= 0))
            throw new IllegalArgumentException("Tên tệp không hợp lệ.");
        if (data.length == 0 || data.length > MAX_ATTACHMENT_BYTES)
            throw new IllegalArgumentException("Tệp phải có dữ liệu và không vượt quá 5 MB.");
    }

    public static String readLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int character;
        while ((character = reader.read()) != -1) {
            if (character == '\n') {
                if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r')
                    line.setLength(line.length() - 1);
                return line.toString();
            }
            if (line.length() >= MAX_MESSAGE_CHARS) throw new IOException("Gói dữ liệu vượt giới hạn 8 MB.");
            line.append((char) character);
        }
        return line.isEmpty() ? null : line.toString();
    }
}
