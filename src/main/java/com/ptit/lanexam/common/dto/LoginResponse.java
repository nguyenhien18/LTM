package com.ptit.lanexam.common.dto;

public class LoginResponse {
    private boolean success;
    private String message;
    private String studentName;

    public LoginResponse() {}
    public LoginResponse(boolean success, String message, String studentName) {
        this.success = success;
        this.message = message;
        this.studentName = studentName;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }
}
