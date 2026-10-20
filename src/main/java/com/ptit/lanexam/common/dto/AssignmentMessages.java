package com.ptit.lanexam.common.dto;

import java.util.List;

public final class AssignmentMessages {
    private AssignmentMessages() {}

    public record ListResult(List<AssignmentInfo> items) {}
    public record SubmitRequest(Long assignmentId, String content, String fileName,
                                byte[] fileData, boolean keepExistingFile) {}
    public record FileRequest(Long assignmentId, boolean submission) {}
    public record FileResult(Long assignmentId, boolean submission, String fileName, byte[] data) {}
}
