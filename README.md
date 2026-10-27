# LAN Exam System - BTL Lập trình mạng

Đề tài: **Thiết kế và xây dựng hệ thống thi/làm bài trong mạng LAN sử dụng JavaFX và TCP Socket theo mô hình Client-Server.**

## 1. Công nghệ

- Java 21
- JavaFX 21
- TCP Socket (`ServerSocket`, `Socket`)
- JSON + Jackson
- Virtual thread của Java 21 cho từng kết nối Client, không xếp phiên mới chờ sau các phiên đang thi
- `ConcurrentHashMap` để mapping sinh viên đang online
- JPA + Hibernate ORM
- MySQL 8
- Maven

## 2. Chức năng MVP đã có

### Server giảng viên
- Tạo và đăng nhập nhiều tài khoản giáo viên lần lượt trên cùng máy Server; hiện tên tài khoản đang dùng và hỗ trợ đổi mật khẩu.
- Mỗi giáo viên có môn, ngân hàng câu hỏi, đề, bài tập và kết quả riêng; chặn dừng/đóng Server và đổi giáo viên trong suốt ca thi đến khi hết giờ, chấm và lưu điểm xong.
- Khởi động TCP Server ở port `5000`.
- Kết nối MySQL bằng JPA/Hibernate.
- Tự tạo dữ liệu demo ở lần chạy đầu.
- Theo dõi sinh viên đang online: MSV, họ tên, IP, trạng thái.
- Mapping `studentCode -> ClientSession -> Socket/IP/status`.
- Mở bài thi cho Client đang đăng nhập và Client đăng nhập sau; mỗi sinh viên tự bấm tham gia.
- Cập nhật trạng thái `ONLINE`, `DOING_EXAM`, `SUBMITTED`.
- Nhận bài và tự động chấm điểm.
- Tạo một lượt làm bài cho mỗi sinh viên/đợt mở đề, lưu hạn nộp ở MySQL và chặn nộp trùng trong cùng đợt.
- Thời gian tính chung từ lúc giảng viên mở đề; hết giờ tự chấm các lựa chọn Server đã nhận, kể cả khi Client ngắt kết nối. Đáp án đang làm chỉ giữ trong RAM.
- Ngân hàng câu trắc nghiệm theo môn học, độ khó 1–6; tạo đề bằng các câu đã lưu.
- Theo dõi trạng thái từng phiên và đề đang làm/đã nộp; gửi cảnh báo vi phạm đến đúng Client, khóa và mở khóa tài khoản.
- Giao bài tập có nội dung, hạn nộp tùy chọn và tệp đề bài; xem nội dung và tải tệp bài nộp của học viên.

### Client sinh viên
- Nhập IP máy Server.
- Đăng nhập bằng MSV + ngày sinh.
- Xem trang chính với bài thi đang mở và lịch sử điểm cá nhân.
- Bấm **Tham gia bài thi** để nhận câu hỏi và thời gian còn lại của ca thi, không được cộng giờ khi vào muộn.
- Làm câu trắc nghiệm với bốn lựa chọn A–D.
- Đồng hồ đếm ngược.
- Hết giờ tự nộp.
- Không lưu hoặc khôi phục bài nháp. Vào lại trước khi hết giờ thì bấm **Làm lại từ đầu**, nhận bài trống với hạn nộp cũ.
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
| B21DCCN004–B21DCCN103 | **12/12/2003** | Sinh viên 004–103 |

Trong Client chọn đúng ngày sinh bằng `DatePicker`.
Server tự bổ sung 100 mã `B21DCCN004` đến `B21DCCN103` chưa có mỗi khi khởi động ứng dụng.
Mỗi tài khoản mới có ngày sinh `12/12/2003` (`2003-12-12` trong giao thức). Tài khoản đã tồn tại giữ nguyên mã, họ tên, ngày sinh, trạng thái khóa và kết quả.

Giáo viên tự tạo tài khoản ở màn hình **Đăng nhập giáo viên** trên Server; không có tài khoản/mật khẩu giáo viên mặc định.

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

