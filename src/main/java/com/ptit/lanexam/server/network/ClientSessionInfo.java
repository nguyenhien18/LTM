package com.ptit.lanexam.server.network;

public record ClientSessionInfo(String studentCode, String fullName, String ip, String status,
                                Long examId, String examTitle) {}
