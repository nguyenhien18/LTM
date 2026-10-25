package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.Student;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SeedDataIntegrationTest {
    private EntityManagerFactory factory;
    @BeforeEach void setUp() {
        factory = Persistence.createEntityManagerFactory("lan-exam-pu", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:seed_students_" + UUID.randomUUID() + ";MODE=MySQL",
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", "",
                "hibernate.hbm2ddl.auto", "create-drop", "hibernate.dialect", "org.hibernate.dialect.H2Dialect"));
    }
    @AfterEach void tearDown() { if (factory != null) factory.close(); }

    @Test void addsOneHundredStudentsWithFixedBirthdayAndDoesNotDuplicateOnRestart() {
        SeedData.ensureSeeded(factory::createEntityManager);
        SeedData.ensureSeeded(factory::createEntityManager);
        try (EntityManager em = factory.createEntityManager()) {
            assertEquals(103L, em.createQuery("select count(s) from Student s", Long.class).getSingleResult());
            assertEquals(100L, em.createQuery("select count(s) from Student s where s.birthDate = :birthday", Long.class)
                    .setParameter("birthday", LocalDate.of(2003, 12, 12)).getSingleResult());
            assertEquals(1L, em.createQuery("select count(e) from Exam e", Long.class).getSingleResult());
        }
        AuthService auth = new AuthService(factory::createEntityManager);
        assertTrue(auth.authenticate("B21DCCN004", "2003-12-12").isPresent());
        assertTrue(auth.authenticate("b21dccn103", "2003-12-12").isPresent());
        assertTrue(auth.authenticate("B21DCCN004", "2003-05-12").isEmpty());
    }

    @Test void addsMissingAccountsWithoutChangingExistingStudentOrUnlockingThem() {
        Long id;
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            Student existing = new Student("B21DCCN050", "Học viên đã có", LocalDate.of(2003, 1, 1));
            existing.setAccountLock(true, "Đang bị khóa"); em.persist(existing); em.getTransaction().commit(); id = existing.getId();
        }
        SeedData.ensureSeeded(factory::createEntityManager);
        try (EntityManager em = factory.createEntityManager()) {
            Student existing = em.find(Student.class, id);
            assertEquals("Học viên đã có", existing.getFullName());
            assertEquals(LocalDate.of(2003, 1, 1), existing.getBirthDate());
            assertTrue(existing.isAccountLocked()); assertEquals("Đang bị khóa", existing.getLockReason());
            assertEquals(100L, em.createQuery("select count(s) from Student s", Long.class).getSingleResult());
        }
        assertTrue(new AuthService(factory::createEntityManager).authenticate("B21DCCN103", "2003-12-12").isPresent());
    }
}