1. Chờ kết nối database. Lần đầu bấm **Tạo tài khoản giáo viên**, nhập tên đăng nhập, họ tên, mật khẩu (8–128 ký tự) và xác nhận. Sau đó đăng nhập và bấm **Khởi động Server**. Các lần sau chỉ cần đăng nhập tài khoản đã tạo.
2. Chờ dòng `Server đang chạy tại cổng 5000`.
   Ngay bên dưới có một **IP Server** và **Cổng** để nhập trên Client, tự chọn từ card Wi-Fi/Ethernet đang hoạt động và bỏ qua card mạng ảo. Địa chỉ vẫn hiển thị khi tạo đề hoặc xử lý vi phạm.
3. Chọn **Ngân hàng câu hỏi** → **Thêm môn học**. Bấm **Tạo câu hỏi**, chọn môn, độ khó từ 1 đến 6; nhập nội dung, bốn lựa chọn A–D, chọn đáp án đúng và bấm **Lưu câu hỏi**.
4. Chọn **Tạo đề thi**, nhập tên và thời gian, chọn môn thi. Lọc theo độ khó nếu cần, chọn các dòng câu hỏi bằng Ctrl/Shift rồi bấm **Thêm câu đã chọn →**. Danh sách bên phải là các câu trong đề; có thể bỏ câu hoặc dùng **Lên/Xuống** để đổi thứ tự. Bấm **Lưu đề thi**.
5. Chọn **Chọn bài thi để mở**, chọn một đề rồi bấm **Mở bài thi đã chọn**. Đồng hồ chung bắt đầu ngay từ thao tác này. Sinh viên bấm **Tham gia bài thi** để nhận câu hỏi và thời gian còn lại. Sau khi khởi động lại Server, giảng viên cần mở bài lại; đợt chưa hết giờ giữ nguyên hạn nộp.
6. Sau khi sinh viên nộp bài, chọn **Xem điểm**; bấm **Làm mới** để cập nhật bảng điểm. Bảng điểm giữ cả bài của đề cũ và sinh viên đã offline.

### Đổi giáo viên trên cùng Server

- Phía trên cùng hiện **Giáo viên: Họ tên (tên đăng nhập)**, **Đổi mật khẩu** và **Đăng xuất / Đổi giáo viên**.
- Chỉ đăng xuất sau khi ca thi hết giờ, mọi đáp án nhận trước hạn đã được xử lý và điểm đã được lưu. Server dừng và ngắt các Client của phiên cũ; đăng nhập tài khoản khác rồi khởi động Server lại. Học viên kết nối lại như trước.
- Từ khi mở đề đến khi ca thi hết giờ, Dừng Server, nút X và đổi giáo viên đều bị chặn, kể cả chưa ai tham gia hoặc mọi người đã nộp sớm. Sau khi khởi động lại ứng dụng, đăng nhập giáo viên khác cũng bị chặn khi ca cũ còn thời gian.
- Môn, câu hỏi, đề thi, bài tập và kết quả được lưu riêng. Mỗi tài khoản có thể tạo cùng tên môn mà không trùng với giáo viên khác. Lịch sử trên Client thuộc dữ liệu của giáo viên đang phụ trách Server.
- Tài khoản giáo viên được tạo đầu tiên nhận toàn bộ dữ liệu cũ của dự án, kể cả đề đã thi và kết quả. Tài khoản tạo sau bắt đầu trống. Dữ liệu cũ không bị xóa.
- Danh sách sinh viên và trạng thái khóa tài khoản học viên được dùng chung trên máy Server.

Database bổ sung bảng `teachers` và cột `owner_teacher_id` cho `subjects`, `bank_questions`, `exams`, `assignments`.
Mật khẩu giáo viên được lưu bằng PBKDF2-HMAC-SHA256 với salt ngẫu nhiên; không lưu mật khẩu dạng văn bản.

Mỗi câu hỏi bắt buộc thuộc một môn và có độ khó 1–6. Chỉ hỗ trợ **Trắc nghiệm** với bốn lựa chọn A–D và một đáp án đúng.
Có thể sửa câu đã lưu trong ngân hàng và dùng lại câu đó cho nhiều đề.
Ví dụ, nhập 100 câu vào ngân hàng môn Lập trình mạng; đề 1 chọn câu 1, 2, 3 còn đề 2 chọn câu 2, 3, 4.
Lưu đề hoặc bỏ một câu khỏi đề đang tạo không xóa câu trong ngân hàng; các câu đã dùng vẫn chọn được cho đề tiếp theo.
Đề chỉ chứa câu thuộc môn đã chọn, không thêm trùng cùng một câu, tối đa 200 câu. Đổi bộ lọc giữ các câu đang chọn;
đổi môn thi sẽ bỏ danh sách câu đã chọn. Cũng có thể thêm môn hoặc tạo/sửa câu hỏi ngay trong màn hình tạo đề.

