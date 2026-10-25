package com.ptit.lanexam.server.service;

public record SubjectInfo(Long id, String name, long questionCount) {
    @Override public String toString() { return name; }
}
