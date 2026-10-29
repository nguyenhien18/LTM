package com.ptit.lanexam.server.service;

public record ClassInfo(Long id, String code, String name, String semester, Long subjectId,
                        String subjectName, long studentCount) {
    @Override public String toString() { return code + " · " + name + " · " + semester; }
}
