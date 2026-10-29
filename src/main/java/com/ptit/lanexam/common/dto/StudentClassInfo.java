package com.ptit.lanexam.common.dto;

public record StudentClassInfo(Long id, String code, String name, String subjectName, String semester) {
    @Override public String toString() { return code + " · " + name; }
}
