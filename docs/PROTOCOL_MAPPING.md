# Mapping giao thức Client - Server

Hệ thống dùng **JSON Lines over TCP**: mỗi message là một JSON nằm trên một dòng và kết thúc bằng ký tự xuống dòng.

## Mapping chức năng

| Chức năng | Client -> Server | Server xử lý | Server -> Client |
|---|---|---|---|
| Đăng nhập | `LOGIN` | Kiểm tra MSV + ngày sinh, tra lịch sử, bài đang mở và bài tập | `LOGIN_RESULT`, `HISTORY_RESULT`, `EXAM_AVAILABLE`, `ASSIGNMENTS_RESULT` |
| Giữ kết nối | `PING` | Xác nhận socket còn sống | `PONG` |
| Mở bài thi | - | Server công bố thông tin đề đang mở, chưa tạo lượt thi | `EXAM_AVAILABLE` |
| Tham gia/tiếp tục | `JOIN_EXAM` | Kiểm tra quyền tham gia, tạo hoặc dùng lại lượt thi | `EXAM_STARTED` |
| Xem lịch sử | `HISTORY_REQUEST` | Lấy các bài đã nộp của sinh viên đang đăng nhập | `HISTORY_RESULT` |
| Tự lưu bài | `SAVE_DRAFT` | Lưu đáp án trong lượt thi còn hạn | - |
| Nộp bài | `SUBMIT_EXAM` | Chấm điểm + lưu DB | `SUBMIT_RESULT` |
| Xem bài tập | `ASSIGNMENTS_REQUEST` | Lấy bài tập và bản nộp của đúng học viên đang đăng nhập | `ASSIGNMENTS_RESULT` |
| Giao bài tập | - | Giảng viên tạo bài trên Server, báo Client tải lại danh sách | `ASSIGNMENTS_CHANGED` |
| Nộp/nộp lại bài tập | `ASSIGNMENT_SUBMIT` | Kiểm tra hạn, nội dung, tệp; lưu một bản nộp cho mỗi học viên/bài tập | `ASSIGNMENT_SUBMITTED`, `ASSIGNMENTS_RESULT` |
| Tải tệp bài tập | `ASSIGNMENT_FILE_REQUEST` | Trả đề bài hoặc tệp bài nộp của chính học viên | `ASSIGNMENT_FILE_RESULT` |
| Lỗi bài tập | - | Báo lỗi mà không ngắt kết nối hay thay đổi bài thi | `ASSIGNMENT_ERROR` |
| Lỗi | - | Phát hiện lỗi nghiệp vụ/protocol | `ERROR` |

`EXAM_AVAILABLE` chỉ chứa tên đề, mã lượt thi, thời lượng hoặc trạng thái tiếp tục; không chứa câu hỏi. `JOIN_EXAM` gửi mã lượt thi. `EXAM_STARTED` chứa câu hỏi, `durationSeconds` còn lại, `deadlineEpochMillis` và `savedAnswers`. Khi đăng nhập lại, Client thấy nút Tiếp tục bài thi nếu lượt thi còn hạn. Mỗi lần mở lại đề sau khi đợt cũ hoàn tất có mã lượt mới, nên sinh viên làm lại được. `HISTORY_RESULT` chứa các bài đã nộp của đúng sinh viên đang đăng nhập, kể cả các đợt trước. `SUBMIT_EXAM` sau hạn chỉ trả kết quả đã chấm từ bản nháp trên server.

Ví dụ tự lưu và nộp bài (mã câu hỏi là khóa của `answers`):

```json
{"type":"SAVE_DRAFT","payload":{"examId":1,"answers":{"1":"B","2":"THREAD"}}}
{"type":"SUBMIT_EXAM","payload":{"examId":1,"answers":{"1":"B","2":"THREAD"}}}
```

Thông điệp được gửi theo đúng thứ tự trên cùng một kết nối TCP. Server chỉ nhận đáp án của các câu thuộc đề và giới hạn mỗi đáp án 1000 ký tự.

## Payload và giới hạn truyền tệp

### Payload bài tập

`ASSIGNMENT_SUBMIT` có `assignmentId`, `content`, `fileName`, `fileData` và `keepExistingFile`.
`fileData` là Base64 của tệp nhị phân, tối đa 5 MB trước khi mã hóa. Nếu có tệp mới thì thay tệp cũ;
nếu không có tệp mới và `keepExistingFile=true` thì giữ tệp cũ; nếu `false` thì xóa tệp khỏi bản nộp.
Yêu cầu không chứa MSV: Server lấy danh tính từ phiên đăng nhập.

`ASSIGNMENT_FILE_REQUEST` có `assignmentId` và `submission`: `false` để tải đề bài, `true` để tải bài của chính mình.
`ASSIGNMENT_FILE_RESULT` trả lại hai trường đó kèm `fileName`, `data` (Base64).
Danh sách chỉ chứa metadata của tệp; dữ liệu nhị phân được tải riêng khi có yêu cầu.
Mỗi dòng JSON được giới hạn 8 MB ký tự ở cả hai phía để chứa tệp sau mã hóa cùng nội dung.
Hạn nộp và thời gian nộp dùng `Instant`; giao diện hiển thị theo múi giờ Việt Nam.

### Payload đăng nhập

```json
{"type":"LOGIN","payload":{"studentCode":"B21DCCN001","birthDate":"2003-05-12"}}
```

Server trả:

```json
{"type":"LOGIN_RESULT","payload":{"success":true,"message":"Đăng nhập thành công","studentName":"Nguyễn Văn An"}}
```

## Mapping sinh viên đang online

Trong `SessionRegistry`:

```java
ConcurrentHashMap<String, ClientSession>
```

Ý nghĩa:

```text
B21DCCN001
    -> ClientSession
        -> ClientHandler
        -> Socket
        -> IP
        -> Status
```

Nhờ mapping này Server biết:

- socket nào thuộc sinh viên nào;
- IP của sinh viên;
- sinh viên đang ONLINE / DOING_EXAM / SUBMITTED;
- cần gửi đề hoặc dữ liệu về kết nối nào.
