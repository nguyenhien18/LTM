# Kịch bản test trong khoảng 2 giờ

Không cần để một bài thi chạy đủ 2 giờ. Mục tiêu là trong buổi test có thể kiểm tra toàn bộ luồng mạng và chức năng chính.

| TC | Nội dung | Kết quả mong đợi |
|---|---|---|
| TC01 | Tạo/đăng nhập tài khoản giáo viên rồi khởi động Server | Hiện họ tên/tên đăng nhập đúng; Server lắng nghe port 5000 |
| TC02 | Client A kết nối | Server nhận socket |
| TC03 | Login sai | Client nhận thông báo thất bại |
| TC04 | Login đúng | Server hiện MSV + IP + ONLINE |
| TC05 | Client B login | Server quản lý đồng thời 2 Client |
| TC06 | Server mở danh sách đề, chọn một bài thi | Cả 2 Client thấy thông tin đúng đề đã chọn; chưa tính giờ khi chưa tham gia |
| TC06a | Client bấm Tham gia bài thi | Nhận câu hỏi và thời gian còn lại tính từ lúc giảng viên mở đề; Server hiện DOING_EXAM |
| TC07 | Client làm bài | Giao diện lưu lựa chọn hiện tại |
| TC08 | Client A nộp | Server lưu bài, chấm điểm, status SUBMITTED |
| TC09 | Client nhận điểm | Hiện score và số câu đúng |
| TC10 | Hết giờ | Client tự động gửi SUBMIT_EXAM |
| TC11 | Đóng Client | Server loại socket khỏi danh sách online |
| TC12 | Cho 80 Client đăng nhập, giữ toàn bộ kết nối rồi cùng tham gia, đổi đáp án và chờ hết giờ tự chấm | Tất cả nhận phản hồi đăng nhập; Server có 80 phiên và lưu đủ 80 kết quả, không phải đóng 8 Client đầu để các Client sau được xử lý |
| TC13 | Đóng Client giữa giờ rồi đăng nhập lại | Trang chính có Làm lại từ đầu; bấm vào nhận bài trống, hạn nộp không thay đổi |
| TC14 | Bấm Mở bài thi lần nữa | Không đặt lại hạn nộp của lượt thi cũ |
| TC15 | Gửi `SUBMIT_EXAM` hai lần | Chỉ có một dòng `submissions`, kết quả không đổi |
| TC16 | Gửi `SUBMIT_EXAM` trước khi tham gia | Server từ chối |
| TC17 | Mất mạng đến hết giờ | Server chấm lựa chọn đã nhận trong RAM; đăng nhập lại xem kết quả |
| TC18 | Giảng viên tạo môn và hai câu trắc nghiệm trong ngân hàng, sau đó tạo đề 2 phút từ hai câu đó | Đề xuất hiện trong danh sách với đúng môn; Client chỉ thấy đề sau khi giảng viên chọn mở |
| TC19 | Tạo đề mới khi còn sinh viên đang làm, rồi chọn mở đề mới | Tạo và lưu được; Server từ chối mở đề khác cho đến khi ca cũ hết giờ và chấm xong |
| TC20 | Mở Xem điểm sau khi nộp và sau khi Client thoát | Điểm, số câu đúng, tên đề và thời gian nộp vẫn hiện; Làm mới cập nhật bài mới |
| TC21 | Client xem điểm, bấm Về trang chính rồi Đổi tài khoản | Trang chính giữ kết nối và có lịch sử; đăng xuất trở lại trang đăng nhập, Server xóa phiên online cũ |
| TC22 | Đăng nhập lại bằng MSV vừa nộp | Trang chính hiện lịch sử điểm, không mời tham gia lại đề đã nộp |
| TC23 | Sau khi ca hết giờ và chấm xong, Server chọn mở lại chính đề đó | Client đã nộp thấy bài thi mới để tham gia; lịch sử vẫn có điểm đợt cũ |
| TC24 | Cùng sinh viên nộp bài ở đợt mở lại | Lịch sử Client và bảng điểm Server có hai dòng, mã lượt khác nhau; không ghi đè điểm cũ |
| TC25 | Server bấm Bài tập / Bài nộp và giao bài có tệp đề | Client online tự thấy bài trong tab Bài tập; Client đăng nhập sau cũng thấy |
| TC26 | Client tải đề, nộp nội dung kèm ZIP rồi đăng nhập lại | Nội dung, tên tệp, thời gian nộp vẫn hiện; tệp tải lại có nội dung đúng |
| TC27 | Client nộp lại trước hạn | Giảng viên chỉ thấy một dòng cho học viên, với nội dung và thời gian nộp mới |
| TC28 | Client sửa nội dung mà không chọn tệp mới, sau đó thử Bỏ tệp | Tệp cũ được giữ ở lần đầu; lần sau bỏ tệp nếu vẫn còn nội dung văn bản |
| TC29 | Đặt hạn gần nhất rồi nộp/nộp lại sau hạn | Server từ chối, bản nộp đã được chấp nhận trước đó vẫn nguyên vẹn |
| TC30 | Client B xem bài tập đã được Client A nộp | B không thấy nội dung hay tải được tệp bài nộp của A |
| TC31 | Chọn tệp trên 5 MB hoặc nộp rỗng | Có thông báo lỗi, không tạo hay ghi đè bản nộp |
| TC32 | Đang nhập bài tập thì giảng viên giao thêm bài mới | Danh sách cập nhật và nội dung đang soạn vẫn được giữ |
| TC33 | Lưu hai câu trắc nghiệm mức 1 và mức 6 vào cùng môn rồi mở lại ngân hàng | Môn, độ khó, nội dung, bốn lựa chọn và đáp án vẫn được lưu; không có lựa chọn dạng điền đáp án |
| TC34 | Chọn môn A rồi lọc mức 6 | Chỉ hiển thị câu trắc nghiệm đúng môn và mức; câu môn B không xuất hiện |
| TC35 | Tạo đề, thêm hai câu rồi đổi bộ lọc và thử thêm lại câu đã chọn | Câu đã thêm vẫn ở danh sách bên phải; không xuất hiện câu trùng |
| TC36 | Đổi môn khi đang có câu trong đề | Bỏ câu của môn trước và chỉ hiển thị ngân hàng của môn mới |
| TC37 | Bỏ câu hoặc dùng Lên/Xuống rồi lưu đề, mở cho Client | Đúng số câu và thứ tự; Client thấy tên môn và không nhận đáp án đúng |
| TC38 | Sửa nội dung, độ khó và đáp án trong ngân hàng sau khi đã lưu đề | Đề cũ và chấm điểm cũ vẫn dùng bản đã chốt; đề tạo mới dùng nội dung vừa sửa |
| TC39 | Thử nhập độ khó 0/7, môn trùng hoặc trắc nghiệm thiếu lựa chọn/đáp án | Hiện lỗi và không lưu dữ liệu không hợp lệ |
| TC40 | Nộp đề 1 rồi đăng nhập lại | Trạng thái ONLINE; điểm đề 1 vẫn có trong lịch sử |
| TC41 | Mở đề 2 sau khi ca đề 1 hết giờ và chấm xong | Trạng thái ONLINE, khi tham gia chuyển DOING_EXAM với tên đề 2; nộp chuyển SUBMITTED |
| TC42 | Giảng viên gửi cảnh báo cho Client A khi A và B cùng online | Chỉ A hiện đúng nội dung, đáp án đã chọn được giữ và thời gian thi vẫn chạy |
| TC43 | Giảng viên khóa tài khoản A trong lúc làm bài | A thấy lý do khóa, trở về đăng nhập; B vẫn dùng bình thường; A không đăng nhập lại được |
| TC44 | Khởi động lại Server rồi thử đăng nhập tài khoản bị khóa | Vẫn bị từ chối; mở khóa trong danh sách tài khoản bị khóa thì đăng nhập lại được |
| TC45 | Đổi đáp án, khóa tài khoản rồi chờ hết giờ | Server chấm lựa chọn đã nhận trong RAM và lưu điểm; lịch sử không bị xóa |
| TC46 | Tạo hai tài khoản giáo viên; nhập sai mật khẩu rồi nhập đúng | Sai thì ở màn hình đăng nhập; đúng thì hiện đúng tên tài khoản trên Server |
| TC47 | Đăng nhập giáo viên đầu tiên sau nâng cấp | Thấy lại môn, câu hỏi, đề, bài tập và kết quả cũ |
| TC48 | Giáo viên A tạo dữ liệu; đăng xuất, đăng nhập giáo viên B | B chỉ thấy dữ liệu của B; A đăng nhập lại vẫn có dữ liệu của mình |
| TC49 | Hai giáo viên tạo cùng tên môn | Mỗi tài khoản lưu được một môn riêng; trùng tên trong cùng tài khoản thì bị từ chối |
| TC50 | Đang thi, cho một Client mất mạng rồi thử đổi giáo viên/dừng Server | Đều bị chặn đến khi ca hết giờ và chấm xong; khởi động lại ứng dụng không bỏ qua điều kiện này |
| TC51 | Sau khi ca thi hết giờ và chấm xong, đổi giáo viên rồi cho Client kết nối lại | Client thấy đề/bài tập/lịch sử của giáo viên mới, không nhận dữ liệu của giáo viên trước |
| TC52 | Đổi mật khẩu với mật khẩu cũ sai rồi đúng; đăng nhập lại | Sai thì không đổi; đúng thì mật khẩu mới dùng được và mật khẩu cũ bị từ chối |

