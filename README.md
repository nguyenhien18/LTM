# LAN Exam System - BTL Lập trình mạng

Đề tài: **Thiết kế và xây dựng hệ thống thi/làm bài trong mạng LAN sử dụng JavaFX và TCP Socket theo mô hình Client-Server.**

## 1. Công nghệ

- Java 21
- JavaFX 21
- TCP Socket (`ServerSocket`, `Socket`)
- JSON + Jackson
- `ExecutorService` để xử lý nhiều Client
- `ConcurrentHashMap` để mapping sinh viên đang online
- JPA + Hibernate ORM
- MySQL 8
- Maven

## 2. Chức năng MVP đã có

### Server giảng viên
- Khởi động TCP Server ở port `5000`.
- Kết nối MySQL bằng JPA/Hibernate.
- Tự tạo dữ liệu demo ở lần chạy đầu.
- Theo dõi sinh viên đang online: MSV, họ tên, IP, trạng thái.
- Mapping `studentCode -> ClientSession -> Socket/IP/status`.
- Mở bài thi cho Client đang đăng nhập và Client đăng nhập sau; mỗi sinh viên tự bấm tham gia.
- Cập nhật trạng thái `ONLINE`, `DOING_EXAM`, `SUBMITTED`.
- Nhận bài và tự động chấm điểm.
- Tạo một lượt làm bài cho mỗi sinh viên/đợt mở đề, lưu hạn nộp ở MySQL và chặn nộp trùng trong cùng đợt.
- Tự chấm bản nháp đã lưu khi hết hạn, kể cả khi Client ngắt kết nối.
- Giao bài tập có nội dung, hạn nộp tùy chọn và tệp đề bài; xem nội dung và tải tệp bài nộp của học viên.

### Client sinh viên
- Nhập IP máy Server.
- Đăng nhập bằng MSV + ngày sinh.
- Xem trang chính với bài thi đang mở và lịch sử điểm cá nhân.
- Bấm **Tham gia bài thi** để nhận câu hỏi qua TCP Socket và bắt đầu tính giờ.
- Làm câu trắc nghiệm và điền đáp án.
- Đồng hồ đếm ngược.
- Hết giờ tự nộp.
- Tự lưu câu trả lời khi thay đổi; đăng nhập lại để tiếp tục bài còn thời gian.
- Nộp bài và xem kết quả.
- Từ màn hình điểm có thể về trang chính hoặc đăng xuất về màn hình đăng nhập. Đăng nhập lại vẫn xem được lịch sử điểm.
- Gửi heartbeat `PING/PONG` định kỳ.
- Tab **Bài tập**: xem yêu cầu, tải đề bài, nộp nội dung hoặc tệp và xem lại bài đã nộp; được nộp lại trước hạn.

## 3. Tài khoản demo

| MSV | Ngày sinh | Họ tên |
|---|---|---|
| B21DCCN001 | 12/05/2003 | Nguyễn Văn An |
| B21DCCN002 | 20/08/2003 | Trần Minh Bình |
| B21DCCN003 | 03/11/2003 | Lê Hoàng Cường |

Trong Client chọn đúng ngày sinh bằng `DatePicker`.

## 4. Chuẩn bị môi trường

Cần cài:

1. **JDK 21**
2. **Maven 3.9+**
3. Một trong hai cách chạy MySQL:
   - Docker Desktop, hoặc
   - MySQL cài trực tiếp trên Windows.

Kiểm tra:

```bash
java -version
mvn -version
```

## 5. Chạy MySQL trên máy Server

MySQL chỉ chạy trên máy Server. Các Client kết nối TCP tới Server qua LAN và không kết nối MySQL trực tiếp. Không cần Internet trong lúc thi; Maven chỉ cần tải thư viện khi chuẩn bị môi trường lần đầu.

Nếu dùng Docker, trong thư mục project chạy:

```bash
docker compose up -d
```

Hoặc chạy `start-mysql.bat`. Database mặc định là `localhost:3306/lan_exam`, tài khoản `root/root`; Hibernate tự tạo bảng.

Nếu dùng MySQL cài sẵn với tài khoản khác, đặt `DB_URL`, `DB_USER` và `DB_PASSWORD` trên máy Server trước khi chạy `mvn javafx:run -Pserver`.

