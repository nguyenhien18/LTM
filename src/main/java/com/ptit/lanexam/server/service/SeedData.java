package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.*;
import com.ptit.lanexam.server.persistence.JpaUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

import java.time.LocalDate;

public final class SeedData {
    private SeedData() {}

    public static void ensureSeeded() {
        EntityManager em = JpaUtil.em();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();

            long studentCount = em.createQuery("select count(s) from Student s", Long.class).getSingleResult();
            if (studentCount == 0) {
                em.persist(new Student("B21DCCN001", "Nguyễn Văn An", LocalDate.of(2003, 5, 12)));
                em.persist(new Student("B21DCCN002", "Trần Minh Bình", LocalDate.of(2003, 8, 20)));
                em.persist(new Student("B21DCCN003", "Lê Hoàng Cường", LocalDate.of(2003, 11, 3)));
            }

            long examCount = em.createQuery("select count(e) from Exam e", Long.class).getSingleResult();
            if (examCount == 0) {
                Exam exam = new Exam("Bài kiểm tra Lập trình mạng - Demo", 20, true);
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
                        "Điền từ khóa Java dùng để tạo một luồng thực thi mới (viết tên lớp).",
                        QuestionType.TEXT,
                        null, null, null, null, "THREAD"));
                exam.addQuestion(new Question(
                        "Địa chỉ dùng để định danh thiết bị trong mạng IP được gọi là gì?",
                        QuestionType.TEXT,
                        null, null, null, null, "IP"));
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
