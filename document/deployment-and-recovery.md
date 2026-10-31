# Triển khai và phục hồi trạm FX9600

> **Pha 1 (2026-10-31): chưa được deploy.** Đường ghi RFID production bị khóa
> `HARDWARE_VERIFICATION_REQUIRED`; không áp dụng các bước thử ghi và phục hồi cũ
> bên dưới cho bản này. Scan/timeout/reboot không xóa recovery. Journal, checkpoint
> và marker phải được giữ qua upgrade. Quy trình hiện hành và ACK offline nằm tại
> [phase1/architecture.md](phase1/architecture.md); xem
> [phase1/hardware-blockers.md](phase1/hardware-blockers.md) trước mọi SAT/deploy.
> Các mục còn lại mô tả bản trước Pha 1 và được giữ để tham chiếu.

## Trạng thái

Đây là bộ file để thử nghiệm, chưa phải gói `.deb` và chưa được xác minh để tự chạy trên firmware 3.32.37. Cần kiểm tra SDK Embedded, cách cài và cơ chế giám sát tiến trình thực tế trước khi đóng gói hoặc vận hành. Chưa kiểm thử watchdog bằng SDK/tag thật.

## Các bước chạy thử bản dev

Thực hiện lần lượt; lệnh Windows chạy trên **PowerShell của máy phát triển**, còn lệnh reader chạy trong **SSH của FX9600**. Địa chỉ ví dụ là `192.168.1.50`; thay bằng IP thực tế nếu reader đổi IP. Bản dev có thể thay đổi EPC của tag khi nhấn nút, vì vậy dùng tag thử và chỉ đặt một tag vật lý trong box.

### 1. Kiểm tra thư mục trên reader

Trong SSH của FX9600:

```sh
ls -ld ~/fx9600_writing_box
ls -l ~/fx9600_writing_box/Symbol.RFID.API3.jar
ls -l ~/fx9600_writing_box/station.properties
```

Thư mục `~/fx9600_writing_box` và JAR SDK đã được dùng trong các lần thử trước. Nếu thư mục chưa có, tạo bằng `mkdir -p ~/fx9600_writing_box`. Nếu file cấu hình chưa có, tạo ở bước 3. Dừng ứng dụng cũ cùng mọi chương trình đang đọc scanner hoặc điều khiển RFID trước khi chạy bản mới.

Kiểm tra cổng API 8081 còn trống bằng `netstat -tln | grep ':8081'`. Nếu có kết quả LISTEN, chọn cổng khác cho cả reader và màn hình. Trên thiết bị này, 8080 đã được một dịch vụ nội bộ sử dụng.

### 2. Tạo bản build mới trên Windows