## 6. Chạy Server

```bash
mvn clean javafx:run -Pserver
```

Hoặc double click:

```text
run-server.bat
```

Trên cửa sổ Server:

1. Chọn **Khởi động Server**.
2. Chờ dòng `Server đang chạy tại cổng 5000`.
3. Chọn **Tạo đề thi**, nhập tên, thời gian, từng câu hỏi và đáp án; bấm **Thêm câu hỏi** cho mỗi câu, rồi **Lưu đề thi**. Đề được lưu trong MySQL và chưa mở cho sinh viên.
4. Chọn **Chọn bài thi để mở**, chọn một đề trong danh sách rồi bấm **Mở bài thi đã chọn**. Sinh viên sẽ thấy bài trên trang chính và tự bấm **Tham gia bài thi**; lúc đó đồng hồ mới bắt đầu. Sau khi khởi động lại Server, giảng viên cần mở bài lại.
5. Sau khi sinh viên nộp bài, chọn **Xem điểm**; bấm **Làm mới** để cập nhật bảng điểm. Bảng điểm giữ cả bài của đề cũ và sinh viên đã offline.

Có thể tạo và lưu đề mới trong lúc sinh viên đang làm bài. Nếu còn sinh viên đang làm đề hiện tại, Server sẽ từ chối **mở một đề khác**; mở lại cùng đề chỉ thông báo lại đợt hiện tại và không đặt lại thời gian. Sau khi mọi lượt làm bài của đợt hiện tại hoàn tất, chọn mở lại cùng đề sẽ tạo **đợt mới**: sinh viên được làm lại, còn điểm đợt cũ vẫn nằm trong lịch sử. Bảng điểm hiển thị mã lượt để phân biệt các đợt. Sau khi sửa mã nguồn, cần đóng các cửa sổ đang chạy và mở lại Server/Client để thấy giao diện mới.

## 7. Chạy Client

```bash
mvn clean javafx:run -Pclient
```

Hoặc:

```text
run-client.bat
```

### Test trên cùng một máy

IP Server:

```text
127.0.0.1
```

Có thể mở nhiều cửa sổ Client và dùng nhiều MSV demo khác nhau.

### Test bằng 2-3 laptop trong LAN

Ví dụ máy Server có IP:

```text
192.168.1.5
```

Client nhập:

```text
Server IP: 192.168.1.5
Port: 5000
```

Các máy phải cùng Wi-Fi/LAN. Windows Firewall có thể yêu cầu cho phép Java nhận kết nối mạng Private.

## 8. Luồng demo để bảo vệ

### Giao và nộp bài tập

Sau khi cập nhật mã nguồn, đóng và chạy lại cả Server lẫn Client để hai phía sử dụng cùng giao thức.

1. Giảng viên khởi động Server, bấm **Bài tập / Bài nộp** → **Giao bài tập**.
2. Nhập tên, nội dung yêu cầu hoặc đính kèm đề bài. Có thể chọn hạn nộp theo giờ Việt Nam; bỏ chọn để không giới hạn thời gian.
3. Bấm **Giao bài tập**. Học viên đang online tự cập nhật danh sách; học viên đăng nhập sau cũng thấy bài.
4. Học viên mở tab **Bài tập**, chọn bài, nhập nội dung và/hoặc bấm **Chọn tệp bài làm**, rồi **Nộp bài tập**.
5. Giảng viên bấm **Làm mới**, chọn bài tập và một học viên để đọc bài; bấm **Tải tệp bài nộp** để lưu về máy.

Mỗi tệp tối đa **5 MB**, mỗi nội dung tối đa **20.000 ký tự**. Có thể nộp tệp ZIP nếu bài gồm nhiều file.
Học viên có thể **Nộp lại bài tập** trước hạn: bản mới thay thế bản cũ, không tạo thêm dòng bài nộp.
Khi sửa nội dung mà không chọn tệp mới, tệp đã nộp được giữ lại; bấm **Bỏ tệp** nếu muốn xóa tệp khỏi bản nộp tiếp theo.
Server kiểm tra hạn nộp bằng đồng hồ của máy Server và từ chối cả nộp lần đầu lẫn nộp lại khi đã hết hạn.

