# Client Windows

[Tải LANExamClient.zip](https://github.com/nguyenhien18/LTM/raw/refs/heads/main/downloads/LANExamClient.zip)

Gói này là bản sao của `dist/warning-5s/LANExamClient.zip`, chứa `LANExamClient.exe` dành cho Windows 64 bit. EXE có sẵn Java runtime và JavaFX; máy sinh viên không cần cài JDK, Maven hoặc MySQL.

## Cách chạy

1. Tải ZIP và giải nén vào một thư mục trên máy sinh viên.
2. Mở `LANExamClient.exe`. Lần đầu ứng dụng tự chuẩn bị runtime trong thư mục dữ liệu của người dùng.
3. Giảng viên đăng nhập và mở Server từ mã nguồn theo [hướng dẫn](../README.md#chạy-server).
4. Nhập IP Server, cổng 5000, mã sinh viên và ngày sinh đúng danh sách đã nhập.
5. Nếu chạy hai chương trình cùng máy, dùng `127.0.0.1`. Nếu chạy khác máy, dùng IP LAN của máy giảng viên.

Bản Client này hỗ trợ toàn màn hình và cảnh báo rời cửa sổ liên tục từ 5 giây. Server quản lý ca thi và lưu kết quả; tải EXE không thay thế bước chạy Server.

## Kiểm tra tệp tải về

ZIP khoảng 41,3 MiB. Mã SHA-256 nằm trong [LANExamClient.zip.sha256](LANExamClient.zip.sha256). Có thể tính lại bằng PowerShell:

```powershell
Get-FileHash -LiteralPath .\LANExamClient.zip -Algorithm SHA256
```

SHA-256 của EXE sau giải nén:

```text
12caa8a9c535050454c67db2402bef16318833ffab3a3d862c26e6b1dc9d6459
```
