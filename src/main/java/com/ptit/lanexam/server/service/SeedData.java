package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.*;
import com.ptit.lanexam.server.persistence.JpaUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

public final class SeedData {
    private SeedData() {}

    public static void ensureSeeded() {
        ensureSeeded(JpaUtil::em);
    }

    static void ensureSeeded(Supplier<EntityManager> entityManagers) {
        EntityManager em = entityManagers.get();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();

            long studentCount = em.createQuery("select count(s) from Student s", Long.class).getSingleResult();
            if (studentCount == 0) {
                em.persist(new Student("B21DCCN001", "Nguyễn Văn An", LocalDate.of(2003, 5, 12)));
                em.persist(new Student("B21DCCN002", "Trần Minh Bình", LocalDate.of(2003, 8, 20)));
                em.persist(new Student("B21DCCN003", "Lê Hoàng Cường", LocalDate.of(2003, 11, 3)));
            }

            Set<String> existingCodes = new HashSet<>(em.createQuery(
                    "select upper(s.studentCode) from Student s where upper(s.studentCode) like 'B21DCCN%'", String.class).getResultList());
            for (int number = 4; number <= 103; number++) {
                String code = String.format(Locale.ROOT, "B21DCCN%03d", number);
                if (existingCodes.add(code))
                    em.persist(new Student(code, String.format(Locale.ROOT, "Sinh viên %03d", number), LocalDate.of(2003, 12, 12)));
            }

            long examCount = em.createQuery("select count(e) from Exam e", Long.class).getSingleResult();
            if (examCount == 0) {
                Exam exam = new Exam("Bài kiểm tra Lập trình mạng - Demo", 20, false);
                exam.addQuestion(new Question(
                        "TCP hoạt động chủ yếu ở tầng nào trong mô hình TCP/IP?",
                        QuestionType.MULTIPLE_CHOICE,
                        "Application", "Transport", "Internet", "Network Access", "B"));
                exam.addQuestion(new Question(
                        "Lớp Java nào thường được dùng phía Server để lắng nghe kết nối TCP?",
                        QuestionType.MULTIPLE_CHOICE,
                        "Socket", "ServerSocket", "DatagramSocket", "URL", "B"));
                exam.addQuestion(new Question(
                        "Giao thức nào không thiết lập kết nối trước khi truyền dữ liệu?",
                        QuestionType.MULTIPLE_CHOICE,
                        "TCP", "HTTP", "UDP", "FTP", "C"));
                exam.addQuestion(new Question(
                        "Lớp Java nào dùng để tạo một luồng thực thi mới?",
                        QuestionType.MULTIPLE_CHOICE,
                        "Thread", "String", "Socket", "Scanner", "A"));
                exam.addQuestion(new Question(
                        "Địa chỉ dùng để định danh thiết bị trong mạng IP được gọi là gì?",
                        QuestionType.MULTIPLE_CHOICE,
                        "URL", "Tên tệp", "Địa chỉ IP", "Số cổng", "C"));
                em.persist(exam);
            }

            tx.commit();
        } catch (RuntimeException e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }
    }
}