## Gợi ý demo nhanh

Để demo hết giờ nhanh, dùng **Tạo đề thi** trên Server và đặt thời lượng 2 phút, chọn đề trong **Chọn bài thi để mở**, rồi cho Client bấm **Tham gia bài thi**. Đề demo mặc định có thời lượng 20 phút.

## Các ca bổ sung cho giờ chung và đóng cửa sổ

- Mở đề 2 phút, sau 30 giây mới tham gia: chỉ còn khoảng 90 giây, không được đủ 2 phút.
- Học viên A chọn đáp án rồi mất mạng; vào lại A hoặc đổi sang B trên cùng Client: mọi câu đều trống, hạn chung không đổi.
- Bấm X Server khi một lượt còn hạn và offline: hiện lý do, giữ cửa sổ và kết nối Server. Chỉ khi ca hết giờ và chấm xong mới đóng được, kể cả mọi người đã nộp sớm.
- Học viên chưa tham gia đến sau hạn: không được vào bài cũ.
- Dùng Client EXE mới; bản cũ gửi SAVE_DRAFT bị yêu cầu cập nhật.

- Gửi đáp án đúng trước hạn nhưng giữ slot xử lý đến sau hạn: không chấm sớm; thả slot thì phải tính đáp án đúng, nhận sau hạn thì bỏ qua.
- Mở đề khi chưa ai tham gia hoặc mọi người đã nộp sớm: Dừng/X/Đổi giáo viên vẫn bị chặn đến hết ca. Mở lại cùng đề giữ nguyên hạn; đề khác bị từ chối.
- Ép mở đề và dừng đồng thời: mở trước thì dừng bị chặn; dừng trước thì không tạo đợt mới trong MySQL.
- Để Client trên trang chờ: số giây giảm dần, hết giờ nút bị khóa và không gửi JOIN_EXAM.
- Một Client không nhận dữ liệu: Client khác vẫn nhận điểm; sau 10 giây bị kẹt ghi thì chỉ kết nối chậm bị ngắt.

- Gửi liên tiếp hai lựa chọn trước hạn trong lúc toàn bộ slot xử lý bị giữ, sau đó ngắt Client: luồng đọc vẫn nhận đủ hai thông điệp, Server chấm lựa chọn cuối sau khi thả slot, không bỏ thông điệp đã nhận khi EOF.
