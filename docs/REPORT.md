# Báo cáo kỹ thuật LAN Exam System

## Mục tiêu và phạm vi

Thi trắc nghiệm trong phòng máy: JavaFX Client sinh viên, JavaFX Server giảng viên, TCP Socket/JSON Lines và MySQL chỉ đặt ở Server. Một giảng viên dùng Server và một lượt thi hoạt động tại một thời điểm. Giảng viên quản lý nhiều lớp; sinh viên có thể thuộc nhiều lớp.

Client nhận câu hỏi/gửi lựa chọn; Server xác thực, kiểm tra lớp/ca, quyết định hạn nộp, phân mã đề, lưu đáp án và chấm điểm. Thi không cần Internet sau khi chuẩn bị runtime/thư viện.

## Luồng nghiệp vụ

1. Giảng viên đăng ký/đăng nhập; tài khoản mới trống. Đổi mật khẩu yêu cầu mật khẩu hiện tại.
2. Tạo môn/lớp, nhập CSV STT/MSV/họ tên/ngày sinh/lớp chính quy. Xem trước rồi xác nhận. Hồ sơ dùng chung; STT riêng theo lớp.
3. Soạn câu hoặc nhập Word theo mức 1–6. Tạo đề mẫu bằng chọn thủ công hoặc ma trận lấy câu ngẫu nhiên không trùng; dùng lại giữa các lớp cùng môn.
4. Chọn lớp/ca/đề/số mã, chuẩn bị danh sách. Chuẩn bị chưa tính giờ. Bắt đầu mới tạo lượt và deadline chung; vào muộn chỉ dùng thời gian còn lại.
5. Sinh viên kết nối IP/cổng, xác thực MSV/ngày sinh, chờ đúng ca rồi tham gia. Không tự chọn lớp/mã đề.
6. Mỗi thay đổi gửi ANSWER_UPDATE; Server kiểm tra lượt/câu hỏi/hạn và ghi exam_attempts.saved_answers_json. Vào lại giữ cùng mã, thứ tự câu/lựa chọn, đáp án đã lưu và deadline.
7. SUBMIT_EXAM trước hạn chốt lựa chọn hợp lệ; sau hạn dùng dữ liệu đã nhận/lưu đúng hạn. Nộp lặp trả kết quả cũ. Hết giờ tự chấm cả lượt offline.
8. Server khởi động lại nạp ca còn hạn trước khi nhận login: người chưa tham gia vẫn có offer, người đang làm tiếp tục bài cũ. Không tạo lại lượt hoặc cộng giờ.
9. Bảng theo dõi tách Online/Offline khỏi tiến độ lưu bền đúng lớp/ca. Đã nộp vẫn giữ khi offline; chưa từng vào đến hết hạn là Vắng thi.
10. Xem/lọc lớp/lượt và xuất điểm CSV. Dừng/đóng/đổi giảng viên bị chặn khi ca còn hạn hoặc còn lượt phải hoàn tất.

## Kiến trúc mã nguồn

| Thành phần | Trách nhiệm |
|---|---|
| client/ClientApp, ExamQuestionNavigator | Đăng nhập, phòng chờ, bài làm, điều hướng, lịch sử |
| client/network/LanClient | Kết nối/đọc/ghi TCP, thông báo mất mạng |
| common, common/dto | Loại message, JSON, DTO thi, giới hạn frame |
| common/ui/AppUi, UiSupport | Theme, cột bảng, thông báo, tác vụ nền |
| server/ServerApp | Tài khoản giảng viên, bốn trang và vòng đời Server |
| ClassManager, QuestionBankBrowser | Lớp/CSV và ngân hàng/Word |
| ExamTemplateManager, ExamEditorDialog | Tạo/tìm/làm mới đề mẫu, chọn câu và ma trận mức |
| ExamOrganizer, ExamResultsPane | Mở/theo dõi ca, xem/xuất kết quả |
| server/network | Accept, phiên, hàng đợi xử lý/gửi |
| server/service, entity, persistence | Nghiệp vụ, dữ liệu thi, kết nối MySQL |
| server/legacy/entity | Hai mapping dữ liệu thực hành lịch sử, không có UI/service/API thực hành |

## Giao thức và đồng thời

Mỗi message là một dòng JSON UTF-8 gồm type/payload. TCP là luồng byte nên phía nhận ghép đến newline, không coi một lần read là một message. Xuống dòng trong chuỗi được JSON escape; frame có giới hạn kích thước.

Server dùng virtual thread, FIFO riêng mỗi kết nối, semaphore 16 slot xử lý để giữ tài nguyên database. Outbox/timeout ghi riêng tránh Client chậm chặn cả lớp. PING mỗi 5 giây, timeout đọc 20 giây, cleanup khi EOF/lỗi.

Thời điểm nhận đủ frame do Server đóng dấu. Yêu cầu trước hạn được chờ slot/khóa DB mà vẫn được xét; chấm hết giờ chờ các yêu cầu đó. Client không tự khai điểm/thời điểm nhận. Mỗi MSV có một phiên hiện hành; phiên mới thay phiên cũ.

Transaction, khóa dòng và unique(student_id, exam_id) bảo vệ lượt/bài nộp. Không gửi đáp án đúng hoặc bảng phân mã của người khác cho Client.

## Thiết kế dữ liệu

teachers lưu hash PBKDF2 có salt. owner_teacher_id chia môn/ngân hàng/lớp/đề/điểm theo giảng viên; hồ sơ sinh viên và khóa tài khoản dùng chung.

course_classes/class_students thể hiện nhiều–nhiều; csv_ordinal nằm ở class_students, regular_class ở students. exam_shifts/exam_shift_students chốt danh sách theo STT.

subjects/bank_questions là kho chung của giảng viên. exams chứa đề mẫu và lượt mở phân biệt bằng template_exam_id; questions là bản sao đã chốt, sửa ngân hàng không đổi điểm cũ.

exam_attempts lưu deadline/mã/đáp án đang làm/trạng thái nộp; submissions/student_answers lưu kết quả cuối. variants_json lưu bố cục và phân mã cố định, cùng bộ nội dung nhưng đảo câu/lựa chọn; phân cân bằng không đồng nghĩa hai máy ngồi cạnh khác mã.

Không DROP bảng khi dọn mã. Giữ mapping assignments/assignment_submissions và QuestionType.TEXT để đọc lịch sử; không còn API thực hành hoặc tạo TEXT mới.

## Kiểm chứng và giới hạn

Chạy mvn test; thêm -Dlanexam.uiTests=true để chạy JavaFX. Test dùng H2/TCP loopback, không dùng MySQL thật. ExamWorkflowUiTest kiểm tra UI/luồng thi; LegacyDataCompatibilityTest kiểm tra dữ liệu cũ còn đọc được. Có test 80 socket, deadline/nộp trùng, hồi phục ca/đáp án, phân quyền, CSV/STT, Word và mã đề. Không coi 80 socket là thử 80 máy thật; kịch bản manual ở TEST_2_HOURS.md.

MSV/ngày sinh là xác thực yếu, TCP chưa TLS; cảnh báo do giảng viên gửi thủ công. Chưa có làm bài offline, tự reconnect, ACK riêng cho lưu đáp án, gia hạn cá nhân hoặc kết thúc sớm. Chỉ lựa chọn Server đã lưu được bảo đảm khôi phục. Phạm vi là BTL/demo trên LAN tin cậy.
