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
- Mỗi kết nối TCP được xử lý ngay trên một virtual thread của Java 21. Handler giữ socket suốt phiên thi, nên không dùng hàng đợi tác vụ phía sau một nhóm luồng cố định.
- Tối đa 16 yêu cầu Client được xử lý cùng lúc để dành chỗ trong pool database mặc định 20 kết nối cho giảng viên và tác vụ hết giờ. Yêu cầu chờ bằng semaphore; mọi kết nối vẫn mở và mỗi Client giữ đúng thứ tự thông điệp.

## Tài khoản giáo viên trên máy Server

- `TeacherLoginPane` xác thực tài khoản cục bộ trước khi mở giao diện quản lý; mỗi lần một giáo viên sử dụng Server.
- `TeacherAccountService` tạo tài khoản, đổi mật khẩu có kiểm tra mật khẩu cũ và chặn đăng nhập giáo viên khác khi ca hiện tại chưa hết giờ.
- `TeacherPassword` dùng PBKDF2-HMAC-SHA256, 600.000 vòng lặp, salt ngẫu nhiên 16 byte; `TeacherIdentity` không chứa mật khẩu hoặc hash.
- `TeacherOwned` bổ sung metadata chủ sở hữu cho môn, câu hỏi ngân hàng, đề/đợt thi và bài tập. Các service được khởi tạo với mã giáo viên đã đăng nhập và kiểm tra quyền ở cả truy vấn danh sách lẫn thao tác bằng mã dữ liệu.
- Tài khoản đầu tiên nhận dữ liệu cũ trong một transaction. Tên chuẩn hóa của môn được đặt trong phạm vi giáo viên để cho phép hai giáo viên có cùng tên môn.
- `ExamServer.openExam`, `stopIfIdle` và việc tham gia dùng chung khóa đọc/ghi. Mở đề và dừng không chạy chồng nhau; đã mở đề thì chặn dừng trong toàn bộ thời gian ca thi, kể cả chưa ai tham gia hoặc mọi người nộp sớm.
- Đăng xuất đóng các kết nối và cửa sổ của phiên cũ; tác vụ giao diện cũ không cập nhật sang phiên giáo viên mới. Danh sách sinh viên/khóa học viên vẫn dùng chung.

## Trạng thái phiên và xử lý vi phạm

- `ClientSession` giữ trạng thái kèm mã lượt và tên đề; đăng nhập mới là `ONLINE`, không suy từ lịch sử điểm.
- `SessionRegistry` chỉ nhận cập nhật đề từ handler đang sở hữu phiên đó. Đổi đợt thi đưa phiên đã nộp về `ONLINE`; tham gia/làm lại chuyển `DOING_EXAM`.
- Tác vụ chấm quá hạn đồng thời cập nhật phiên đang làm sang `SUBMITTED` và gửi kết quả về Client còn kết nối.
- `StudentSessionTable` hiển thị đề gắn với trạng thái và nút gửi cảnh báo/khóa cho từng sinh viên.
- `AuthService` lưu khóa/lý do trong `students`; `ClientHandler` kiểm tra khóa khi đăng nhập và trước yêu cầu đã xác thực.
- `ExamServer` gửi cảnh báo đến đúng handler; khóa được lưu trước khi gửi thông báo ngắt phiên. `LockedAccountManager` cho phép giảng viên mở khóa tài khoản offline.
- Khóa giữ nguyên kết quả và các lựa chọn hiện tại trong RAM để chấm hết giờ. Vào lại làm bài trống theo hạn chung.

## Ngân hàng câu hỏi và tạo đề

- `Subject` lưu môn học; tên môn được chuẩn hóa khoảng trắng và chữ hoa/thường để tránh tạo trùng.
- `BankQuestion` thuộc một môn, có độ khó 1–6, bốn lựa chọn trắc nghiệm và một đáp án đúng.
- `QuestionBankService` chỉ lưu/sửa câu trắc nghiệm và lọc theo môn, độ khó; giao diện chỉ gọi database qua service trên máy giảng viên.
- `ExamService.createExamFromBank` xác thực các mã câu đã chọn trong một transaction, chỉ cho phép cùng môn và không trùng mã.
- Câu điền và đề chứa câu điền đã lưu từ phiên bản cũ vẫn được giữ trong database để đọc lịch sử, nhưng không được chọn để tạo hoặc mở đề mới.
- `Exam` giữ môn thi; các `Question` trong đề là bản sao độc lập từ ngân hàng, giữ thứ tự chọn, mã nguồn và độ khó.
- Khi mở đợt thi, đề và metadata tiếp tục được sao chép để sửa ngân hàng không ảnh hưởng nội dung, đáp án hoặc kết quả đã có.
- Client nhận tên môn và câu hỏi của đợt thi, không nhận đáp án đúng hay ngân hàng câu hỏi.

