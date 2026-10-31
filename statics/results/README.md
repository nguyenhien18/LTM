# Kết quả và bằng chứng

## Đã có bằng chứng

Nguồn `full-tests-2026-10-31.txt` là bản sao nguyên trạng log Maven được tạo ngày 31/10/2026, trước lần bổ sung tài liệu này. Không phải kết quả chạy lại sau khi sắp xếp thư mục.

| Chỉ số kiểm thử | Giá trị từ log |
|---|---|
| Tests run | 141 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 0 |
| Maven build | BUILD SUCCESS |
| Thời gian toàn lệnh | 01:34 min |

Lệnh đã dùng: `mvn "-Dlanexam.uiTests=true" test`. Kiểm thử dùng H2 riêng, TCP loopback và JavaFX. Test `eightyStudentsCanLoginAndSubmitWhileAllConnectionsStayOpen` kiểm tra đăng nhập, tham gia/lưu bài và nộp cho 80 kết nối; đây là test tính đúng đắn, không phải chứng minh công suất tối đa hoặc 80 máy LAN.

## Chạy phép đo loopback

`ReportBenchmarkTest` là test opt-in, chạy riêng khi bật `lanexam.benchmark=true`. Test đo TCP loopback/H2 ở 5 mức 1/10/20/40/80 Client, 3 lượt mỗi mức; mỗi Client nhận 30 câu, gửi 5 cập nhật rồi nộp. Hướng dẫn chạy ở README gốc; mẫu đo được tạo trong `output/report-final/experiment/` và tổng hợp bằng `tools/analyze_report_benchmark.py`.

Dữ liệu đo và báo cáo hiện được giữ cục bộ. Chưa có benchmark MySQL qua LAN nhiều máy hoặc CPU/RAM theo thời gian; không dùng thời gian 01:34 của Maven làm latency ứng dụng. `EXPERIMENT_PLAN.md` là kế hoạch mở rộng phép đo.