Tại PowerShell, từ thư mục dự án `D:\Project\Embedded\fx9600_writing_box`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/stage-release.ps1 -OutputDirectory target/release-dev
```

Script chạy các kiểm tra và tạo JAR mới cùng SDK, cấu hình mẫu và `run.sh` trong `target/release-dev`. Nếu thư mục này đã tồn tại, script dừng để tránh ghi đè: chọn tên staging khác bằng `-OutputDirectory`, rồi dùng đúng tên đó trong các lệnh sao chép dưới đây. Không đưa file cấu hình Wi-Fi của màn hình vào gói.

### 3. Sao chép bản mới từ Windows sang reader

Trong PowerShell Windows:

```powershell
scp "D:\Project\Embedded\fx9600_writing_box\target\release-dev\fx9600-writing-box.jar" rfidadm@192.168.1.50:~/fx9600_writing_box/
scp "D:\Project\Embedded\fx9600_writing_box\target\release-dev\run.sh" rfidadm@192.168.1.50:~/fx9600_writing_box/
```

Nếu bước 1 cho thấy JAR SDK chưa có trên reader, sao chép thêm:

```powershell
scp "D:\Project\Embedded\fx9600_writing_box\target\release-dev\Symbol.RFID.API3.jar" rfidadm@192.168.1.50:~/fx9600_writing_box/
```

Nếu **chưa có** `station.properties`, sao chép cấu hình mẫu. Nếu đã có và đã chỉnh, giữ file cũ để không ghi đè:

```powershell
scp "D:\Project\Embedded\fx9600_writing_box\target\release-dev\station.properties.example" rfidadm@192.168.1.50:~/fx9600_writing_box/station.properties
```

### 4. Kiểm tra và chạy trên FX9600

Trong SSH của reader:

```sh
cd ~/fx9600_writing_box
ls -l fx9600-writing-box.jar Symbol.RFID.API3.jar station.properties run.sh
```

Xác nhận cấu hình scanner trỏ đúng cổng CDC, cổng đã ở chế độ raw với hậu tố LF, tài khoản có quyền đọc và thư mục marker có quyền ghi. Xác minh native dependency RFID SDK có sẵn trên reader. Nếu `station.properties` cũ ghi `status.port=8080`, đổi thành `status.port=8081` sau khi kiểm tra cổng trống; giá trị trong file cấu hình ghi đè mặc định của chương trình. Sau đó chạy:

```sh
chmod +x run.sh
./run.sh
```

Mật khẩu tag mặc định `00000000`; nếu dùng giá trị khác, đặt biến môi trường `FX9600_ACCESS_PASSWORD` gồm tám ký tự HEX trước khi chạy và không ghi vào log. Giữ cửa sổ SSH mở để xem trạng thái; nhấn `Ctrl+C` để dừng.

### 5. Kiểm tra trạng thái và thử ghi

- Xác nhận terminal báo scanner và reader sẵn sàng. Quét barcode hợp lệ; terminal cần báo `WAIT_TRIGGER`.
- Từ một cửa sổ PowerShell Windows khác, kiểm tra API bằng `Invoke-WebRequest -UseBasicParsing http://192.168.1.50:8081/status`. Màn hình ESP32 phải cấu hình đúng URL và kết nối cùng mạng.
- Khi đã xác nhận EPC cũ của **một tag thử** dài 80 bit, nhấn GPI1; theo dõi `WRITING` rồi `PASS` hoặc `FAIL`. EPC cũ khác 80 bit hiện báo `EPC_RESIZE_UNVERIFIED` và không ghi.
- Nếu lỗi có `tagMayHaveChanged=true` hoặc marker phục hồi còn tồn tại, giữ riêng và kiểm tra tag trước khi tiếp tục.

## Timeout và phục hồi

`operation.timeout.ms` mặc định 30000 ms áp dụng cho toàn bộ inventory, ghi và đọc lại sau một lần nhấn. Trước khi chạy thao tác, ứng dụng ghi `recovery.marker` (mặc định `rfid-operation.marker`) và đồng bộ nội dung; tài khoản ứng dụng cần có quyền ghi tại đường dẫn này. Nếu không tạo được marker, ứng dụng báo FAIL và không bắt đầu thao tác. Khi hết hạn, ứng dụng ghi lỗi, giữ marker và kết thúc tiến trình với mã 2 để cơ chế giám sát bên ngoài khởi động lại. Chương trình hiện không tự cài hay cung cấp cơ chế giám sát đó.

Nếu marker còn sau khi khởi động, terminal và API cảnh báo `PREVIOUS_OPERATION_INCOMPLETE tagMayHaveChanged=true`. Kiểm tra tag đã dùng trước đó; không có phép ghi lại tự động. Theo quy trình được chọn, trạm nhận barcode mới và cho phép thử sau một lần nhấn mới. Cảnh báo trong API được xóa khi nhận barcode mới; marker được xóa khi một thao tác mới hoàn tất. Nếu dừng đột ngột giữa lúc lưu marker, mức bảo đảm dữ liệu phụ thuộc hệ thống tập tin và nguồn điện.

Việc dừng tiến trình Java không chứng minh tác vụ SDK/reader đã dừng. Phải kiểm thử khả năng phục hồi reader, thao tác inventory/access cũ và tránh chồng lấn trên firmware thực tế trước khi kích hoạt tự khởi động trong vận hành. Deadline chưa bao phủ các lệnh SDK ngoài thao tác ghi, như kết nối hoặc đọc GPI.

## Các điều kiện còn chặn production

- EPC cũ khác 80 bit vẫn bị từ chối; lỗi `WRITE_PC` và cách chọn lại MR6P chưa được xác minh.
- Tag trùng EPC chưa được phân biệt bằng TID; quy trình phụ thuộc việc đặt đúng một tag vật lý trong box.
- Scanner CDC chỉ báo trạng thái cổng/IO; chưa chứng minh phát hiện được mọi lỗi mất nguồn hoặc scanner im lặng.
- API HTTP không xác thực; giới hạn vào LAN tin cậy. Cần kiểm thử reader và màn hình thực tế, gồm mất kết nối và phục hồi.
- Cần xác định phương án `.deb`, tài khoản, quyền thiết bị, startup, watchdog/supervisor, rollback và kiểm thử trong firmware 3.32.37.
