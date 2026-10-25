package com.ptit.lanexam.server.service;

import com.ptit.lanexam.server.entity.TeacherOwned;
import java.util.Objects;

record TeacherScope(Long teacherId) {
    TeacherScope {
        if (teacherId != null && teacherId <= 0) throw new IllegalArgumentException("Mã giáo viên không hợp lệ.");
    }
    String predicate(String alias) {
        // The identifier is a validated Long obtained from the local teacher login.
        return alias + ".ownerTeacherId " + (teacherId == null ? "is null" : "= " + teacherId);
    }
    void require(TeacherOwned entity) {
        if (entity == null || !Objects.equals(entity.getOwnerTeacherId(), teacherId))
            throw new IllegalArgumentException("Không tìm thấy dữ liệu của giáo viên đang đăng nhập.");
    }
    void assign(TeacherOwned entity) { entity.setOwnerTeacherId(teacherId); }
}
