# Kiến trúc hệ thống thi trắc nghiệm LAN

## Thành phần

- JavaFX Server: đăng nhập giảng viên và bốn trang Lớp & sinh viên / Ngân hàng câu hỏi / Tổ chức thi / Kết quả.
- JavaFX Client: đăng nhập, phòng chờ, làm bài và lịch sử điểm; không có bước tự chọn lớp.
- TCP JSON Lines: ServerSocket/Socket, luồng ảo cho kết nối, luồng đọc và hàng đợi xử lý FIFO riêng mỗi Client.
- Hibernate/JPA + MySQL tại Server. Client không có driver/kết nối database.

## Danh tính, lớp và dữ liệu chung

`TeacherAccountService` tạo tài khoản trống, mật khẩu PBKDF2-HMAC-SHA256 với salt riêng. `TeacherScope` giới hạn môn, ngân hàng, đề, ca và kết quả theo giảng viên.

`CourseClass` có mã, tên, môn, học kỳ và chủ sở hữu. `ClassStudent` nối nhiều lớp với nhiều sinh viên, duy nhất `(class_id,student_id)`. `Student.studentCode` là định danh dùng chung trên cùng database. Ngày sinh dùng để xác thực theo danh sách CSV.

CSV nhà trường có năm cột STT / MSV / Họ và tên / Ngày sinh / Lớp; ngày theo dd/MM/yyyy và chấp nhận dấu phân cách phẩy hoặc chấm phẩy. `ClassStudent.csv_ordinal` nullable lưu đúng STT theo từng lớp; re-import có thể cập nhật STT, kể cả hoán đổi hai STT trong một transaction. Service khóa lớp và kiểm tra toàn bộ STT sau nhập trước khi ghi; không tự đánh lại số. `Student.regularClass` lưu lớp chính quy, tách biệt với `CourseClass` là lớp học phần/nhóm thi. Mẫu ba cột cũ vẫn được hỗ trợ; phải bổ sung STT để chia ca. Hồ sơ chưa có regularClass được bổ sung khi nhập; hồ sơ đã có lớp khác sẽ báo xung đột để tránh ghi đè thông tin dùng chung.

`RosterCsv` đọc UTF-8/BOM và cột có ngoặc kép/dấu phẩy. `ClassService.preview` chỉ đọc; `importRoster` kiểm tra lại và ghi nguyên transaction. Hồ sơ trùng mã nhưng khác tên/ngày sinh chặn cả lần nhập.

`ClassManager` chỉ quản lý danh sách lớp/sinh viên và trạng thái kết nối. Ngân hàng, đề và kết quả truy cập ở các trang riêng để giảm tầng điều hướng.

## Ngân hàng, đề mẫu và ca thi

- `Subject`, `BankQuestion`: câu trắc nghiệm theo môn, độ khó 1–6.
- `Exam` không có `templateExamId`: đề mẫu, dùng lại giữa các lớp.
- `ExamShiftService` chia theo `csv_ordinal` tăng dần, tối đa N sinh viên/ca (mặc định UI 30); chặn STT thiếu/trùng và lớp không thuộc giáo viên. Không lấy thứ tự dòng CSV, MSV hay thứ tự sắp bảng làm STT.
- Hai bảng `exam_shifts` và `exam_shift_students` lưu đợt (`batch_key`), số ca, sinh viên và STT tại thời điểm chia. Nhập CSV sau đó không đổi danh sách ca. Chưa bắt đầu có thể chia lại; đã bắt đầu phải hoàn tất cả đợt trước khi tạo đợt mới. Danh sách có lượt thi được giữ làm lịch sử.
- `Exam` có `templateExamId`, `courseClass` và tùy chọn `shift_id`: một lượt thi, giữ bản sao câu hỏi và hạn chung. `shift_id` duy nhất: mỗi ca đã chia chỉ mở một lượt; lớp chưa chia vẫn có lượt cả lớp.
- `ExamOrganizer` cho chọn lớp/ca/đề và tải danh sách trước khi bắt đầu. Chuẩn bị là trạng thái giao diện; chưa tạo lượt hoặc tính giờ.
- Bắt đầu gọi `ExamServer.openExam(template,classId,shiftId)` rồi `ExamService.activateExam`. Service khóa giáo viên/lớp, xác thực quyền, môn học, ca thuộc đợt hiện hành và ca đang diễn ra trước khi sao chép đề, gắn lớp/ca và lưu deadline.
- Một Server hiện có một giảng viên và một ca thi đang diễn ra. Mở lại đúng ca còn hạn giữ deadline; ca sau tạo ID mới.
- `findOffer` kiểm tra cả thành viên lớp lẫn danh sách ca; `startAttempt` kiểm tra lại khi cấp đề. Biết mã lượt thi không cấp quyền tham gia. Tiêu đề thông báo, bài thi và kết quả kèm số ca, dùng giao thức Client hiện có.

