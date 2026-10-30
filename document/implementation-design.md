# Thiết kế ban đầu trạm ghi EPC trên FX9600

> Tài liệu thiết kế ban đầu, giữ lại để tham khảo. Một số giả định và đề xuất dưới đây đã thay đổi; xem [overview.md](overview.md), [station-status-and-tags.md](station-status-and-tags.md) và [deployment-and-recovery.md](deployment-and-recovery.md) cho hành vi và giới hạn hiện tại.

## 1. Phạm vi và điều kiện tiên quyết

Ứng dụng Java chạy trên Zebra FX9600 firmware 3.32.37, kết nối RFID SDK nội bộ và nhận barcode từ scanner DS5502 cắm trực tiếp vào cổng USB host của reader. Scanner dự kiến dùng **USB CDC Host**, cung cấp dữ liệu qua cổng serial do hệ điều hành nhận diện. Không phụ thuộc PC hoặc internet trong vận hành. HMI và giao thức HMI chưa được quyết định.

**Trạng thái:** đây là thiết kế triển khai, chưa xác nhận tương thích USB CDC, quyền truy cập serial, Java runtime, RFID hay đóng gói trên thiết bị thực. Không coi tên thiết bị, tham số serial hoặc định dạng kết thúc dòng dưới đây là giá trị đã xác minh.

