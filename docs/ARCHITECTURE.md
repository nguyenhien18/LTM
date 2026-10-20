# Kiến trúc

```text
                     MYSQL
                       ^
                       |
                 JPA/Hibernate
                       |
              +------------------+
              |    JAVA SERVER   |
              |------------------|
              | JavaFX Admin UI  |
              | ServerSocket     |
              | ExecutorService  |
              | SessionRegistry  |
              | AuthService      |
              | ExamService      |
              +---------+--------+
                        |
                  TCP + JSON
                        |
            +-----------+-----------+
            |                       |
      +-----+------+          +-----+------+
      | JavaFX     |          | JavaFX     |
      | Client A   |          | Client B   |
      +------------+          +------------+
```

## Nguyên tắc

- Client **không truy cập MySQL trực tiếp**; MySQL chỉ nằm trên máy Server.
- Server là nơi xác thực, chấm điểm, lưu dữ liệu và quản lý trạng thái.
- Client chỉ gửi hành động/đáp án qua TCP.
- Server có quyền quyết định trạng thái cuối cùng.