Khi lưu đề, Server sao chép nội dung, lựa chọn, đáp án và độ khó của từng câu. Sửa câu trong ngân hàng chỉ áp dụng cho các đề được tạo sau đó;
nội dung và cách chấm của đề đã lưu không bị thay đổi.

Có thể tạo và lưu đề mới trong lúc sinh viên đang làm bài. Khi ca hiện tại còn thời gian, Server từ chối **mở một đề khác**; mở lại cùng đề chỉ thông báo lại và không đặt lại thời gian, kể cả mọi người đã nộp sớm. Sau khi ca thi hết giờ và mọi bài được chấm xong, chọn mở lại cùng đề sẽ tạo **đợt mới**: sinh viên được làm lại, còn điểm đợt cũ vẫn nằm trong lịch sử. Bảng điểm hiển thị mã lượt để phân biệt các đợt. Sau khi sửa mã nguồn, cần đóng các cửa sổ đang chạy và mở lại Server/Client để thấy giao diện mới.

### Trạng thái và xử lý vi phạm

- **ONLINE**: vừa đăng nhập hoặc đang chờ tham gia đề mới. Đăng nhập lại không lấy trạng thái nộp bài từ lịch sử.
- **DOING_EXAM**: đã bấm tham gia/làm lại đề; cột bên cạnh hiện mã lượt và tên đề.
- **SUBMITTED**: đã nộp hoặc đã được chấm hết giờ trong phiên này. Khi có đề mới để tham gia, trạng thái trở về **ONLINE**.
- Trong cột **Cảnh báo / Tài khoản**, bấm **Cảnh báo vi phạm**, nhập nội dung rồi gửi. Chỉ Client của sinh viên đó hiện thông báo; thời gian làm bài vẫn chạy.
- Bấm **Khóa tài khoản**, nhập lý do rồi xác nhận. Client nhận lý do khóa, ngắt phiên và trở về đăng nhập. Tài khoản bị chặn đăng nhập cho đến khi giảng viên mở khóa; khởi động lại Server không tự mở khóa.
- Bấm **Tài khoản bị khóa / Mở khóa** ở thanh nút phía trên bảng online, chọn sinh viên rồi bấm **Mở khóa tài khoản đã chọn** để cho phép đăng nhập lại. Tài khoản bị khóa không hiện trong bảng online vì Client đã bị ngắt kết nối.

Khóa tài khoản không xóa kết quả. Hết giờ Server chấm các lựa chọn đã nhận trong RAM; nếu được mở khóa và vào làm lại trước hạn, học viên bắt đầu bài trống theo hạn chung.
Hibernate bổ sung `students.account_locked` (mặc định `false`) và `lock_reason` khi Server khởi động với cấu hình cập nhật schema hiện tại.

## 7. Chạy Client

### Gửi một file EXE cho học viên

Chạy `build-client-exe.bat` để tạo **`dist/LANExamClient.exe`**. Gửi riêng file EXE này cho học viên dùng Windows 10/11 64 bit.
Mở EXE là chạy giao diện Client; Java và JavaFX đã được đóng gói sẵn. Lần đầu tự chuẩn bị runtime, các lần sau mở lại trực tiếp.
Học viên nhập IP Server, cổng, mã sinh viên và ngày sinh như bình thường.
Chi tiết đóng gói và kiểm tra nằm trong [hướng dẫn Client EXE](packaging/windows/README.md).

### Chạy từ mã nguồn

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
Server xét hạn bằng thời điểm nhận đủ thông điệp trên máy Server, trước khi chờ xử lý hoặc khóa database; thời gian nộp được lưu theo mốc này. Bài tới trước hạn vẫn được nhận nếu xử lý sau hạn. Cả nộp lần đầu lẫn nộp lại tới đúng hoặc sau hạn đều bị từ chối; thời gian bấm nút trên Client không quyết định hạn nộp.

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
10. Client B chọn đáp án, đóng ứng dụng rồi đăng nhập lại, bấm Làm lại từ đầu -> bài trống, thời gian tiếp tục theo hạn chung
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