Nguồn tham khảo: `project-context.md`; mã tham chiếu `D:\Project\Web\writing_box\src`, đặc biệt `EpcEncodingService`, `StationServiceImp`, `ReaderServiceImp` và `ReaderHardwareImp`. Zebra mô tả USB CDC là chế độ giả lập cổng serial và yêu cầu host có driver CDC; tài liệu chung này không xác nhận hỗ trợ trên tổ hợp DS5502/FX9600: [Zebra Scanner SDK](https://techdocs.zebra.com/dcs/scanners/sdk-windows/), [USB Device Type](https://docs.zebra.com/us/en/scanners/general/sx20-series/sp20-prg/c-sm20-usb-interface/r-sm20-usb-parameter-defaults/c-param-desc-usb-device-type.platform-qrg.html).

## 2. Luồng nghiệp vụ

1. Scanner gửi dữ liệu barcode qua USB CDC; ứng dụng ghép byte thành một bản quét hoàn chỉnh theo ký tự kết thúc đã cấu hình và kiểm chứng.
2. Lưu barcode, thời điểm nhận và định danh lượt quét. Hiển thị trạng thái chờ thao tác; một bản quét mới thay thế bản đang chờ theo chính sách trạm.
3. Khi có lệnh ghi từ HMI hoặc nút được cấu hình, cố định lượt quét đang chọn; kiểm tra nó còn hiệu lực. Trong lúc ghi, bỏ qua các lượt quét mới và từ chối lệnh ghi đồng thời.
4. Kiểm tra và mã hóa barcode theo mục 4; inventory mới trong vùng làm việc. Chỉ tiếp tục khi xác định được đúng một tag theo tiêu chí mục 5.
5. Ghi EPC, đọc lại bộ nhớ EPC, so sánh byte và giải mã ngược so với barcode ban đầu. Chỉ báo PASS khi tất cả bước xác minh thành công; mọi lỗi hoặc không khớp báo FAIL.
6. Cập nhật HMI khi giao diện được chọn. Sau lỗi ghi hoặc mất kết nối, không tự ý ghi lại: tag có thể đã thay đổi một phần.

Cần cấu hình thời hạn barcode, hành vi thay thế và cơ chế kích hoạt. Mã tham chiếu giữ kết quả thành công 3 giây, lỗi 10 giây và cho thử lại trong khoảng lỗi; các giá trị này chỉ là hành vi tham chiếu, cần xác nhận với vận hành.

## 3. Scanner USB CDC

### Khảo sát trên thiết bị

- Xác định chính xác model, firmware, cáp và nguồn cấp của scanner; cấu hình scanner bằng tài liệu dành riêng cho model sang **USB CDC Host**, không nhầm với SSI over USB CDC. Thiết lập hậu tố dòng, ví dụ CR, LF hoặc CRLF, theo cấu hình đã thử.
- Với quyền quản trị phù hợp, cắm scanner và kiểm tra log hệ điều hành, danh sách USB và thiết bị serial trước/sau khi cắm. Các lệnh như `dmesg`, `lsusb`, `ls -l /dev/ttyACM* /dev/ttyUSB*` chỉ dùng nếu firmware cung cấp; ghi lại kết quả thực tế, không giả định chắc chắn có `/dev/ttyACM0`.
- Xác minh driver CDC và thiết bị serial thực sự xuất hiện; kiểm tra tài khoản chạy ứng dụng có quyền mở cổng. Ưu tiên đường dẫn ổn định theo nhận dạng USB nếu hệ thống hỗ trợ; nếu không, cấu hình đường dẫn và phát hiện lại sau khi cắm lại. Không mặc định cấp quyền rộng cho mọi người dùng.
- Kiểm tra thông số mở cổng, ký tự kết thúc, charset, prefix/suffix và dữ liệu thô bằng barcode mẫu. Cấu hình này phải khớp scanner và host. Xác minh ngắt/cắm lại, khởi động lại reader và quét liên tiếp.

### Thiết kế bộ đọc serial

Tạo `ScannerSource` với các trạng thái `DISCONNECTED`, `CONNECTING`, `READY`, `ERROR`; triển khai CDC riêng, không mang phụ thuộc JavaPOS/SNAPI của ứng dụng tham chiếu. Chọn thư viện serial có bản tương thích Java 8, hệ điều hành và kiến trúc CPU của FX9600; xác minh native library và cách đóng gói trước khi chọn. Nếu firmware không cung cấp driver hoặc không cho ứng dụng truy cập thiết bị, ghi nhận đây là điểm chặn cần xử lý trên thiết bị, không giả định Java có thể khắc phục.

Đọc luồng byte theo từng chunk: một lần đọc không nhất thiết tương ứng một barcode. Tích lũy đến delimiter đã cấu hình, xử lý đúng CRLF qua ranh giới chunk; chỉ loại delimiter, không tự `trim`, đổi chữ hoa hoặc bỏ số 0 đầu. Giới hạn kích thước buffer và thời gian giữ khung chưa hoàn chỉnh; khung quá dài hoặc hết thời gian phải bị loại và báo lỗi, không ghi dữ liệu một phần. Giải mã theo charset đã cấu hình; với định dạng mục 4 cần ASCII hợp lệ. Không tự gửi lệnh điều khiển hoặc giả định DTR/RTS khi chưa xác minh scanner yêu cầu.

Khi cổng bị ngắt hoặc đọc lỗi: đóng tài nguyên, bỏ khung chưa hoàn chỉnh, báo mất kết nối và thử kết nối lại có khoảng nghỉ; không sử dụng lại barcode cũ sau khi mất kết nối. Khi dừng ứng dụng, dừng luồng đọc và đóng cổng. Tránh ghi barcode thô vào log sản xuất nếu không cần thiết.

## 4. Hợp đồng mã hóa EPC

Tương thích với mã tham chiếu `EpcEncodingService`:

- Barcode phải khớp chính xác `[A-Z0-9]{2}[0-9]{16}`: hai ký tự đầu là chữ hoa ASCII hoặc số, theo sau là 16 chữ số ASCII. Không nhận chữ thường, dấu cách hay suffix scanner trong nội dung barcode.
- Hai ký tự đầu chuyển thành 2 byte ASCII. 16 chữ số còn lại đóng gói BCD: mỗi chữ số là một nibble, hai chữ số thành một byte; giữ nguyên các số 0 đầu.
- EPC kết quả dài 10 byte / 80 bit, tương ứng 20 ký tự HEX, không có padding. Đây là quy ước ứng dụng, cần xác nhận tính tương thích với tag và hệ thống sử dụng dữ liệu.

```text
Barcode:  9K1135600660004039
ASCII:    39 4B
BCD:      11 35 60 06 60 00 40 39
EPC HEX:  394B1135600660004039
```

Giải mã: kiểm tra đúng 10 byte; giải mã 2 byte đầu thành ASCII và xác nhận `[A-Z0-9]{2}`; 8 byte tiếp theo phải có từng nibble nằm trong 0–9; nối lại thành 18 ký tự. Từ chối mọi giá trị không hợp lệ. Java 8 không có `java.util.HexFormat`: dùng hàm chuyển HEX/byte tương thích Java 8 và kiểm tra đầu vào chặt chẽ.

## 5. Ghi và xác minh RFID

- Khởi tạo RFID SDK theo mẫu Embedded Java của Zebra, kết nối nội bộ `127.0.0.1:5084` như dự án mẫu; xác minh trên firmware thực tế. Cấu hình antenna, công suất, vùng hoạt động, timeout và access password theo thiết bị/tag; không lưu mật khẩu trong log.
- Inventory ngay trong thao tác ghi, không dựa duy nhất vào kết quả cũ trên HMI. Không ghi khi không có tag, phát hiện nhiều tag hoặc không thể chứng minh mục tiêu an toàn. Một EPC được quan sát không chứng minh chắc chắn chỉ có một tag vật lý: hai tag có thể trùng EPC hoặc một tag không được phát hiện. Cần thiết kế vùng đọc và xác minh bằng TID hoặc phương pháp phù hợp với tag/SDK; nếu không chứng minh được một tag duy nhất, không ghi.
- Xác nhận tag cho phép ghi, bộ nhớ đủ 80 bit và quyền/mật khẩu hợp lệ. Nếu EPC hiện tại khác 80 bit, cần cập nhật PC length đúng và bảo toàn các bit PC khác. Mã tham chiếu ghi EPC rồi cập nhật PC, có kiểm tra trước và đọc lại PC; quá trình nhiều bước có thể thất bại giữa chừng. Chỉ triển khai thay đổi độ dài sau khi xác minh chính xác đơn vị offset, cách chọn tag qua các bước, hành vi SDK và khả năng phục hồi trên tag thực. Nếu chưa xác minh, từ chối ghi khi độ dài EPC cũ khác 80 bit.
- Chọn đúng tag trong cả quá trình trước và sau khi EPC đổi; ưu tiên định danh ổn định như TID nếu thực tế hỗ trợ và đảm bảo phân biệt được tag. Sau ghi, đọc trực tiếp 10 byte EPC, so sánh với dữ liệu đã ghi; giải mã và so sánh barcode. Chỉ PASS khi đọc lại thành công và cả hai phép so sánh khớp. Với lỗi sau khi bắt đầu ghi, báo rõ nội dung tag có thể đã thay đổi và yêu cầu kiểm tra trước khi thử lại.

## 6. Kiến trúc và cấu hình đề xuất

- `ScannerSource`: kết nối CDC, tạo sự kiện barcode đầy đủ và trạng thái kết nối.
- `EpcCodec`: kiểm tra, mã hóa và giải mã độc lập với phần cứng.
- `RfidGateway`: kết nối SDK, inventory, chọn tag, ghi và đọc lại.
- `StationWorkflow`: quản lý lượt quét, hết hạn, khóa thao tác ghi, kết quả và lỗi.
- `StatusGateway`: cung cấp trạng thái cho HMI sau khi chọn giao thức; không ràng buộc nghiệp vụ vào Modbus TCP khi chưa quyết định.

Cấu hình ngoài mã nguồn: thiết bị serial ổn định, charset, delimiter, thông số cổng đã xác minh, timeout và giới hạn khung; địa chỉ RFID, antenna, timeout, mật khẩu và thời hạn barcode. Cung cấp trạng thái scanner/reader, barcode, EPC, kết quả, mã lỗi và thời điểm; thiết kế cơ chế báo mất kết nối khi chọn HMI.

## 7. Kiểm thử và triển khai

1. **Codec không cần phần cứng:** kiểm tra ví dụ trên, các số 0 đầu, tiền tố số/chữ; từ chối sai chiều dài, chữ thường, ký tự ngoài ASCII, chữ trong phần số và BCD có nibble A–F. Kiểm tra `decode(encode(barcode))` với nhiều đầu vào hợp lệ.
2. **CDC:** dùng nguồn byte giả lập để kiểm tra một khung chia nhiều lần đọc, nhiều khung trong một lần đọc, CRLF tách chunk, timeout, quá dài, byte sai và ngắt kết nối giữa khung. Sau đó đo dữ liệu thực từ DS5502 và kiểm tra rút/cắm, khởi động lại.
3. **Workflow:** kiểm tra barcode hết hạn/thay đổi, lệnh ghi đồng thời, quét trong khi ghi, không có tag, nhiều tag, thất bại ghi và đọc lại không khớp. Không phát PASS khi một bước xác minh lỗi.
4. **Thiết bị thực:** dùng tag thử có thể ghi, kiểm tra EPC và PC trước/sau, tag khóa/sai mật khẩu, mất kết nối giữa các bước và khả năng nhận diện lại cùng tag. Thử nhiều tag, kể cả trùng EPC, và kiểm tra vùng đọc thực tế.
5. **Đóng gói:** xác nhận phiên bản Java trên reader và các dependency Java/native; build với mục tiêu Java 8. Kiểm tra `.deb` tạo bằng `tools/userAppPackageBuilder/userAppPackageBuilder.bat`, cách cài, quyền serial, khởi động lại, tự khởi chạy, log và gỡ/cập nhật trên firmware thực trước khi vận hành.

## 8. Quyết định cần chốt sau khảo sát

- Driver CDC, tên thiết bị ổn định, quyền, charset, delimiter và cấu hình cổng thực tế.
- Model tag, dung lượng EPC, định danh duy nhất, mật khẩu và phương án an toàn khi thay đổi độ dài EPC.
- Nút kích hoạt, thời hạn barcode, cách xử lý quét mới và thử lại sau lỗi.
- Model HMI, giao thức, ánh xạ dữ liệu và cách cảnh báo mất kết nối.
- Runtime Java, thư viện serial/native và phương thức đóng gói được FX9600 hỗ trợ.
