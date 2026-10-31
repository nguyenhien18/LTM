# Tài nguyên báo cáo và demo

- `architecture.png` / `.svg`: các thành phần và cơ chế xử lý Server hiện hành.
- `protocol.png` / `.svg`: trình tự giao tiếp sinh viên–Server khi thi.
- `PROTOCOL.md`: framing, hướng message, ví dụ và lỗi.
- `dataset/`: dữ liệu demo tổng hợp, không phải danh sách sinh viên thật.
- `results/`: bằng chứng kiểm thử đã có và kế hoạch mở rộng đo hiệu năng.

SVG là bản vector có thể chỉnh sửa; PNG phục vụ chèn báo cáo. Xuất lại PNG bằng `node tools/render-diagrams.cjs` từ thư mục gốc khi có Node.js và package `sharp` trong đường dẫn module. Đây chỉ là công cụ tài liệu, không phải dependency chạy ứng dụng Java.
