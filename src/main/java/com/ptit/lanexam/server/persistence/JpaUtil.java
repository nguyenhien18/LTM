package com.ptit.lanexam.server.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import java.util.HashMap;
import java.util.Map;

public final class JpaUtil {
    private static EntityManagerFactory emf;

    private JpaUtil() {}

    public static synchronized void init() {
        if (emf != null && emf.isOpen()) return;

        Map<String, Object> properties = new HashMap<>();
        properties.put("jakarta.persistence.jdbc.url", env("DB_URL",
                "jdbc:mysql://localhost:3306/lan_exam?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh"));
        properties.put("jakarta.persistence.jdbc.user", env("DB_USER", "root"));
        properties.put("jakarta.persistence.jdbc.password", env("DB_PASSWORD", "root"));

        emf = Persistence.createEntityManagerFactory("lan-exam-pu", properties);
    }

    public static EntityManager em() {
        if (emf == null || !emf.isOpen()) init();
        return emf.createEntityManager();
    }

    public static synchronized void close() {
        if (emf != null && emf.isOpen()) emf.close();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
