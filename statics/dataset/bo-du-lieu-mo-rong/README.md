# Bộ dữ liệu mở rộng cho LAN Exam System

Dữ liệu sinh viên hoàn toàn giả lập để nhập thử và demo. Bộ câu hỏi dùng cho thực hành, kiểm tra nhập dữ liệu và soạn đề; các mức 1–6 là phân loại gợi ý, chưa được hiệu chuẩn bằng kết quả thi thực tế. Có các bài cùng dạng với tham số khác nhau.

| Môn học | Sinh viên | Câu hỏi |
|---|---:|---:|
| Cơ sở dữ liệu | [60](01_Co_so_du_lieu_60_sinh_vien.csv) | [240](01_Co_so_du_lieu_240_cau.docx) |
| Lập trình mạng | [64](02_Lap_trinh_mang_64_sinh_vien.csv) | [260](02_Lap_trinh_mang_260_cau.docx) |
| Lập trình web | [68](03_Lap_trinh_web_68_sinh_vien.csv) | [220](03_Lap_trinh_web_220_cau.docx) |
| Cấu trúc dữ liệu và giải thuật | [72](04_Cau_truc_du_lieu_va_giai_thuat_72_sinh_vien.csv) | [280](04_Cau_truc_du_lieu_va_giai_thuat_280_cau.docx) |
| Hệ điều hành | [76](05_He_dieu_hanh_76_sinh_vien.csv) | [250](05_He_dieu_hanh_250_cau.docx) |
| Lập trình hướng đối tượng | [80](06_Lap_trinh_huong_doi_tuong_80_sinh_vien.csv) | [230](06_Lap_trinh_huong_doi_tuong_230_cau.docx) |
| Công nghệ phần mềm | [62](07_Cong_nghe_phan_mem_62_sinh_vien.csv) | [200](07_Cong_nghe_phan_mem_200_cau.docx) |
| An toàn thông tin | [66](08_An_toan_thong_tin_66_sinh_vien.csv) | [210](08_An_toan_thong_tin_210_cau.docx) |
| Kiến trúc máy tính | [74](09_Kien_truc_may_tinh_74_sinh_vien.csv) | [270](09_Kien_truc_may_tinh_270_cau.docx) |
| Toán rời rạc | [78](10_Toan_roi_rac_78_sinh_vien.csv) | [300](10_Toan_roi_rac_300_cau.docx) |

Tổng cộng 10 CSV, 700 sinh viên và 10 Word, 2.460 câu hỏi.

## Nhập danh sách sinh viên

- CSV UTF-8 có BOM, đúng 5 cột: `STT,MSV,Họ và tên,Ngày sinh,Lớp`. Ngày sinh theo `dd/MM/yyyy`. STT bắt đầu 1 và tăng liên tục trong mỗi file.
- MSV từ B24DCCN5001 đến B24DCCN5700, không trùng giữa 10 file hoặc hai CSV B23 cũ. Lớp trong CSV là lớp chính quy; bạn chọn lớp học phần trên Server trước khi nhập.
- Sinh viên đăng nhập bằng MSV và ngày sinh trong hồ sơ đã lưu. Nếu MSV đã tồn tại từ lần nhập khác, hệ thống giữ hồ sơ cũ.
- Mỗi danh sách có thể nhập vào nhiều lớp học phần để thử sinh viên học nhiều lớp. Không cần tạo lại tài khoản.

## Nhập câu hỏi Word

- Tạo/chọn đúng môn trong Ngân hàng câu hỏi, chọn Nhập Word, xem trước và xác nhận. Mỗi tệp có 200–300 câu, nằm trong giới hạn 500 câu/tệp của ứng dụng.
- Mẫu: `MỨC 1`, `Câu 1: ...`, `A. ...` đến `D. ...`, `Đáp án: B`. Chỉ có một đáp án đúng; vị trí A–D được phân bố cân bằng trong từng file.
- Chỉ sử dụng văn bản thường. Công thức được viết bằng ký tự, không dùng ảnh, bảng, công thức Word hoặc đánh số tự động.
- Ba môn Cơ sở dữ liệu, Lập trình mạng và Lập trình web có dùng lại một phần câu lý thuyết của bộ 200 câu trước và bổ sung bài/biến thể khác. Nếu đã nhập bộ cũ vào cùng môn, những câu trùng hoàn toàn được ứng dụng bỏ qua; số thêm mới có thể thấp hơn số câu trong tệp.

## Kiểm tra đã thực hiện

- Bộ đọc thật `QuestionWordImport.read` đọc đủ 2.460 câu, không báo lỗi cấu trúc; bộ đọc `RosterCsv.read` đọc đủ 700 sinh viên.
- Mỗi file có đủ 6 mức; không trùng nội dung câu hỏi trong cùng file; bốn lựa chọn không trùng nhau.
- Chạy 408 trường hợp Java và 180 trường hợp JavaScript của trình tạo để kiểm tra đáp án mã nguồn. Các bài tính toán được tạo theo công thức hoặc mô phỏng xác định.
- Kiểm tra bố cục bản render và kiểm tra tự động không có khối câu hỏi bị cắt sang trang khác hoặc văn bản vượt lề.
- Không phải ngân hàng đề đã được giảng viên thẩm định. Nên xem nội dung và điều chỉnh độ khó trước khi dùng để chấm thi thật.

## Tài liệu tham khảo kiến thức

- Java và hướng đối tượng: https://docs.oracle.com/javase/tutorial/java/concepts/
- Hệ điều hành OSTEP: https://pages.cs.wisc.edu/~remzi/OSTEP/
- Toán cho khoa học máy tính MIT: https://ocw.mit.edu/courses/6-042j-mathematics-for-computer-science-spring-2015/
- An toàn ứng dụng OWASP: https://owasp.org/projects/top-ten
- Các file là câu hỏi biên soạn cho bộ dữ liệu demo; không phải bộ đề chính thức của các đơn vị trên.
