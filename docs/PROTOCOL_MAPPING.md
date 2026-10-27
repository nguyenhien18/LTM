# Mapping giao thức Client - Server

Hệ thống dùng **JSON Lines over TCP**: mỗi message là một JSON nằm trên một dòng và kết thúc bằng ký tự xuống dòng.

Giáo viên đăng nhập cục bộ trên máy Server. Các service xử lý TCP của phiên đó được gắn mã giáo viên;
Client sinh viên giữ nguyên giao thức đăng nhập MSV/ngày sinh và chỉ nhận đề, bài tập, lịch sử trong phạm vi giáo viên đang phụ trách.
Đổi giáo viên khi không còn lượt đang làm sẽ đóng toàn bộ kết nối cũ; sinh viên kết nối lại sau khi giáo viên mới khởi động Server.

## Mapping chức năng

| Chức năng | Client -> Server | Server xử lý | Server -> Client |
|---|---|---|---|
| Đăng nhập | `LOGIN` | Kiểm tra MSV + ngày sinh, tra lịch sử, bài đang mở và bài tập | `LOGIN_RESULT`, `HISTORY_RESULT`, `EXAM_AVAILABLE`, `ASSIGNMENTS_RESULT` |
| Giữ kết nối | `PING` | Xác nhận socket còn sống | `PONG` |
| Mở bài thi | - | Server công bố thông tin đề đang mở, chưa tạo lượt thi | `EXAM_AVAILABLE` |
| Tham gia/làm lại | `JOIN_EXAM` | Kiểm tra hạn chung, tạo hoặc dùng lại lượt thi và xóa lựa chọn cũ | `EXAM_STARTED` |
| Xem lịch sử | `HISTORY_REQUEST` | Lấy các bài đã nộp của sinh viên đang đăng nhập | `HISTORY_RESULT` |
| Cập nhật lựa chọn | `ANSWER_UPDATE` | Giữ lựa chọn của phiên hiện tại trong RAM để chấm hết giờ; không lưu nháp MySQL | - |
| Nộp bài | `SUBMIT_EXAM` | Chấm điểm + lưu DB | `SUBMIT_RESULT` |
| Xem bài tập | `ASSIGNMENTS_REQUEST` | Lấy bài tập và bản nộp của đúng học viên đang đăng nhập | `ASSIGNMENTS_RESULT` |
| Giao bài tập | - | Giảng viên tạo bài trên Server, báo Client tải lại danh sách | `ASSIGNMENTS_CHANGED` |
| Nộp/nộp lại bài tập | `ASSIGNMENT_SUBMIT` | Xét hạn và lưu giờ nộp theo thời điểm Server nhận đủ thông điệp, trước hàng đợi; kiểm tra nội dung, tệp; lưu một bản nộp cho mỗi học viên/bài tập | `ASSIGNMENT_SUBMITTED`, `ASSIGNMENTS_RESULT` |
| Tải tệp bài tập | `ASSIGNMENT_FILE_REQUEST` | Trả đề bài hoặc tệp bài nộp của chính học viên | `ASSIGNMENT_FILE_RESULT` |
| Lỗi bài tập | - | Báo lỗi mà không ngắt kết nối hay thay đổi bài thi | `ASSIGNMENT_ERROR` |
| Cảnh báo vi phạm | - | Giảng viên nhập nội dung trên Server; gửi riêng đến phiên của sinh viên được chọn | `VIOLATION_WARNING` |
| Khóa tài khoản | - | Lưu cờ khóa và lý do trong database, thông báo Client rồi ngắt phiên; từ chối đăng nhập lại | `ACCOUNT_LOCKED` |
| Lỗi | - | Phát hiện lỗi nghiệp vụ/protocol | `ERROR` |

`EXAM_AVAILABLE` chứa thông tin đề, thời gian còn lại, `deadlineEpochMillis` và `serverTimeEpochMillis` tính từ lúc giảng viên mở đề. `JOIN_EXAM` gửi mã đợt; `EXAM_STARTED` trả câu hỏi, `durationSeconds` còn lại và `deadlineEpochMillis` chung kèm `serverTimeEpochMillis`. Không có `savedAnswers`. Vào lại trước hạn nhận bài trống, không được cộng giờ. Sau khi đã nộp hoặc hết hạn không được làm lại trong cùng đợt.

`ANSWER_UPDATE` chứa toàn bộ lựa chọn hiện tại, gửi ngay sau thay đổi. Server lấy danh tính từ socket và chỉ giữ dữ liệu trong RAM để chấm hết giờ. Khi học viên tham gia lại, dữ liệu RAM của lượt cũ bị xóa. `SUBMIT_EXAM` trước hạn chấm nội dung gửi lên; sau hạn trả kết quả hoặc chấm lựa chọn Server đã nhận trước hạn. Tác vụ Server tự chấm cả học viên offline.

```json
{"type":"ANSWER_UPDATE","payload":{"examId":1,"answers":{"1":"B","2":"A"}}}
{"type":"SUBMIT_EXAM","payload":{"examId":1,"answers":{"1":"B","2":"A"}}}
```

`SAVE_DRAFT` là thông điệp của Client cũ, bị từ chối kèm yêu cầu cập nhật EXE. Dùng Client mới cùng Server mới.

Thông điệp được gửi theo đúng thứ tự trên cùng một kết nối TCP. Server chỉ nhận đáp án của các câu thuộc đề và giới hạn mỗi đáp án 1000 ký tự.

`VIOLATION_WARNING` và `ACCOUNT_LOCKED` dùng payload là chuỗi thông báo. Cảnh báo tối đa 1.000 ký tự; lý do khóa tối đa 500 ký tự.
Đây là thông báo từ Server; Client không có quyền yêu cầu cảnh báo hoặc khóa tài khoản qua giao thức.
Client hiển thị cảnh báo trong khi đồng hồ thi vẫn chạy. Khi nhận `ACCOUNT_LOCKED`, Client dừng màn hình thi,
trở về đăng nhập và hiện lý do; không hiện thêm popup mất kết nối chung cho cùng sự kiện.
Mở khóa được thực hiện trực tiếp trên giao diện giảng viên và cập nhật database.

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
- mã lượt và tên đề gắn với trạng thái đang làm/đã nộp;
- cần gửi đề hoặc dữ liệu về kết nối nào.

Mỗi kết nối đăng nhập mới bắt đầu ở `ONLINE`, dù sinh viên có bài nộp cũ. Bấm tham gia/làm lại chuyển sang
`DOING_EXAM`; nộp hoặc tự chấm hết giờ chuyển sang `SUBMITTED` cho đúng mã lượt đang làm.
Khi có đợt mới để tham gia, trạng thái đã nộp của đợt trước trở về `ONLINE`. Cập nhật từ handler cũ không thay đổi phiên đăng nhập mới.

Client tự đếm thời gian trên trang chờ, khóa tham gia khi hết hạn và nhận EXAM_AVAILABLE với exam=null khi Server thu hồi đề. Thời điểm nhận ANSWER_UPDATE/SUBMIT_EXAM do Server ghi sau khi đọc trọn thông điệp; không lấy giờ từ Client. Chấm hết giờ chờ xử lý xong các thông điệp nhận trước hạn rồi mới chốt kết quả.
