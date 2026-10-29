# LAN Exam System — BTL Lập trình mạng

Hệ thống thi trắc nghiệm trong phòng máy theo mô hình **một Server giảng viên – nhiều Client sinh viên**, dùng **Java 21, JavaFX, TCP Socket, JSON Lines và MySQL**.

Client chỉ kết nối TCP tới Server. MySQL đặt ở máy giảng viên; Client không truy cập database và không nhận đáp án đúng. Sau khi chuẩn bị thư viện, ứng dụng có thể thi trong LAN không có Internet.

## Chức năng

| Server giảng viên | Client sinh viên |
|---|---|
| Đăng ký/đăng nhập, đổi mật khẩu, dữ liệu riêng từng giảng viên | Kết nối IP/cổng, đăng nhập bằng MSV và ngày sinh |
| Tạo môn, quản lý nhiều lớp, nhập danh sách CSV có xem trước | Phòng chờ, chỉ nhận ca đúng danh sách |
| Sinh viên thuộc nhiều lớp, STT riêng từng lớp theo CSV | Tham gia/tiếp tục đúng lượt, không tự chọn lớp hoặc mã đề |
| Ngân hàng câu hỏi mức 1–6, tạo/sửa và nhập Word nhiều dòng | Làm trắc nghiệm một đáp án A–D, điều hướng và đánh dấu xem lại |
| Tạo đề thủ công hoặc lấy câu ngẫu nhiên theo ma trận mức | Đồng hồ theo hạn chung, vào muộn không cộng giờ |
| Chia ca theo STT gốc, chọn đề cùng môn với lớp | Gửi lựa chọn khi thay đổi, khôi phục dữ liệu Server đã lưu |
| Tạo 1–8 mã, đảo câu/lựa chọn và phân cân bằng | Nộp sớm, nhắc câu bỏ trống, tự gửi nộp khi hết giờ |
| Theo dõi kết nối và tiến độ từng ca, cảnh báo/khóa/mở khóa | Nhận cảnh báo/khóa từ giảng viên |
| Tự chấm cả lượt offline, lọc lớp/lượt và xuất điểm CSV | Xem điểm và lịch sử cá nhân |

Một Server phục vụ **một giảng viên và một ca đang diễn ra tại một thời điểm**. Các lớp/ca thi được tổ chức lần lượt, kết quả mỗi lượt được giữ riêng. Tài khoản giảng viên mới bắt đầu trống, không tự tạo sinh viên hoặc đề demo.

## Yêu cầu môi trường

- JDK **21** và Maven **3.9+** trên máy chạy từ mã nguồn.
- MySQL **8+** trên máy Server; tài khoản có quyền tạo/cập nhật schema `lan_exam`.
- Các máy kết nối được IP/cổng Server trong LAN; cho phép Java nhận TCP cổng **5000** qua firewall.
- Lần build đầu cần tải thư viện Maven. Sau khi có đủ thư viện/runtime, lúc thi không cần Internet.

Repository chỉ chứa mã nguồn, kiểm thử, tài nguyên cần cho ứng dụng và cấu hình chạy. Báo cáo riêng, thư mục đóng gói, EXE/ZIP và dữ liệu build không được đưa vào repository.

## Chạy Server

```powershell
git clone https://github.com/nguyenhien18/LTM.git
cd LTM

$env:DB_USER = "root"
$env:DB_PASSWORD = "mat_khau_MySQL_thuc_te"
.\run-server.bat
```

Hoặc dùng Maven trực tiếp:

```powershell
mvn javafx:run -Pserver
```

URL mặc định: `jdbc:mysql://localhost:3306/lan_exam` với các tùy chọn kết nối trong `JpaUtil`. Có thể đặt `DB_URL` để dùng cấu hình khác. Hibernate hiện dùng `update` để bổ sung bảng/cột; sao lưu database thật trước khi cập nhật phiên bản.

Nếu muốn dùng MySQL Docker thay cho MySQL đã cài:

```powershell
docker compose up -d
$env:DB_USER = "root"
$env:DB_PASSWORD = "root"
.\run-server.bat
```

`root/root` là cấu hình demo trong `docker-compose.yml`. Chọn một MySQL đang dùng cổng 3306, tránh chạy đồng thời hai dịch vụ cùng cổng. Không thay mật khẩu MySQL đã dùng thành công bằng giá trị minh họa.

## Chạy Client

Mở terminal khác trên cùng máy hoặc tải mã nguồn về máy sinh viên:

```powershell
.\run-client.bat
```

Hoặc:

```powershell
mvn javafx:run -Pclient
```

Nhập IP của máy giảng viên, cổng 5000, MSV và ngày sinh đúng danh sách CSV. `127.0.0.1` chỉ dùng khi Client và Server chạy cùng máy. Client không cần MySQL hoặc các biến `DB_*`.

## Quy trình tổ chức thi

