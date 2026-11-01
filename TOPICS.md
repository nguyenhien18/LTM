# Đề tài của nhóm

**Tên:** Hệ thống thi trắc nghiệm tập trung qua mạng LAN theo mô hình Client–Server.

Đây là mô tả đề tài nhóm đang triển khai, không phải danh sách TOPICS.md chính thức của giảng viên. Tình trạng phê duyệt đề tài: nhóm tự bổ sung sau khi có xác nhận của giảng viên.

## Bài toán và mục tiêu

Tổ chức thi trắc nghiệm tại phòng máy có nhiều sinh viên. Giảng viên quản lý lớp, chuẩn bị câu hỏi/đề và mở ca thi. Server phát đề đúng danh sách, tiếp nhận đáp án, kiểm soát thời hạn, lưu kết quả và ghi nhận các sự kiện cần giảng viên kiểm tra.

## Phạm vi

- Một tiến trình Server phục vụ một giảng viên và một ca đang diễn ra tại một thời điểm.
- Nhiều Client sinh viên kết nối TCP vào Server trong LAN; dữ liệu dùng JSON Lines UTF-8.
- MySQL đặt phía Server; Client không kết nối trực tiếp CSDL và không nhận đáp án đúng.
- Quản lý nhiều lớp học phần; sinh viên có thể học nhiều lớp. Chia ca theo STT CSV; ngân hàng câu hỏi theo môn của giảng viên.
- Trắc nghiệm một đáp án A–D, ma trận mức 1–6, mã đề và chấm tập trung.
- Tiếp tục lượt còn hạn bằng đăng nhập lại thủ công; khôi phục ca còn hạn sau restart.
- Cảnh báo rời màn hình liên tục từ 5 giây; giảng viên quyết định nhắc nhở/đình chỉ.

## Nội dung Lập trình mạng cần bảo vệ

TCP framing; vòng đời kết nối; protocol và DTO; request/response và thông báo chủ động; virtual thread; FIFO theo kết nối; giới hạn request đồng thời; thời điểm nhận request và deadline; lỗi, timeout, nộp lặp; phục hồi trạng thái.

## Điểm cải tiến và đánh giá

Các cải tiến đã có test: khôi phục ca có danh sách và bài đã lưu; chốt bài theo thời điểm Server nhận request; mã đề giữ cơ cấu mức độ; episode ID chống cảnh báo trùng. Đã chạy bộ test chức năng và phép đo TCP loopback/H2 với 3.624 thao tác thành công. Xem [kết quả và dữ liệu](statics/results/README.md), [hình thiết kế](statics/README.md) và [kế hoạch đo tiếp](statics/results/EXPERIMENT_PLAN.md).

## Thông tin nhóm

Trường/khoa: ........................................

Lớp/nhóm: ........................................

Giảng viên hướng dẫn: ........................................

| Thành viên | MSV | Phần việc kỹ thuật thực tế | Bằng chứng đóng góp |
|---|---|---|---|
| | | | |
| | | | |
| | | | |
| | | | |