Nội dung và tệp được lưu trong hai bảng MySQL mới: `assignments`, `assignment_submissions`.
Hibernate tạo thêm bảng khi khởi động. Đăng xuất hoặc khởi động lại Server không làm mất bài tập đã giao hay bài đã nộp.
Học viên chỉ xem/tải được bài nộp của chính mình; giảng viên xem được các bài nộp trong cửa sổ Server.

### Demo bài thi

```text
1. Mở Server -> Khởi động Server
2. Mở Client A -> đăng nhập B21DCCN001
3. Mở Client B -> đăng nhập B21DCCN002
4. Server thấy 2 IP và trạng thái ONLINE
5. Server bấm Chọn bài thi để mở, chọn một đề đã lưu
6. Hai Client thấy bài đang mở; từng Client bấm Tham gia bài thi -> trạng thái DOING_EXAM
7. Client A làm bài và Nộp
8. Server đổi Client A thành SUBMITTED
9. Client A nhận điểm, về trang chính và xem lịch sử điểm
10. Client B chọn đáp án, đóng ứng dụng rồi đăng nhập lại, bấm Tiếp tục bài thi -> đáp án và thời gian còn lại được khôi phục
11. Đóng Client B -> Server xóa Client B khỏi danh sách online
```

## 9. Cấu trúc source

```text
src/main/java/com/ptit/lanexam
├── common
│   ├── dto
│   ├── JsonUtil.java
│   ├── MessageType.java
│   └── NetworkMessage.java
├── server
│   ├── entity
│   ├── network
│   ├── persistence
│   ├── service
│   └── ServerApp.java
└── client
    ├── network
    └── ClientApp.java
```

## 10. Kiểm thử và dữ liệu cũ

Chạy kiểm thử tích hợp bằng `mvn test`. Các ca kiểm thử dùng H2 trong bộ nhớ riêng, không thay thế hoặc sửa database MySQL của ứng dụng. Xem thêm [kịch bản kiểm thử](docs/TEST_2_HOURS.md) và [tóm tắt báo cáo](docs/REPORT.md).

Kiểm thử bài tập bao gồm lưu/tải lại tệp, nộp lại, giới hạn dung lượng, phân quyền, chặn quá hạn và truyền tệp qua TCP thật trên loopback.
Để kiểm tra thêm giao diện JavaFX trên máy có desktop, chạy `mvn test -Dlanexam.uiTests=true`.
Lệnh này kiểm tra việc giữ nội dung đang soạn khi danh sách cập nhật và tạo ảnh xem trước ở `target/assignment-preview/`.

Lược đồ có bảng `exam_attempts` và ràng buộc duy nhất `(student_id, exam_id)` ở cả `exam_attempts` và `submissions`. Mỗi lần mở lại đề sau khi đợt trước hoàn tất sẽ tạo một bản ghi `exams` mới với `template_exam_id` trỏ về đề gốc; vì thế bài làm cũ được giữ nguyên mà sinh viên vẫn có thể làm lại trong đợt mới. Hibernate `update` tự thêm cột nullable này trên MySQL khi khởi động; vẫn nên sao lưu database trước khi nâng cấp dữ liệu thật. Nếu MySQL cũ đã có nhiều bài nộp cho cùng một sinh viên/đợt, cần xử lý bản ghi trùng trước khi cập nhật.

## 11. Giới hạn và hướng phát triển

- Sửa/xóa đề đã tạo và quản lý ngân hàng câu hỏi trên Server.
- Random câu hỏi và đảo đáp án.
- Server gửi thông báo cho sinh viên.
- Xuất kết quả Excel.
- Phân quyền tài khoản giảng viên.
- Tài khoản hiện dùng ngày sinh; giao thức TCP chưa mã hóa. Chỉ dùng trong LAN tin cậy để demo, chưa phù hợp với kỳ thi chính thức.
- Khi vừa nhập đáp án xong mà tắt máy trước khi bản nháp gửi xong, thay đổi cuối cùng có thể chưa được lưu.

Các mục trên cần giải quyết trước khi triển khai thực tế.
