package com.ptit.lanexam.server.service;

import com.ptit.lanexam.common.JsonUtil;
import com.ptit.lanexam.server.entity.*;
import jakarta.persistence.EntityManager;
import java.util.*;

/** Immutable roster captured in the same transaction that opens the round. */
final class RoundRoster {
    private RoundRoster() {}
    static List<ClassService.RosterStudent> candidates(EntityManager em, CourseClass group, ExamShift shift) {
        if (shift != null) return shift.getCandidates().stream()
                .map(c -> ClassService.rosterStudent(c.getStudent(), c.getOrdinal())).toList();
        return em.createQuery("select cs from ClassStudent cs join fetch cs.student where cs.courseClass = :class order by cs.ordinal asc nulls last, cs.student.studentCode", ClassStudent.class)
                .setParameter("class", group).getResultList().stream()
                .map(cs -> ClassService.rosterStudent(cs.getStudent(), cs.getOrdinal())).toList();
    }
    static String write(List<ClassService.RosterStudent> rows) {
        try { return JsonUtil.MAPPER.writeValueAsString(rows); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Không lưu được danh sách ca thi.", ex); }
    }
    static List<ClassService.RosterStudent> read(Exam exam) {
        if (exam.getRosterJson() == null) return null; // Rounds opened by an older version.
        try { return List.copyOf(JsonUtil.MAPPER.readValue(exam.getRosterJson(),
                new com.fasterxml.jackson.core.type.TypeReference<List<ClassService.RosterStudent>>() {})); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Không đọc được danh sách ca thi đã chốt.", ex); }
    }
    static boolean eligible(Exam exam, String code) {
        var rows = read(exam);
        return rows == null || rows.stream().anyMatch(r -> r.code().equals(code));
    }
    static boolean same(List<ClassService.RosterStudent> first, List<ClassService.RosterStudent> second) {
        return first.size() == second.size() && new HashSet<>(first).equals(new HashSet<>(second));
    }
}