Báo cáo đầy đủ theo cấu trúc bài tập lớn: [Bản Word](output/documents/Bao_cao_BTL_LAN_Exam_System.docx).

Kiểm thử bài tập bao gồm lưu/tải lại tệp, nộp lại, giới hạn dung lượng, phân quyền, chặn quá hạn và truyền tệp qua TCP thật trên loopback.
Để kiểm tra thêm giao diện JavaFX trên máy có desktop, chạy `mvn test -Dlanexam.uiTests=true`.
Lệnh này kiểm tra việc giữ nội dung đang soạn khi danh sách cập nhật và tạo ảnh xem trước ở `target/assignment-preview/`.

`QuestionBankIntegrationTest` kiểm tra lưu môn/câu hỏi, lọc theo môn và độ khó, chặn câu điền đáp án/câu khác môn/trùng câu,
giữ thứ tự chọn và giữ nguyên đề/đáp án khi sửa ngân hàng. Các kiểm thử giao diện còn kiểm tra tạo câu, chọn câu,
lọc, đổi môn và đổi thứ tự trong đề; chạy cùng cờ `-Dlanexam.uiTests=true`.

Ngân hàng dùng hai bảng mới `subjects`, `bank_questions`; đề có `subject_id`, câu trong đề có `bank_question_id` và `difficulty`.
Hibernate bổ sung bảng/cột khi khởi động Server. Các cột thêm vào đề/câu cũ cho phép NULL nên đề và kết quả cũ vẫn sử dụng được.
Đề cũ hiển thị **Chưa phân loại** và câu cũ chưa tự đưa vào ngân hàng vì chưa có thông tin môn/độ khó.
Chạy lại cả Server và Client bằng mã mới để cùng nhận thông tin môn thi.

Lược đồ có bảng `exam_attempts` và ràng buộc duy nhất `(student_id, exam_id)` ở cả `exam_attempts` và `submissions`. Mỗi lần mở lại đề sau khi đợt trước hết giờ và chấm xong sẽ tạo một bản ghi `exams` mới với `template_exam_id` trỏ về đề gốc; vì thế bài làm cũ được giữ nguyên mà sinh viên vẫn có thể làm lại trong đợt mới. Hibernate `update` tự thêm cột nullable này trên MySQL khi khởi động; vẫn nên sao lưu database trước khi nâng cấp dữ liệu thật. Nếu MySQL cũ đã có nhiều bài nộp cho cùng một sinh viên/đợt, cần xử lý bản ghi trùng trước khi cập nhật.

## 11. Giới hạn và hướng phát triển

- Sửa/xóa đề đã tạo và quản lý ngân hàng câu hỏi trên Server.
- Random câu hỏi và đảo đáp án.
- Server gửi thông báo cho sinh viên.
- Xuất kết quả Excel.
- Phân quyền tài khoản giảng viên.
- Tài khoản hiện dùng ngày sinh; giao thức TCP chưa mã hóa. Chỉ dùng trong LAN tin cậy để demo, chưa phù hợp với kỳ thi chính thức.
- Lựa chọn chưa nộp chỉ giữ trong RAM Server để tự chấm khi hết giờ; Server bị tắt cưỡng bức/mất điện sẽ mất các lựa chọn này. Đáp án chưa kịp truyền đến Server không được tính. Kết quả đã nộp vẫn lưu MySQL.

Các mục trên cần giải quyết trước khi triển khai thực tế.

### Nhận đáp án sát giờ và kết nối chậm

Server ghi thời điểm nhận xong từng thông điệp, trước khi phân tích và chờ lượt xử lý. Đáp án nhận trước hạn vẫn được tính dù được xử lý sau hạn; tác vụ chấm chờ các thông điệp hợp lệ đó hoàn tất. Đáp án nhận sau hạn không thay đổi kết quả.

Trang chờ tự đếm thời gian còn lại và khóa nút tham gia khi hết giờ. Server cũng thu hồi thông báo đề hết hạn. Dữ liệu gửi đến từng học viên có hàng đợi riêng, giữ thứ tự; kết nối bị kẹt ghi quá 10 giây hoặc tràn hàng đợi sẽ bị ngắt riêng để không giữ luồng chấm điểm.
