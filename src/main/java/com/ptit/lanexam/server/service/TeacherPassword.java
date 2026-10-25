package com.ptit.lanexam.server.service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

final class TeacherPassword {
    private static final int ITERATIONS = 600_000;
    private TeacherPassword() {}
    static void validate(String password) {
        if (password == null || password.length() < 8 || password.length() > 128)
            throw new IllegalArgumentException("Mật khẩu phải có từ 8 đến 128 ký tự.");
    }
    static String hash(String password) {
        validate(password);
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }
    static boolean matches(String password, String encoded) {
        if (password == null || password.length() > 128) return false;
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 4 || !parts[0].equals("pbkdf2")) return false;
            return MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]),
                    derive(password, Base64.getDecoder().decode(parts[2]), Integer.parseInt(parts[1])));
        } catch (IllegalArgumentException ex) { return false; }
    }
    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("Không thể xử lý mật khẩu.", ex); }
        finally { spec.clearPassword(); }
    }
}
