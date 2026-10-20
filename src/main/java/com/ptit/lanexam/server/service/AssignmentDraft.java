package com.ptit.lanexam.server.service;

import java.time.Instant;

public record AssignmentDraft(String title, String description, Instant dueAt, String fileName, byte[] fileData) {}
