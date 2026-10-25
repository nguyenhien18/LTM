# Tóm tắt báo cáo LAN Exam System

## Mục tiêu và phạm vi

Ứng dụng thi trắc nghiệm trên mạng LAN theo mô hình một server giảng viên và nhiều client sinh viên. Client JavaFX chỉ gửi yêu cầu và hiển thị giao diện; server TCP/JPA xác thực, phát đề, quản lý lượt thi, lưu bài và chấm điểm.

## Luồng chính

Giáo viên tạo/đăng nhập tài khoản trên cùng máy Server trước khi mở giao diện quản lý. Mỗi lần một giáo viên sử dụng Server;
họ tên và tên đăng nhập được hiển thị rõ ở đầu cửa sổ. Môn, câu hỏi, đề thi, bài tập và kết quả được tách theo chủ sở hữu.
Đổi mật khẩu yêu cầu mật khẩu hiện tại. Đăng xuất/dừng Server bị chặn nếu còn lượt thi chưa hết hạn, gồm cả sinh viên offline;
khi được phép đổi, các kết nối cũ được đóng trước khi đăng nhập giáo viên tiếp theo.

1. Sinh viên đăng nhập bằng mã sinh viên và ngày sinh. Server quản lý một kết nối hiện tại cho mỗi mã sinh viên.
2. Giảng viên chọn một đề từ danh sách đã lưu để mở bài thi. Server tạo một đợt thi từ đề gốc, rồi gửi thông tin đề đang mở, chưa gửi câu hỏi. Client hiển thị trang chính gồm bài có thể tham gia và lịch sử điểm cá nhân. Sinh viên bấm **Tham gia bài thi** để Server tạo `ExamAttempt` với thời điểm bắt đầu và hạn nộp; tham gia lại trong cùng đợt không gia hạn lượt cũ.
3. Client gửi `SAVE_DRAFT` sau khi thay đổi đáp án. Server lưu vào MySQL trên máy Server khi lượt thi còn thời gian. Khi đăng nhập lại, trang chính có nút **Tiếp tục bài thi**; bấm nút sẽ nhận đề, thời gian còn lại và đáp án đã lưu.
4. Khi nhận `SUBMIT_EXAM`, server khóa dòng lượt thi, kiểm tra hạn nộp và tạo tối đa một `Submission` cho cặp sinh viên/đề. Sau hạn, server dùng bản nháp đã lưu. Một tác vụ định kỳ chấm các lượt quá hạn kể cả khi client đã ngắt mạng.
5. Kết quả gồm điểm thang 10, số câu đúng và tổng số câu. Nộp lại trả kết quả đã lưu, không chấm thêm.
6. Giảng viên tạo câu trắc nghiệm với bốn lựa chọn A–D và một đáp án đúng trong ngân hàng, phân loại theo môn và độ khó 1–6. Khi tạo đề, chọn môn, lọc câu theo độ khó, chọn và sắp xếp các câu. Server sao chép câu vào đề để việc sửa ngân hàng không đổi đề đã lưu. Đề mới chưa mở ngay; giảng viên chọn mở sau khi lưu. Khi đợt hiện tại đã hoàn tất, mở lại cùng đề tạo đợt mới; khi còn lượt thi đang làm, Server không cho đổi sang đề khác.
7. Giảng viên mở bảng điểm để xem kết quả các đề của mình, gồm sinh viên đã offline; nút Làm mới đọc lại dữ liệu từ MySQL.

## Thiết kế dữ liệu

`teachers` lưu tên đăng nhập duy nhất, họ tên và hash mật khẩu PBKDF2-HMAC-SHA256 có salt riêng.
`owner_teacher_id` trên `subjects`, `bank_questions`, `exams`, `assignments` phân chia dữ liệu; kết quả kế thừa phạm vi từ đề thi.
Tài khoản đầu tiên nhận dữ liệu cũ trong một transaction. Tài khoản sinh viên và khóa học viên vẫn dùng chung trên Server.

`students` và `exams`/`questions` chứa danh tính và đề. Đề gốc có `template_exam_id = NULL`; mỗi đợt mở lại tạo một bản sao đề/câu hỏi với `template_exam_id` trỏ về đề gốc và mã đề riêng. `exam_attempts` lưu quyền làm bài, thời hạn, bản nháp và trạng thái nộp. `submissions`/`student_answers` lưu kết quả cuối cùng. Ràng buộc duy nhất `(student_id, exam_id)` chặn nộp trùng trong một đợt nhưng vẫn cho phép làm lại ở đợt mới.

`subjects` và `bank_questions` lưu môn và câu hỏi dùng lại. Mỗi câu có môn, độ khó 1–6, nội dung, bốn lựa chọn trắc nghiệm và một đáp án đúng.
`exams.subject_id` lưu môn thi; `questions.bank_question_id`/`difficulty` ghi lại nguồn và độ khó lúc tạo đề.
Đây là metadata đi kèm bản sao nội dung, không liên kết đáp án chấm thi tới câu đang được sửa trong ngân hàng.

## Kiểm chứng

`TeacherAccountIntegrationTest` kiểm tra đăng ký/đổi mật khẩu, bảo toàn dữ liệu cũ, cô lập dữ liệu giữa hai giáo viên,
từ chối thao tác bằng mã dữ liệu của người khác, chặn đổi giáo viên khi có lượt thi offline và trường hợp tham gia thi đồng thời với dừng Server.
Kiểm thử JavaFX kiểm tra đăng ký, xác nhận mật khẩu, đăng nhập sai/đúng, hiển thị tên và đổi tài khoản sau khi hoàn tất lượt thi.

`ExamServiceIntegrationTest` dùng Hibernate và H2 để kiểm tra: từ chối nộp khi chưa tham gia; tham gia lại giữ nguyên hạn; nộp lặp giữ nguyên một kết quả; khôi phục bản nháp; từ chối mã câu hỏi lạ; chấm bản nháp thay vì đáp án gửi sau hạn; lưu đề mới mà không đổi đề đang mở; chặn chọn đề khác khi còn lượt làm bài; mở lại cùng đề tạo đợt mới; giữ cả điểm cũ và điểm mới; và chỉ hiển thị lịch sử của đúng sinh viên. Chạy bằng `mvn test`. Kiểm tra GUI và nhiều máy LAN theo `TEST_2_HOURS.md`.

## Giới hạn

Đây là bản demo trong LAN tin cậy. Ngày sinh là thông tin xác thực yếu và TCP chưa mã hóa. Tài khoản giáo viên dùng lần lượt trên một máy Server; giao diện chỉ tạo đề mới, chưa sửa/xóa đề cũ. Bản nháp được gửi sau khoảng 600 ms không nhập thêm; tắt máy ngay sau khi gõ có thể mất thay đổi cuối. Khi triển khai thực tế cần xác thực sinh viên mạnh hơn, TLS, quy trình sao lưu/migration database và kiểm thử tải/mất mạng trên nhiều máy.