1. Tạo/đăng nhập tài khoản giảng viên.
2. **Lớp & sinh viên:** tạo môn và lớp học phần, nhập CSV, kiểm tra xem trước rồi xác nhận.
3. **Ngân hàng câu hỏi:** chọn môn, soạn câu hoặc nhập `.docx`.
4. **Tổ chức thi → Đề thi:** tạo đề mẫu, chọn thủ công hoặc nhập số câu từng mức để lấy ngẫu nhiên; kiểm tra rồi lưu.
5. Khởi động TCP Server để sinh viên kết nối vào phòng chờ.
6. **Tổ chức thi → Ca thi:** chọn lớp; nếu cần chia ca thì đặt số người/ca và bấm **Chia ca theo STT**. Chọn ca, đề, số mã rồi **Chuẩn bị ca thi**.
7. Kiểm tra danh sách/kết nối rồi bấm **Bắt đầu thi**. Đây là lúc đồng hồ chung bắt đầu; chuẩn bị chưa tính giờ.
8. Theo dõi, chờ nộp/hết giờ, vào **Kết quả** để lọc và xuất CSV. Chờ ca trước hết hạn và chấm xong mới mở ca tiếp theo.

Ví dụ 60 sinh viên STT 1–60, 30 người/ca: ca 1 gồm STT 1–30, ca 2 gồm STT 31–60. Hệ thống sắp theo STT CSV tăng dần; khoảng trống STT được giữ nguyên. Danh sách ca được chốt trong database; nhập lại CSV không tự thay danh sách ca đã chia. Có thể chia lại trước khi đợt bắt đầu; đợt đã bắt đầu cần hoàn tất các ca trước khi tạo đợt mới. Nếu chưa chia ca có thể thi cả lớp.

## CSV sinh viên

Tải CSV mẫu ngay trong ứng dụng hoặc tạo tệp UTF-8:

```csv
STT,MSV,Họ và tên,Ngày sinh,Lớp
1,B23DCCN011,Bùi Thế Anh,07/11/2005,D23CNPM05
2,B23DCCN019,Hoàng Đức Anh,17/03/2005,D23CNPM03
```

- Hỗ trợ BOM, dấu phẩy/chấm phẩy, ngày `dd/MM/yyyy` hoặc `yyyy-MM-dd`; tối đa 2 MB/2.000 sinh viên mỗi lần nhập.
- Cột **Lớp** là lớp chính quy; danh sách được nhập vào lớp học phần đang chọn, không tự tạo lớp từ cột này.
- MSV chưa có thì tạo hồ sơ; đã có và khớp thông tin thì dùng lại/thêm vào lớp. Không tạo nhiều tài khoản cho cùng sinh viên.
- STT lưu riêng theo lớp, phải dương và không trùng khi có giá trị. Nhập lại CSV có STT sẽ cập nhật theo MSV; không đánh số theo mã sinh viên hoặc vị trí hiển thị.
- Nếu STT hiện **—**, dùng **Bổ sung STT từ CSV**. Mẫu ba cột cũ/STT trống vẫn nhập được nhưng cần bổ sung đủ STT trước khi chia ca.
- Lỗi cấu trúc, trùng MSV hoặc xung đột họ tên/ngày sinh/lớp chính quy bị chặn; không nhập dở dang hoặc ghi đè hồ sơ dùng chung.

## Nhập câu hỏi từ Word

Chọn môn → **Tải Word mẫu** → soạn nội dung → **Nhập Word** → xem trước → xác nhận. Mẫu dùng bởi ứng dụng có tại [question-bank-template.docx](src/main/resources/templates/question-bank-template.docx).

```text
MỨC 1
Câu 1: Giao thức nào truyền dữ liệu tin cậy?
A. UDP
B. TCP
C. IP
D. ICMP
Đáp án: B
```

Mỗi tiêu đề MỨC 1–6 áp dụng cho các câu phía dưới. Nội dung câu và lựa chọn có thể xuống nhiều dòng; nhãn phải là văn bản như mẫu. Chỉ nhận `.docx`, tối đa 5 MB/500 câu, một đáp án A–D; ảnh, bảng, công thức Word, Track Changes và nhãn tự động không được hỗ trợ. Preview/hủy chưa ghi dữ liệu; câu trùng hoàn toàn trong cùng môn được bỏ qua.

## Đề thi, mã đề và khôi phục