## Thời gian chung, bài trống khi vào lại và đóng Server

- `Exam.openedAt` và `deadlineAt` lưu thời điểm giảng viên mở đề và hạn kết thúc chung. Mọi `ExamAttempt` dùng cùng hạn; không gia hạn khi vào muộn hoặc vào lại.
- Client gửi `ANSWER_UPDATE` ngay khi đổi lựa chọn. `ExamService` chỉ giữ lựa chọn trong RAM, gắn với mã lượt của đúng học viên; không ghi nháp vào database, không gửi lại đáp án cho Client.
- `startAttempt` xóa lựa chọn của lượt cũ khi học viên vào làm lại. Client luôn dựng bài trống, không giữ đáp án sau mất kết nối.
- Tác vụ mỗi giây chấm những lượt hết hạn từ RAM, lưu kết quả cuối vào MySQL và gửi điểm cho Client online. Học viên offline cũng được chấm; nếu Server mất điện, lựa chọn chưa nộp trong RAM không được khôi phục.
- Nút X được chặn bằng cùng kiểm tra database/khóa tham gia như nút Dừng Server. Ca còn hạn khiến thao tác bị từ chối. Sau hạn, phải xử lý hết đáp án tới đúng giờ và lưu điểm xong mới được đóng kết nối.
- Các cột thời gian mới nullable để giữ dữ liệu cũ. Khi mở lại đợt cũ chưa có thời gian chung, lấy thời điểm tham gia sớm nhất làm mốc; không đọc cột nháp cũ.

## Thông điệp chờ xử lý và gửi dữ liệu

- `RequestReceipt` đăng ký dấu thời gian Server ngay sau khi đọc trọn dòng JSON, trước khi parse/chờ semaphore. Chỉ ANSWER_UPDATE và SUBMIT_EXAM của phiên đã đăng nhập giữ receipt trong thời gian chờ xử lý.
- Tác vụ chấm khóa lượt và kiểm tra receipt trước hạn của đúng sinh viên/đợt. Nếu còn receipt, lượt được hoãn đến vòng sau; đáp án xử lý muộn vẫn xét theo thời điểm nhận. Receipt được giải phóng cả khi lỗi hoặc đóng kết nối.
- `ClientWriter` dùng virtual thread riêng cho từng socket, hàng đợi tối đa 64 thông điệp và 16 Mi ký tự chờ. Lệnh send chỉ xếp hàng; ghi socket bị kẹt quá 10 giây hoặc tràn hàng đợi sẽ đóng riêng kết nối đó. Thông báo khóa tài khoản được flush trước khi đóng.
- `LanClient` chỉ khóa trạng thái để lấy kết nối hiện tại, rồi khóa riêng writer của kết nối khi gửi trọn thông điệp. Đóng/đăng xuất đóng trực tiếp socket mà không chờ khóa ghi; socket đóng sẽ giải phóng lệnh gửi đang kẹt. Luồng đọc cũ chỉ được đóng đúng kết nối của nó khi kết thúc.
- `ASSIGNMENT_SUBMIT` truyền dấu thời gian nhận đủ thông điệp từ luồng đọc vào `AssignmentService`; không cần giữ receipt chờ chấm thi. Cả xét hạn và `submittedAt` dùng mốc nhận này, kể cả nộp lại hoặc chờ khóa dòng database. Tới đúng hoặc sau hạn bị từ chối; payload Client không có trường tự khai thời gian nộp.
- Offer và đề mang deadlineEpochMillis/serverTimeEpochMillis. Client dùng chênh lệch thời gian Server và đồng hồ đơn điệu để đếm xuống, không phụ thuộc giờ hệ thống máy học sinh. Hết hạn thì nút tham gia bị khóa; Server thu hồi offer.

Luồng đọc TCP và luồng xử lý FIFO của từng Client được tách riêng. Luồng đọc tiếp tục đóng dấu thời gian cho các thông điệp hoàn chỉnh khi yêu cầu trước đang chờ slot; hàng đợi vào giới hạn 64 thông điệp/16 Mi ký tự. Khi đầu vào EOF hoặc ghi thất bại, các thông điệp đã nhận được xử lý hết trước khi xóa phiên. Dừng cưỡng bức hoặc thay phiên đăng nhập mới vẫn hủy phiên cũ.
