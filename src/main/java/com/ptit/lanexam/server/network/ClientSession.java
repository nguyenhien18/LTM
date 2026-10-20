package com.ptit.lanexam.server.network;

public class ClientSession {
    private final String studentCode;
    private final String fullName;
    private final String ip;
    private final ClientHandler handler;
    private volatile ClientStatus status = ClientStatus.ONLINE;

    public ClientSession(String studentCode, String fullName, String ip, ClientHandler handler) {
        this.studentCode = studentCode;
        this.fullName = fullName;
        this.ip = ip;
        this.handler = handler;
    }

    public String getStudentCode() { return studentCode; }
    public String getFullName() { return fullName; }
    public String getIp() { return ip; }
    public ClientHandler getHandler() { return handler; }
    public ClientStatus getStatus() { return status; }
    public void setStatus(ClientStatus status) { this.status = status; }

    public ClientSessionInfo info() {
        return new ClientSessionInfo(studentCode, fullName, ip, status.name());
    }
}