- Tạo đề nhanh lấy đúng số câu từng mức, tổng 1–200, không trùng. Đổi một câu lấy câu khác cùng mức; thiếu ngân hàng thì giữ danh sách đang soạn và báo lỗi.
- Một mã dùng thứ tự gốc; 2–8 mã là 101–108, dùng cùng bộ nội dung nhưng đảo câu/lựa chọn. Phân mã ngẫu nhiên cân bằng, không đảm bảo hai máy ngồi cạnh khác mã.
- Nhận diện một số câu tham chiếu A/B/“tất cả ở trên” để giữ thứ tự lựa chọn; không bảo đảm hiểu mọi cách diễn đạt. Nên soạn câu độc lập, không phụ thuộc số câu khác.
- Đề và lượt thi lưu bản sao câu hỏi, nên sửa ngân hàng không đổi đề/điểm cũ. Mã/bố cục/đáp án đã lưu giữ nguyên khi vào lại.
- Mỗi thay đổi gửi `ANSWER_UPDATE`. Server lưu lựa chọn hợp lệ; lựa chọn chưa được Server lưu không được bảo đảm khôi phục. Ngắt mạng không dừng đồng hồ.
- Hết giờ Server chấm cả lượt offline. Yêu cầu nhận trước hạn vẫn được xét nếu phải chờ xử lý; nhận sau hạn không sửa kết quả. Nộp lặp không tạo thêm điểm.
- Sau sự cố, đăng nhập đúng giảng viên và khởi động TCP Server: ca còn hạn tự khôi phục, cả người đã vào và chưa vào bài đều được nhận ca đúng quyền.
- Bảng theo dõi tách **Kết nối** Online/Offline khỏi **Bài thi của ca này** đọc từ database, cập nhật khoảng 2 giây. Đã nộp vẫn giữ khi offline; chưa từng vào đến hết hạn là Vắng thi, không tự tạo điểm 0.
- Dừng/đóng/đổi giảng viên bị chặn khi ca còn hạn hoặc còn lượt đang hoàn tất chấm/lưu.

## Kiến trúc và giao thức

```text
Client JavaFX ── TCP / JSON Lines ── Server JavaFX ── Hibernate/JPA ── MySQL
```

| Thành phần | Vai trò |
|---|---|
| `client`, `client/network` | Giao diện sinh viên, socket đọc/ghi và thông báo mất kết nối |
| `common`, `common/dto`, `common/ui` | Message/JSON, DTO, giới hạn frame, giao diện dùng chung |
| `server` | Quản lý lớp, ngân hàng, đề mẫu, ca và kết quả |
| `server/network` | Accept, phiên hiện hành, FIFO mỗi Client, hàng đợi gửi và timeout |
| `server/service`, `entity`, `persistence` | Nghiệp vụ, phân quyền, dữ liệu và cấu hình MySQL |
| `server/legacy/entity` | Hai mapping lịch sử thực hành, không còn UI/service/API thực hành |

Mỗi message là một dòng JSON UTF-8 `{type,payload}`. Luồng chính: `LOGIN` → `LOGIN_RESULT` → `EXAM_AVAILABLE` → `JOIN_EXAM` → `EXAM_STARTED` → `ANSWER_UPDATE` / `SUBMIT_EXAM` → `SUBMIT_RESULT`. Có `HISTORY_REQUEST/RESULT`, `PING/PONG`, cảnh báo/khóa và `ERROR`.

Server dùng virtual thread, FIFO cho từng kết nối và giới hạn 16 yêu cầu xử lý đồng thời; outbox và timeout ghi riêng. Client gửi heartbeat mỗi 5 giây, timeout đọc 20 giây. Một MSV có một phiên hiện hành; đăng nhập mới thay phiên cũ.

CSDL có 13 bảng phục vụ thi: tài khoản, môn/lớp/thành viên, ngân hàng, đề/câu chốt, ca/thành viên ca, lượt làm, bài nộp và đáp án. Hai bảng thực hành cũ được giữ để đọc lịch sử; ứng dụng không xóa dữ liệu cũ hoặc tự gán dữ liệu chưa có chủ cho giảng viên mới.

## Kiểm thử

```powershell
mvn test
mvn "-Dlanexam.uiTests=true" test
```

Test dùng H2 riêng, TCP cổng tạm và JavaFX, không cần MySQL thật. `ExamWorkflowUiTest` kiểm tra giao diện/luồng thi; các test service kiểm tra CSV/STT, Word, chia ca, ma trận/mã đề, phân quyền, nộp trùng, deadline và khôi phục. `LegacyDataCompatibilityTest` kiểm tra khả năng đọc lịch sử. Dữ liệu kiểm thử nằm trong `src/test/resources`.

Có test 80 socket cùng tồn tại trên loopback; kết quả đó không thay thế thử nghiệm nhiều máy thật trong phòng lab. Cần kiểm tra IP/firewall/mất mạng và MySQL trên môi trường demo.

## Giới hạn

Xác thực sinh viên bằng ngày sinh còn yếu và TCP chưa TLS; phù hợp BTL/demo trên LAN tin cậy. Cảnh báo do giảng viên gửi thủ công, không giám sát màn hình. Chưa có thi ngoại tuyến, tự reconnect, ACK riêng từng lần lưu, gia hạn cá nhân hoặc kết thúc sớm. Khóa sinh viên có phạm vi toàn tài khoản trên database.
