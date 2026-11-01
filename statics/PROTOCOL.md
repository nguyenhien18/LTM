# TCP / JSON Lines

## Kết nối và framing

Client kết nối IP/cổng của Server (mặc định 5000), duy trì một TCP connection. Mỗi frame là một dòng JSON UTF-8, kết thúc bằng newline: `{"type":"...","payload":...}\n`. TCP không giữ ranh giới message; `TransferLimits.readLine` đọc đến newline và giới hạn 8 × 1024 × 1024 **ký tự**, không phải giới hạn byte chính xác.

Server xác định sinh viên qua phiên đã đăng nhập; các request làm bài không tự khai MSV, điểm hoặc thời điểm nhận. Client không kết nối MySQL. Đăng nhập mới cùng MSV thay phiên cũ. Heartbeat Client mỗi 5 giây; timeout đọc được cấu hình 20 giây. Lỗi writer/timeout hoặc đóng Client kết thúc kết nối; đăng nhập lại để tiếp tục thủ công, không có tự reconnect.

## Message hiện hành

| Message | Chiều | Ý nghĩa |
|---|---|---|
| LOGIN | Client → Server | studentCode, birthDate dạng ISO yyyy-MM-dd; focusMonitoring/examControls là cờ khả năng của Client |
| LOGIN_RESULT | Server → Client | Thành công/thất bại và thông tin hiển thị |
| EXAM_AVAILABLE | Server → Client | Offer đúng quyền hoặc exam=null; không chứa đáp án đúng |
| JOIN_EXAM | Client → Server | examId của lượt muốn tham gia/tiếp tục |
| EXAM_STARTED | Server → Client | Đề theo mã đã gán, deadline, thời gian Server, savedAnswers của chính lượt |
| ANSWER_UPDATE | Client → Server | examId và snapshot answers theo questionId |
| SUBMIT_EXAM | Client → Server | examId và answers để nộp bài |
| SUBMIT_RESULT | Server → Client | Điểm, số câu đúng, tổng số câu và thông báo |
| HISTORY_REQUEST / HISTORY_RESULT | Client → Server / ngược lại | Lịch sử cá nhân trong phạm vi giảng viên |
| PING / PONG | Client → Server / ngược lại | Giữ và kiểm tra kết nối |
| EXAM_FOCUS_CHANGED | Client → Server | examId, episodeId (UUID), focused; không có timestamp do Client khai |
| EXAM_WARNING_STATUS | Server → Client | Số lần rời màn hình và trạng thái cần giảng viên kiểm tra |
| VIOLATION_WARNING | Server → Client | Nhắc nhở của giảng viên |
| EXAM_DISQUALIFIED | Server → Client | examId và reason; kết quả gửi sau khi hoàn tất chấm |
| ACCOUNT_LOCKED | Server → Client | Thông báo khóa theo giảng viên, đóng phiên |
| ERROR | Server → Client | Lỗi dữ liệu/nghiệp vụ; xử lý tùy loại lỗi |
| SAVE_DRAFT | Client cũ → Server | Chỉ giữ để trả thông báo cập nhật Client; không dùng thay ANSWER_UPDATE |

## Ví dụ frame

Các ID sau chỉ minh họa, không thể dùng để bỏ qua kiểm tra danh sách/quyền.

```json
{"type":"LOGIN","payload":{"studentCode":"B23DCCN201","birthDate":"2004-01-01","focusMonitoring":true,"examControls":true}}
{"type":"JOIN_EXAM","payload":{"examId":12}}
{"type":"ANSWER_UPDATE","payload":{"examId":12,"answers":{"101":"A","102":"C"}}}
{"type":"SUBMIT_EXAM","payload":{"examId":12,"answers":{"101":"A","102":"C"}}}
{"type":"EXAM_FOCUS_CHANGED","payload":{"examId":12,"episodeId":"123e4567-e89b-12d3-a456-426614174000","focused":false}}
{"type":"PING","payload":null}
```

## Trình tự và tính đúng đắn

1. Giảng viên đăng nhập, Server mở socket; sinh viên có trong lớp có thể vào phòng chờ.
2. Khi mở ca, Server chốt roster, hạn chung và mã đề. Client nhận offer nếu thuộc danh sách.
3. JOIN_EXAM được kiểm tra lại; EXAM_STARTED cung cấp đề đúng mã và lựa chọn đã lưu nếu tiếp tục.
4. Mỗi thay đổi tạo ANSWER_UPDATE; chưa có ACK riêng cho từng lần lưu. Chỉ dữ liệu Server đã lưu mới được bảo đảm khôi phục.
5. RequestReceipt lưu thời điểm Server nhận đủ frame trước khi xếp hàng. FIFO giữ thứ tự request của mỗi Client; tối đa 16 request được xử lý song song.
6. Nộp lặp không tạo thêm kết quả. Sau deadline/dừng ca, hệ thống chờ các đáp án nhận trước hạn xử lý rồi chấm. `ExamService` đổi lựa chọn về đáp án gốc và tính điểm bằng Java; JPA/MySQL lưu bài nộp và câu trả lời trong giao dịch. Yêu cầu nhận sau hạn không thay điểm.
7. Rời màn hình liên tục từ 5 giây: xác nhận được gửi lại cùng episodeId; Server ghi một sự kiện, cập nhật khi quay lại, báo giảng viên ngay từ lần đầu. Gián đoạn mạng được ghi riêng.
8. Sau restart, ca còn hạn được khôi phục từ CSDL; Client phải đăng nhập lại. Deadline và mã đề không được cấp lại tùy ý.

`attemptGate` dùng read lock khi vào bài (`JOIN_EXAM`); mở/dừng ca, đình chỉ và dừng Server dùng write lock. `ANSWER_UPDATE` và `SUBMIT_EXAM` không lấy read lock này: thứ tự và việc chốt bài dựa vào FIFO, RequestReceipt, giao dịch, khóa bản ghi và ràng buộc duy nhất.

Một mã dùng đề **Gốc**, có thể không có `variants_json` hoặc `variant_code`. Từ 2–8 mã mới có bố cục và phân công mã số 101–108. Khi khởi động, Server chỉ phục hồi ca còn hạn, không tự mở lại hay gia hạn ca đã kết thúc. Thi cả lớp không chia ca có thể tạo lượt mới từ cùng đề mẫu; ca đã chia từng mở thi không được dùng lại.

Xem [sơ đồ TCP](protocol.png), [nguồn SVG](protocol.svg), [nguồn PlantUML](protocol.puml) và [thiết kế dữ liệu](DATA_MODEL.md).

## Lỗi và giới hạn

Request sai, ca không hợp lệ, không có quyền hoặc dữ liệu nghiệp vụ sai nhận lỗi phù hợp; frame quá lớn, kết nối hỏng và timeout có thể bị đóng. Một Client chậm có outbox/timeout riêng. Cơ chế này không phải exactly-once delivery cho mọi message, chưa có TLS và không bảo đảm Client bị chỉnh sửa sẽ báo focus trung thực.
