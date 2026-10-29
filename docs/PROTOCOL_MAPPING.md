# Giao thức TCP JSON Lines

Mỗi thông điệp là một dòng JSON `{type,payload}` UTF-8. Client kết nối IP Server/cổng 5000, không gửi SQL hoặc kết nối MySQL.

| Chiều | Type | Ý nghĩa |
|---|---|---|
| Client → Server | LOGIN | studentCode và birthDate yyyy-MM-dd |
| Server → Client | LOGIN_RESULT | Thành công/thông báo/họ tên |
| Server → Client | EXAM_AVAILABLE | Offer ca được phép tham gia hoặc exam=null |
| Client → Server | JOIN_EXAM | examId cần tham gia/tiếp tục |
| Server → Client | EXAM_STARTED | Câu hỏi, hạn chung, thời gian Server và savedAnswers của chính lượt làm |
| Client → Server | ANSWER_UPDATE | examId và ảnh chụp answers hiện tại |
| Client → Server | SUBMIT_EXAM | examId và answers để nộp sớm |
| Server → Client | SUBMIT_RESULT | Điểm, số đúng, tổng số câu |
| Client → Server | HISTORY_REQUEST | Yêu cầu lịch sử điểm và cập nhật offer |
| Server → Client | HISTORY_RESULT | Các kết quả của sinh viên trong phạm vi Server/giảng viên |
| Client ↔ Server | PING / PONG | Heartbeat |
| Server → Client | VIOLATION_WARNING | Cảnh báo từ giảng viên |
| Server → Client | ACCOUNT_LOCKED | Thông báo khóa và ngắt phiên |
| Server → Client | ERROR | Lỗi xác thực hoặc nghiệp vụ |

Ví dụ cập nhật đáp án:

```json
{"type":"ANSWER_UPDATE","payload":{"examId":12,"answers":{"101":"A","102":"C"}}}
```

`EXAM_AVAILABLE` chứa examId/title/classId/className/subjectName, thời gian còn lại, resumable và hai mốc thời gian Server. Offer không chứa câu hỏi hoặc đáp án đúng.

`EXAM_STARTED` có `savedAnswers` là ánh xạ questionId sang lựa chọn đã nhận của đúng sinh viên. Người mới nhận map rỗng. Vào lại nhận cùng deadline và lựa chọn đã lưu, không nhận thêm thời gian. Không có trường đáp án đúng trong QuestionDto.

Server lấy danh tính từ phiên đăng nhập và tra class_students khi cấp đề. Nếu lớp đã chia ca, Server kiểm tra thêm exam_shift_students ở cả lúc thông báo EXAM_AVAILABLE và xử lý JOIN_EXAM. Sinh viên cùng lớp nhưng khác ca nhận exam=null và bị từ chối nếu tự gửi mã lượt thi. Số ca được kèm trong title của offer, đề và lịch sử; không thêm loại thông điệp hay yêu cầu Client tự chọn ca. Biết examId hoặc gửi classId không tạo quyền tham gia.

RequestReceipt đóng dấu thời gian Server khi nhận đủ frame. ANSWER_UPDATE/SUBMIT_EXAM nhận trước hạn vẫn được xử lý khi chờ slot/khóa DB; tác vụ hết giờ chờ các receipt đó. Thông điệp nhận sau hạn không sửa kết quả. Client không được tự khai thời điểm nhận hoặc điểm số.

Một studentCode có một phiên hiện hành; cập nhật từ handler cũ không thay đổi trạng thái của handler mới. Các kết quả cuối vẫn chỉ có một bản ghi cho mỗi sinh viên/ca.

Luồng CLASSES_* và ASSIGNMENT_* đã được bỏ khỏi giao thức hiện hành. Client không tự chọn lớp và không truyền tệp thực hành. SAVE_DRAFT cũ chỉ được giữ để trả thông báo yêu cầu cập nhật Client; ANSWER_UPDATE là cơ chế lưu lựa chọn hiện tại.