## Tạo đề nhanh và mã đề

`QuestionBankService.drawQuestions` kiểm tra phạm vi môn, ma trận mức 1–6 và tổng 1–200 câu, rút không hoàn lại bằng SecureRandom. Danh sách loại trừ hỗ trợ đổi từng câu cùng mức. Đây là thao tác đọc; `ExamEditorDialog` xem trước và kết hợp sửa thủ công, `createExamFromBank` tiếp tục chụp câu hỏi khi lưu đề mẫu.

`activateExam(template,class,shift,variantCount)` vẫn tạo một round và một bộ câu hỏi snapshot chung. Với 2–8 mã, `ExamVariants` sinh các bố cục khác nhau, đảo thứ tự questionId và hoán vị vị trí bốn lựa chọn; chia ngẫu nhiên cân bằng mã 101–108 cho danh sách sinh viên lớp/ca. Round lưu JSON `variants_json` MEDIUMTEXT nullable gồm bố cục và bảng phân mã, không thêm bảng/copy nội dung cho từng mã. Ca một mã và dữ liệu cũ có JSON null dùng thứ tự gốc.

`ExamAttempt.variant_code` cố định khi tham gia. `toDto(attempt)` áp dụng bố cục và kèm mã trong title; questionId và cấu trúc TCP hiện có được giữ nguyên. Đáp án nháp là chữ theo vị trí Client đã thấy. Khi nộp/hết giờ, Server ánh xạ chữ đó về lựa chọn gốc trước khi so đáp án; `StudentAnswer.answerText` lưu chữ Client đã chọn và `Submission.variant_code` lưu mã để đối chiếu. Mã được xuất trong bảng điểm/CSV và lịch sử. Không gửi JSON phân mã hay đáp án đúng sang Client.

`positionalOptions` giữ nguyên A–D khi phát hiện một số mẫu tham chiếu vị trí tiếng Việt/Anh phổ biến. Bộ sinh kiểm tra số bố cục khả dụng khi mọi câu đều phải giữ lựa chọn và từ chối nếu không đủ mã khác nhau. Đây là nhận diện mẫu văn bản; người soạn cần tránh tham chiếu phụ thuộc thứ tự câu hoặc các cách viết vị trí ngoài mẫu.

`findOffer` và `startAttempt` chặn người không có trong bảng phân mã, kể cả mới được thêm vào lớp sau khi ca nhiều mã đã mở. Phân mã được lưu cùng transaction mở ca, không phụ thuộc số thứ tự người đăng nhập, RAM hay seed được Client khai báo. Mở lại ca cùng số mã không sinh lại bố cục; đổi số mã khi đang thi bị từ chối.

## Nhập câu hỏi Word

`QuestionWordImport` chỉ đọc `word/document.xml` bằng ZIP/StAX của JDK. Tệp tối đa 5 MB, phần XML sau giải nén tối đa 4 MB, tối đa 500 câu. DTD và external entities bị tắt. Trình đọc ghép các run và xuống dòng trong Word, nhận tiêu đề MỨC 1–6, Câu n:, A.–D. và Đáp án: A–D. Nội dung không hỗ trợ (bảng/ảnh/công thức, đánh số tự động, chỉnh sửa chưa chấp nhận) tạo lỗi chặn nhập, tránh bỏ sót nội dung mà vẫn báo thành công.

`QuestionBankService.previewWord` kiểm tra quyền môn và phân loại mới/trùng/lỗi, không ghi dữ liệu. `importWord` khóa môn, kiểm tra lại, bỏ qua câu trùng hoàn toàn và lưu nguyên transaction vào `BankQuestion`. Việc nhập không sửa câu có sẵn hay ảnh chụp đề thi. `QuestionWordPreviewDialog` hiện danh sách và đầy đủ lựa chọn ở vùng chi tiết; chỉ cho xác nhận khi không có lỗi và còn câu mới. Mẫu `.docx` được đóng gói dưới `/templates/` và tải bằng nút trong `QuestionBankBrowser`.

