# Client Windows chạy từ một file EXE

## File gửi cho học viên

`dist/LANExamClient.exe` là file duy nhất cần gửi. Bản này dành cho Windows 10/11 64 bit.
Học viên mở EXE, ứng dụng hiện giao diện đăng nhập; nhập IP Server, cổng, mã sinh viên và ngày sinh như khi chạy Client từ Maven.

Lần đầu EXE tự chuẩn bị Java runtime và JavaFX trong `%LOCALAPPDATA%\LANExamClient\runtime`.
Các lần mở tiếp theo dùng lại bản đã chuẩn bị. Không có bước cài đặt hoặc yêu cầu quyền quản trị; không cần Java/Maven trên máy nhận.
Mỗi bản phát hành có thư mục runtime theo hash riêng, nên bản mới không ghi đè runtime của bản cũ đang chạy.

## Tạo lại sau khi sửa Client

Chạy `build-client-exe.bat` trong thư mục dự án, hoặc dùng PowerShell:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\packaging\windows\Build-ClientExe.ps1
```

Máy đóng gói cần JDK 21+ có `jlink`/`jpackage`, Maven và C# compiler của .NET Framework đi kèm Windows.
Script tìm Maven trong PATH hoặc bộ nhớ đệm Maven Wrapper. Có thể truyền `-JdkHome` hoặc `-MavenRepository` nếu dùng đường dẫn khác mặc định.

Kết quả:

- `dist/LANExamClient.exe`: ứng dụng tự giải nén, kèm runtime.
- `dist/LANExamClient.exe.sha256`: mã kiểm tra của file phát hành.
- `target/client-exe/<lần đóng gói>/build-info.json`: thông tin và thư mục trung gian.

Chỉ các class `client`, `common`, JavaFX và Jackson được đưa vào Client. Không kèm lớp Server, driver MySQL, Hibernate, cấu hình database hoặc dữ liệu tài khoản giáo viên.
Các file bản quyền trong runtime/thư viện được giữ nguyên trong gói.

## Kiểm tra EXE

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\packaging\windows\Test-ClientExe.ps1
```

Lệnh mở một Client kiểm thử với `JAVA_HOME` trống và PATH không chứa Java, xác nhận cửa sổ đăng nhập xuất hiện,
sau đó đóng đúng cửa sổ vừa tạo. Kết quả được lưu ở `target/portable-client-smoke.json`.

Ứng dụng Java được đóng gói bằng [`jpackage` với runtime riêng](https://docs.oracle.com/en/java/javase/21/jpackage/packaging-tool-user-guide.pdf);
file phát hành dùng bộ mở Windows có nhúng payload qua [C# compiler](https://learn.microsoft.com/en-us/visualstudio/msbuild/csc-task?view=visualstudio).
