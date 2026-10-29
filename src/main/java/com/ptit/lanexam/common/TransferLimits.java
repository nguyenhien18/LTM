package com.ptit.lanexam.common;

import java.io.IOException;
import java.io.Reader;

public final class TransferLimits {
    // Bound complete JSON frames, including large exam question sets.
    public static final int MAX_MESSAGE_CHARS = 8 * 1024 * 1024;

    private TransferLimits() {}

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