## Lưu đáp án và khôi phục

`ExamAttempt` duy nhất `(student_id,exam_id)` giữ thời điểm bắt đầu, deadline, submittedAt và `saved_answers_json` nullable. Không thêm bảng nháp riêng.

`ANSWER_UPDATE` chứa toàn bộ lựa chọn hiện tại. Service khóa lượt, kiểm tra thời điểm Server nhận và mã câu rồi lưu JSON trong transaction. Chỉ người đã xác thực có lượt làm phù hợp mới cập nhật được.

Vào lại dùng cùng lượt/deadline. `ExamDto.savedAnswers` chứa riêng lựa chọn đã lưu của lượt đó; `ClientApp` dựng lựa chọn trước khi gắn listener để không gửi lại ảnh chụp còn thiếu trong lúc dựng giao diện. DTO câu hỏi không chứa đáp án đúng.

Hết giờ dùng JSON đã lưu để chấm; không phụ thuộc RAM của tiến trình hoặc trạng thái kết nối. Sau khi lưu Submission/StudentAnswer và đánh dấu submitted, dữ liệu nháp được xóa khỏi lượt. Gửi nộp lại trả cùng kết quả.

## Thời gian, hàng đợi và kết nối

- Client dùng deadlineEpochMillis/serverTimeEpochMillis và đồng hồ đơn điệu để đếm thời gian còn lại.
- Luồng đọc đóng dấu nhận khi có đủ frame, trước parse/chờ slot. `RequestReceipt` giữ thông điệp đáp án/nộp trước hạn trong khi chờ xử lý.
- Chấm quá hạn khóa lượt và hoãn nếu còn receipt đúng hạn. Nhận sau hạn không sửa kết quả.
- EOF/ghi lỗi vẫn xử lý các frame hoàn chỉnh đã nhận trước khi xóa phiên. Thay phiên đăng nhập hủy handler cũ.
- Hàng đợi vào tối đa 64 frame/16 Mi ký tự; các yêu cầu dùng semaphore giới hạn truy cập DB.
- `ClientWriter` có hàng đợi riêng, timeout ghi 10 giây và giới hạn dữ liệu; Client chậm không giữ luồng chấm điểm.
- Mở ca/tham gia/dừng dùng khóa đọc/ghi trong ExamServer. Không dừng, đóng cửa sổ hay đổi giáo viên khi ca còn hạn hoặc đang hoàn tất chấm.

## Theo dõi, xử lý vi phạm và bảng điểm

`SessionRegistry` ánh xạ studentCode sang handler/IP/trạng thái của phiên hiện hành. `ExamOrganizer` ghép với danh sách lớp để hiện cả người chưa kết nối; không coi offline là kết luận vắng thi.

Cảnh báo gửi đúng handler. Khóa tài khoản được lưu trước khi ngắt kết nối; đáp án đã nhận vẫn lưu để chấm. Nút Mở khóa SV ở thanh tài khoản thao tác trên database.

`ExamResultsPane` chỉ trình bày kết quả trắc nghiệm, lọc theo classId/roundId và chụp danh sách đang lọc để xuất. `ExamResultsCsv` tạo UTF-8/BOM, escape CSV và vô hiệu hóa tiền tố công thức ở ô văn bản khi mở bằng bảng tính.

## Dữ liệu cũ

Lớp, hồ sơ, đề và kết quả cũ giữ nguyên. Chỉ giữ hai mapping đọc bảng thực hành trong `server/legacy/entity`; service, UI, DTO và giao thức thực hành đã được bỏ. Tab Đề thi dùng `ExamTemplateManager`; tiện ích giao diện chung là `UiSupport`. Cột saved_answers_json là nullable để cập nhật schema hiện có. Lượt cũ chưa có đáp án lưu được hiểu là chưa trả lời.

## Khôi phục và bảng theo dõi

TCP Server nạp ca còn hạn của đúng giảng viên trước khi nhận đăng nhập. Người chưa vào bài vẫn nhận offer; ID lượt, deadline và mã đề không đổi. `ExamOrganizer` tách Online/Offline từ phiên mạng khỏi tiến độ đọc qua `ExamService.roundProgress(classId, shiftId)`, tự cập nhật khoảng 2 giây. Bài đã nộp giữ trạng thái khi offline/vào lại; người chưa tham gia sau hạn là Vắng thi.
