package com.ptit.lanexam.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

@MappedSuperclass
public abstract class TeacherOwned {
    @Column(name = "owner_teacher_id")
    private Long ownerTeacherId;
    public Long getOwnerTeacherId() { return ownerTeacherId; }
    public void setOwnerTeacherId(Long id) { ownerTeacherId = id; }
}
