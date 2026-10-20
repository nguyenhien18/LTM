# Kịch bản test trong khoảng 2 giờ

Không cần để một bài thi chạy đủ 2 giờ. Mục tiêu là trong buổi test có thể kiểm tra toàn bộ luồng mạng và chức năng chính.

| TC | Nội dung | Kết quả mong đợi |
|---|---|---|
| TC01 | Khởi động Server | Server lắng nghe port 5000 |
| TC02 | Client A kết nối | Server nhận socket |
| TC03 | Login sai | Client nhận thông báo thất bại |
| TC04 | Login đúng | Server hiện MSV + IP + ONLINE |
| TC05 | Client B login | Server quản lý đồng thời 2 Client |
| TC06 | Server mở danh sách đề, chọn một bài thi | Cả 2 Client thấy thông tin đúng đề đã chọn; chưa tính giờ khi chưa tham gia |
| TC06a | Client bấm Tham gia bài thi | Nhận câu hỏi và bắt đầu tính giờ; Server hiện DOING_EXAM |
| TC07 | Client làm bài | Giao diện lưu lựa chọn hiện tại |
| TC08 | Client A nộp | Server lưu bài, chấm điểm, status SUBMITTED |
| TC09 | Client nhận điểm | Hiện score và số câu đúng |
| TC10 | Hết giờ | Client tự động gửi SUBMIT_EXAM |
| TC11 | Đóng Client | Server loại socket khỏi danh sách online |
| TC12 | Mở 3 Client | Kiểm tra đa luồng và mapping nhiều phiên |
| TC13 | Đóng Client giữa giờ rồi đăng nhập lại | Trang chính có Tiếp tục bài thi; bấm vào nhận đáp án đã lưu và thời gian còn lại |
| TC14 | Bấm Mở bài thi lần nữa | Không đặt lại hạn nộp của lượt thi cũ |
| TC15 | Gửi `SUBMIT_EXAM` hai lần | Chỉ có một dòng `submissions`, kết quả không đổi |
| TC16 | Gửi `SUBMIT_EXAM` trước khi tham gia | Server từ chối |
| TC17 | Mất mạng đến hết giờ | Server chấm bản nháp đã lưu; đăng nhập lại xem kết quả |
| TC18 | Giảng viên tạo đề 2 phút với một câu trắc nghiệm và một câu điền | Đề xuất hiện trong danh sách MySQL; Client chỉ thấy đề sau khi giảng viên chọn mở |
| TC19 | Tạo đề mới khi còn sinh viên đang làm, rồi chọn mở đề mới | Tạo và lưu được; Server từ chối mở đề khác cho đến khi bài cũ hoàn tất |
| TC20 | Mở Xem điểm sau khi nộp và sau khi Client thoát | Điểm, số câu đúng, tên đề và thời gian nộp vẫn hiện; Làm mới cập nhật bài mới |
| TC21 | Client xem điểm, bấm Về trang chính rồi Đổi tài khoản | Trang chính giữ kết nối và có lịch sử; đăng xuất trở lại trang đăng nhập, Server xóa phiên online cũ |
| TC22 | Đăng nhập lại bằng MSV vừa nộp | Trang chính hiện lịch sử điểm, không mời tham gia lại đề đã nộp |
| TC23 | Sau khi mọi sinh viên nộp, Server chọn mở lại chính đề đó | Client đã nộp thấy bài thi mới để tham gia; lịch sử vẫn có điểm đợt cũ |
| TC24 | Cùng sinh viên nộp bài ở đợt mở lại | Lịch sử Client và bảng điểm Server có hai dòng, mã lượt khác nhau; không ghi đè điểm cũ |
| TC25 | Server bấm Bài tập / Bài nộp và giao bài có tệp đề | Client online tự thấy bài trong tab Bài tập; Client đăng nhập sau cũng thấy |
| TC26 | Client tải đề, nộp nội dung kèm ZIP rồi đăng nhập lại | Nội dung, tên tệp, thời gian nộp vẫn hiện; tệp tải lại có nội dung đúng |
| TC27 | Client nộp lại trước hạn | Giảng viên chỉ thấy một dòng cho học viên, với nội dung và thời gian nộp mới |
| TC28 | Client sửa nội dung mà không chọn tệp mới, sau đó thử Bỏ tệp | Tệp cũ được giữ ở lần đầu; lần sau bỏ tệp nếu vẫn còn nội dung văn bản |
| TC29 | Đặt hạn gần nhất rồi nộp/nộp lại sau hạn | Server từ chối, bản nộp đã được chấp nhận trước đó vẫn nguyên vẹn |
| TC30 | Client B xem bài tập đã được Client A nộp | B không thấy nội dung hay tải được tệp bài nộp của A |
| TC31 | Chọn tệp trên 5 MB hoặc nộp rỗng | Có thông báo lỗi, không tạo hay ghi đè bản nộp |
| TC32 | Đang nhập bài tập thì giảng viên giao thêm bài mới | Danh sách cập nhật và nội dung đang soạn vẫn được giữ |

## Gợi ý demo nhanh

Để demo hết giờ nhanh, dùng **Tạo đề thi** trên Server và đặt thời lượng 2 phút, chọn đề trong **Chọn bài thi để mở**, rồi cho Client bấm **Tham gia bài thi**. Đề demo mặc định có thời lượng 20 phút.
