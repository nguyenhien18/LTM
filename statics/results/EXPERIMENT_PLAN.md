# Kế hoạch thực nghiệm (chưa thực hiện benchmark)

## Môi trường cần ghi lại

Commit; thời gian; CPU/RAM/OS từng máy; JDK; phiên bản MySQL/H2; số Client; chạy loopback hay nhiều máy; Ethernet/Wi-Fi; topology; số câu, độ dài câu và số mã đề; tần suất gửi đáp án; thời lượng; số lần lặp. Không dùng dữ liệu thi thật. Phân biệt test H2 với đo MySQL.

## Kịch bản

| Nhóm | Các mức/thao tác dự kiến | Chỉ số/bằng chứng |
|---|---|---|
| Đăng nhập và nhận đề | 1, 10, 20, 40, 80 Client khởi phát cùng thời điểm | Tỷ lệ thành công, thời gian LOGIN_RESULT, JOIN đến EXAM_STARTED |
| Lưu đáp án | Các mức tải trên, giữ tần suất và cỡ đề cố định | Số update gửi/đã lưu, thời gian xử lý quan sát được, lỗi và queue |
| Nộp đồng thời | Nhiều Client nộp trong cùng khoảng thời gian | Thời gian SUBMIT_RESULT, bài nộp duy nhất, kết quả đúng |
| Phục hồi | Dừng cưỡng bức Server thử nghiệm rồi chạy lại khi còn hạn | Thời gian đăng nhập/tiếp tục, giữ mã/deadline/đáp án đã lưu; người chưa vào cũng nhận offer |
| Cảnh báo | Rời dưới 5 giây; rời 5–10 giây; ở ngoài kéo dài; quay lại rồi rời tiếp | Số sự kiện, thời gian Server hiện thông báo, không đếm trùng; mở hộp thoại ứng dụng không tính |
| Lỗi mạng/Client chậm | Ngắt Client thử nghiệm, Server không chạy, writer chậm | Thông báo lỗi, Client còn lại tiếp tục, kết quả lưu đúng |

## Cách đo

- Đo thời gian request–response bằng đồng hồ đơn điệu trên cùng Client, không lấy hiệu đồng hồ tường của hai máy chưa đồng bộ.
- Response time đăng nhập: gửi LOGIN đến nhận LOGIN_RESULT. Nhận đề: gửi JOIN_EXAM đến nhận EXAM_STARTED. Nộp: gửi SUBMIT_EXAM đến nhận SUBMIT_RESULT.
- ANSWER_UPDATE chưa có ACK riêng. Không dùng thời điểm `send()` xong để khẳng định đã lưu. Muốn đo lưu bài cần instrument log Server hoặc dùng một hàng rào PING/PONG sau update và nêu rõ phép đo gồm cả PING/PONG; đối chiếu database.
- Throughput = số tác vụ hoàn thành thành công / khoảng đo, ghi rõ loại tác vụ. Error rate = số thất bại / tổng tác vụ thử. Báo riêng timeout và loại lỗi.
- Báo median/p95 và số mẫu, có warm-up tách riêng; lặp mỗi cấu hình ít nhất 3 lần nếu điều kiện cho phép. Không bỏ kết quả xấu không giải thích.
- Cảnh báo có kiểm tra định kỳ Client và chu kỳ cập nhật UI Server; ngưỡng 5 giây không đồng nghĩa toast phải hiện chính xác ở 5.000 giây. Ghi thời gian quan sát thực tế và sai số.
- Thử nghiệm phục hồi cưỡng bức chỉ trên tiến trình/CSDL thử nghiệm tách riêng, không dừng Server đang phục vụ bài thi.

## Baseline và phân tích

Chọn biến thể baseline có thể tái lập trong harness thử nghiệm riêng nếu so sánh định lượng: không lưu snapshot so với có lưu; chấm ngay theo giờ xử lý so với chờ request nhận trước hạn. Nếu không chạy baseline thì chỉ báo cáo kiểm chứng tình huống và giới hạn, không tự tạo cột số liệu baseline.

Lưu raw CSV/JSON có run_id, cấu hình và metric; kèm log/commit/lệnh chạy. Bảng kết quả cần nêu số Client thực, số tác vụ, thời gian đo, median/p95, throughput và lỗi. Giải thích ảnh hưởng DB, giới hạn request, tần suất gửi và cấu hình LAN. Không kết luận tăng tải vô hạn từ một lần test 80 kết nối.
