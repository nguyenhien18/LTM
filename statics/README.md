# Hình và tài liệu của dự án

Báo cáo Word của nhóm nằm tại [report](../report/README.md). Các hình và dữ liệu dưới đây được lưu riêng để có thể xem, tải và đối chiếu với mã nguồn.

| Nội dung | Hình PNG | Nguồn SVG |
|---|---|---|
| Kiến trúc Client–Server | [architecture.png](architecture.png) | [architecture.svg](architecture.svg) |
| Trình tự TCP và lưu bài | [protocol.png](protocol.png) | [protocol.svg](protocol.svg) |
| Lớp, sinh viên và khóa theo giảng viên | [class-students.png](class-students.png) | [class-students.svg](class-students.svg) |
| Lượt thi, câu hỏi và kết quả | [exam-data.png](exam-data.png) | [exam-data.svg](exam-data.svg) |
| Trạng thái bài thi | [exam-states.png](exam-states.png) | [exam-states.svg](exam-states.svg) |

Các hình dùng nền trắng và đường nối đơn giản. Sơ đồ TCP đặt bước tính điểm tại Server; MySQL lưu dữ liệu trong giao dịch. Sơ đồ trạng thái có bước **Đang chấm**, tách khỏi Online/Offline. Sơ đồ dữ liệu chỉ liệt kê các cột chính.

- [PROTOCOL.md](PROTOCOL.md): cấu trúc frame, các message, thứ tự và lỗi.
- [DATA_MODEL.md](DATA_MODEL.md): quan hệ dữ liệu, danh sách ca, mã đề và cách tính điểm.
- [REFERENCES.md](REFERENCES.md): tài liệu kỹ thuật chính thức.
- [protocol.puml](protocol.puml): nguồn PlantUML của luồng giao tiếp, có thể chỉnh sửa và xuất bằng PlantUML.
- [dataset](dataset/README.md): dữ liệu demo tổng hợp.
- [results](results/README.md): log kiểm thử, số đo thô, bảng tổng hợp và biểu đồ.

SVG là nguồn vector chỉnh sửa trực tiếp. Xuất lại năm PNG bằng `node tools/render-diagrams.cjs` từ thư mục gốc khi có Node.js và package `sharp`. PlantUML và SVG là hai bản nguồn riêng; khi đổi luồng TCP cần cập nhật cả hai. Những công cụ này chỉ phục vụ tài liệu, không phải điều kiện chạy ứng dụng Java.
