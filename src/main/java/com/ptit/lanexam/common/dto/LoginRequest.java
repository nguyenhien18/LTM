package com.ptit.lanexam.common.dto;

public class LoginRequest {
    private String studentCode;
    private String birthDate;
    private boolean focusMonitoring;

    public LoginRequest() {}
    public LoginRequest(String studentCode, String birthDate) {
        this.studentCode = studentCode;
        this.birthDate = birthDate;
    }
    public LoginRequest(String studentCode, String birthDate, boolean focusMonitoring) {
        this(studentCode, birthDate); this.focusMonitoring = focusMonitoring;
    }
    public boolean isFocusMonitoring() { return focusMonitoring; }
    public void setFocusMonitoring(boolean enabled) { focusMonitoring = enabled; }

    public String getStudentCode() { return studentCode; }
    public void setStudentCode(String studentCode) { this.studentCode = studentCode; }
    public String getBirthDate() { return birthDate; }
    public void setBirthDate(String birthDate) { this.birthDate = birthDate; }
}
