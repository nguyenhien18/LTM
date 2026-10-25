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

## Tài khoản giáo viên trên máy Server

- `TeacherLoginPane` xác thực tài khoản cục bộ trước khi mở giao diện quản lý; mỗi lần một giáo viên sử dụng Server.
- `TeacherAccountService` tạo tài khoản, đổi mật khẩu có kiểm tra mật khẩu cũ và chặn đăng nhập giáo viên khác nếu còn lượt thi chưa kết thúc.
- `TeacherPassword` dùng PBKDF2-HMAC-SHA256, 600.000 vòng lặp, salt ngẫu nhiên 16 byte; `TeacherIdentity` không chứa mật khẩu hoặc hash.
- `TeacherOwned` bổ sung metadata chủ sở hữu cho môn, câu hỏi ngân hàng, đề/đợt thi và bài tập. Các service được khởi tạo với mã giáo viên đã đăng nhập và kiểm tra quyền ở cả truy vấn danh sách lẫn thao tác bằng mã dữ liệu.
- Tài khoản đầu tiên nhận dữ liệu cũ trong một transaction. Tên chuẩn hóa của môn được đặt trong phạm vi giáo viên để cho phép hai giáo viên có cùng tên môn.
- `ExamServer.stopIfIdle` và việc tham gia bài thi dùng chung khóa đọc/ghi, tránh dừng Server khi lượt thi mới vừa được tạo. Kiểm tra dựa vào database nên gồm cả sinh viên đã offline.
- Đăng xuất đóng các kết nối và cửa sổ của phiên cũ; tác vụ giao diện cũ không cập nhật sang phiên giáo viên mới. Danh sách sinh viên/khóa học viên vẫn dùng chung.

## Trạng thái phiên và xử lý vi phạm

- `ClientSession` giữ trạng thái kèm mã lượt và tên đề; đăng nhập mới là `ONLINE`, không suy từ lịch sử điểm.
- `SessionRegistry` chỉ nhận cập nhật đề từ handler đang sở hữu phiên đó. Đổi đợt thi đưa phiên đã nộp về `ONLINE`; tham gia/tiếp tục chuyển `DOING_EXAM`.
- Tác vụ chấm quá hạn đồng thời cập nhật phiên đang làm sang `SUBMITTED` và gửi kết quả về Client còn kết nối.
- `StudentSessionTable` hiển thị đề gắn với trạng thái và nút gửi cảnh báo/khóa cho từng sinh viên.
- `AuthService` lưu khóa/lý do trong `students`; `ClientHandler` kiểm tra khóa khi đăng nhập và trước yêu cầu đã xác thực.
- `ExamServer` gửi cảnh báo đến đúng handler; khóa được lưu trước khi gửi thông báo ngắt phiên. `LockedAccountManager` cho phép giảng viên mở khóa tài khoản offline.
- Khóa giữ nguyên bản nháp và kết quả đã lưu; việc chấm quá hạn vẫn theo hạn nộp ban đầu.

## Ngân hàng câu hỏi và tạo đề

- `Subject` lưu môn học; tên môn được chuẩn hóa khoảng trắng và chữ hoa/thường để tránh tạo trùng.
- `BankQuestion` thuộc một môn, có độ khó 1–6, bốn lựa chọn trắc nghiệm và một đáp án đúng.
- `QuestionBankService` chỉ lưu/sửa câu trắc nghiệm và lọc theo môn, độ khó; giao diện chỉ gọi database qua service trên máy giảng viên.
- `ExamService.createExamFromBank` xác thực các mã câu đã chọn trong một transaction, chỉ cho phép cùng môn và không trùng mã.
- Câu điền và đề chứa câu điền đã lưu từ phiên bản cũ vẫn được giữ trong database để đọc lịch sử, nhưng không được chọn để tạo hoặc mở đề mới.
- `Exam` giữ môn thi; các `Question` trong đề là bản sao độc lập từ ngân hàng, giữ thứ tự chọn, mã nguồn và độ khó.
- Khi mở đợt thi, đề và metadata tiếp tục được sao chép để sửa ngân hàng không ảnh hưởng nội dung, đáp án hoặc kết quả đã có.
- Client nhận tên môn và câu hỏi của đợt thi, không nhận đáp án đúng hay ngân hàng câu hỏi.
