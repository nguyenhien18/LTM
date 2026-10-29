# Kịch bản kiểm thử và demo

## Chuẩn bị

Dùng database thử/bản sao, một Server MySQL và ít nhất hai máy Client trong LAN. Dùng dist/current/LANExamClient.exe; kiểm tra firewall/IP/cổng. Đề 1–2 phút giúp demo nhanh. Không cố ý dừng tiến trình của ca thi thật.

| TC | Thao tác | Kết quả mong đợi |
|---|---|---|
| 01 | Tạo giảng viên, sai/đúng mật khẩu | Sai bị chặn, đúng hiện tên; tài khoản mới trống |
| 02 | Tạo môn/hai lớp; preview CSV rồi hủy | Chưa ghi hồ sơ/thành viên |
| 03 | Xác nhận CSV năm cột; nhập cùng sinh viên vào hai lớp | Hồ sơ dùng lại, STT riêng; lớp chính quy khác lớp học phần |
| 04 | CSV sai ngày/trùng MSV/STT/xung đột | Báo lỗi, không nhập dở dang |
| 05 | Danh sách cũ thiếu STT, nhập lại CSV gốc | Cập nhật đúng STT theo MSV, không tạo trùng |
| 06 | Chia 60 sinh viên thành hai ca 30 người | Theo STT gốc; mở lại giữ danh sách |
| 07 | Tạo/sửa câu; nhập Word nhiều dòng/trùng/lỗi | Đúng A–D/đáp án; sai bị chặn, trùng bỏ qua, hủy không ghi |
| 08 | Tab Đề thi tạo/tìm/làm mới đề | Đúng tên/câu/thời lượng; không có giao thực hành hoặc mở ca trùng |
| 09 | Lấy ma trận mức, đổi một câu; thử thiếu ngân hàng | Đủ số lượng, không trùng; lỗi giữ danh sách soạn |
| 10 | Sửa ngân hàng sau lưu đề | Đề cũ giữ nội dung/đáp án đã chốt |
| 11 | Chuẩn bị rồi bắt đầu với bốn mã | Chuẩn bị chưa tính giờ; bắt đầu mới có deadline chung |
| 12 | A đúng ca, B khác ca | A có offer, B không nhận/không vào bằng ID của A |
| 13 | Đợi 30 giây sau mở mới tham gia | Chỉ còn thời gian theo deadline chung |
| 14 | Chọn đáp án, đợi lưu, mất mạng rồi vào lại | Cùng mã/bố cục/đáp án đã lưu/deadline |
| 15 | A đã vào, B cùng ca chưa vào; khởi động lại Server khi còn hạn | Đúng giảng viên, khởi động TCP: A tiếp tục, B nhận đề, không tạo lượt mới |
| 16 | A nộp sớm, B offline đến hết hạn | Server chấm/lưu cả hai theo đáp án hợp lệ đã nhận |
| 17 | Nộp hai lần, đăng nhập lại sau nộp | Một kết quả; không sửa bài đã nộp |
| 18 | Đáp án trước hạn chờ slot/DB; đáp án khác sau hạn | Xét đúng thời điểm nhận Server; test tự động kiểm tra hàng đợi |
| 19 | Đã nộp rồi thoát/vào lại; xem ca/lớp khác | Online/Offline thay đổi, tiến độ theo đúng lượt, không lẫn |
| 20 | Người chưa từng vào đến hết hạn | Vắng thi; không tự tạo điểm 0 |
| 21 | Cảnh báo/khóa riêng A, mở khóa | B không bị ảnh hưởng; khóa giữ bền, bài đã nhận vẫn chấm |
| 22 | Cùng MSV đăng nhập ở máy khác | Phiên mới thay phiên cũ; không lẫn đáp án sinh viên khác |
| 23 | Dừng/X/Đổi giảng viên trước hạn | Chặn đến hết hạn và chấm xong, kể cả nộp hết sớm |
| 24 | Mở ca tiếp theo, xem/lọc/xuất điểm | Giữ ca cũ, đúng bộ lọc/tiếng Việt/mã/điểm/giờ nộp |
| 25 | Đổi giảng viên sau khi đủ điều kiện | Đóng phiên cũ, dữ liệu chủ sở hữu tách riêng |
| 26 | Client không đọc/frame quá lớn; 80 socket đồng thời | Timeout/giới hạn riêng; các kết nối còn lại hoạt động (test loopback/H2) |

## Chạy tự động

```powershell
mvn test
mvn "-Dlanexam.uiTests=true" test
```

ExamWorkflowUiTest kiểm tra giao diện/luồng hiện tại. LegacyDataCompatibilityTest bảo toàn mapping lịch sử. Không có bài tập/gửi tệp thực hành trong bản hiện hành. Ghi riêng số máy, IP/cổng và kết quả thử LAN/MySQL thật; test 80 socket một máy không thay thế thử 80 máy.
