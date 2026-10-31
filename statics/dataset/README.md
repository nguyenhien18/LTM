# Dữ liệu demo

Đây là dữ liệu tổng hợp phục vụ kiểm thử/demo, không phải dữ liệu sinh viên thật.

| File | Nội dung |
|---|---|
| sinh-vien-nhom-01-60.csv | 60 sinh viên, STT 1–60, MSV B23DCCN201–B23DCCN260 |
| sinh-vien-nhom-02-60.csv | 60 sinh viên, STT 1–60, MSV B23DCCN261–B23DCCN320 |
| question-bank-template.docx | Word mẫu chính ứng dụng đang sử dụng |
| Co_so_du_lieu_200_cau.docx | Bộ 200 câu demo Cơ sở dữ liệu |
| Lap_trinh_mang_200_cau.docx | Bộ 200 câu demo Lập trình mạng |
| Lap_trinh_web_200_cau.docx | Bộ 200 câu demo Lập trình web |

CSV có cột `STT,MSV,Họ và tên,Ngày sinh,Lớp`; ngày sinh dd/MM/yyyy. Mật khẩu đăng nhập sinh viên chính là ngày sinh đang lưu, không có tài khoản sinh viên cố định được seed sẵn. Ví dụ nhóm 01 có B23DCCN201 / 01/01/2004 khi nhập lần đầu vào database sạch.

Tạo môn/lớp rồi nhập CSV qua giao diện Server. Cùng CSV có thể thêm vào nhiều lớp; MSV đã tồn tại giữ hồ sơ/ngày sinh cũ dù CSV khác, không cập nhật mật khẩu từ file. CSV không chứa quan hệ ca thi; chia ca sau khi nhập lớp.

Chọn đúng môn trong ngân hàng rồi nhập Word, xem trước và xác nhận. Các bộ câu hỏi này đã có từ lần tạo dữ liệu trước; cần giảng viên/nhóm rà lại kiến thức và độ khó trước khi dùng thi thật, không coi nhãn mức là kết quả hiệu chuẩn độ khó. Không thêm file vào database bằng chép vào thư mục.
