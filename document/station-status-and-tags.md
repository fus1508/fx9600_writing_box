# Yêu cầu trạng thái và ghi tag

> Phase 3 thay contract health và UI: xem [health-contract](phase3/health-contract.md)
> và [ui-state-design](phase3/ui-state-design.md). Bảng trạng thái bên dưới mô tả
> baseline cũ: mở CDC hiện chỉ CONNECTED, không READY. Production RFID vẫn BLOCKED
> theo Phase 1; không áp dụng quy trình ghi cũ trước nghiệm thu phần cứng.

## Phạm vi và điều kiện tag

Trạm nhận barcode USB CDC kết thúc LF, nhấn GPI1 để ghi, và chỉ báo PASS sau khi đọc lại EPC khớp và giải mã được barcode. Cách mã hóa hiện tại chỉ nhận hai ký tự ASCII chữ hoa/số theo sau bởi đúng 16 chữ số; xem `epc-encoding.md`. Thay đổi độ dài hoặc loại dữ liệu barcode cần thống nhất hợp đồng giải mã với ứng dụng khác trước khi triển khai.

Mục tiêu là hỗ trợ các tag tương thích có đủ bộ nhớ, cho phép ghi và được SDK/reader nhận diện. Không thể bảo đảm ghi mọi loại tag: tag bị khóa, không có đủ bộ nhớ, sai quyền, không tương thích hoặc không xác định được mục tiêu phải báo FAIL. Không cắt barcode, không báo PASS khi đọc lại sai.

Phiên bản hiện tại chỉ ghi khi EPC cũ có 80 bit. Việc đổi độ dài EPC và cập nhật PC chưa được xác minh trên MR6P; đã có lần ghi PC thất bại trên thiết bị. Không gỡ chặn cho đến khi xác định lại chính xác tag qua toàn bộ các bước và thử nghiệm đổi độ dài trên tag thật. Khi ghi thất bại sau khi bắt đầu, thông báo tag có thể đã thay đổi; không tự ghi lại.

## Một hoặc nhiều tag

Vận hành với một tag vật lý trong vùng đọc. Inventory hiện tại chỉ phân biệt các EPC khác nhau: hai tag có cùng EPC có thể bị xem là một. Không được hiểu một EPC được quan sát là bằng chứng tuyệt đối rằng chỉ có một tag.

Phương án cần khảo sát là đọc TID có phần serial thực sự duy nhất cho từng tag trong lúc inventory và chọn lại mục tiêu theo TID ổn định. Không phải mọi TID hoặc mọi phần TID đều đủ phân biệt từng tag. Cần xác minh SDK Embedded Java, cấu hình reader và tag MR6P có thể báo TID độc lập ngay cả khi EPC trùng; không chọn một tag chỉ bằng kết quả `readWait` lọc EPC. Khi TID không được báo đầy đủ, nhiều tag hoặc inventory không tin cậy, yêu cầu thiết kế tương lai là từ chối ghi. Kể cả vậy, tag ngoài vùng đọc hoặc không được phát hiện vẫn cần được ngăn bằng cơ cấu box và kiểm thử thực tế.

## Trạng thái hiện tại

| Đối tượng | Trạng thái | Ý nghĩa |
|---|---|---|
| Scanner | `READY` | Đã mở thiết bị CDC; chưa chứng minh scanner đang hoạt động đầy đủ. |
| Scanner | `DISCONNECTED` | Mở/đọc lỗi hoặc phát hiện đường dẫn thiết bị biến mất. |
| Reader | `READY` | Kết nối SDK và GPI1 được kiểm tra thành công. |
| Reader | `DISCONNECTED` | Kết nối không sẵn sàng hoặc phiên bị đánh dấu không sử dụng được. |
| Trạm | `IDLE` | Không còn barcode hợp lệ hoặc đã hết thời gian hiển thị kết quả. |
| Trạm | `WAIT_TRIGGER` | Có barcode hợp lệ và chờ nhấn nút. |
| Trạm | `WRITING` | Đang inventory, ghi hoặc xác minh; từ chối thao tác ghi khác. |
| Trạm | `PASS` | EPC đã đọc lại khớp và giải mã khớp barcode. |
| Trạm | `FAIL` | Không hoàn tất hoặc không xác minh được; kèm mã lỗi, giai đoạn và cờ `tagMayHaveChanged`. |
| Màn hình | `WiFi DISCONNECTED` | ESP32 chưa kết nối Wi-Fi. |
| Màn hình | Reader/scanner `UNKNOWN` | Màn hình không nhận được trạng thái FX9600 hợp lệ; không suy ra thiết bị nào bị hỏng. |

Barcode hợp lệ mới nhất thay thế mã đang chờ và có hiệu lực mặc định 60 giây. Sau PASS/FAIL, kết quả hiển thị 5 giây; quét barcode mới hợp lệ có thể bắt đầu lượt mới trước khi hết thời gian đó. Khi phát hiện mất scanner hoặc reader, xóa barcode đang chờ và không bắt đầu ghi mới cho đến khi cả hai được báo sẵn sàng. Trong quá trình ghi, mất kết nối được báo FAIL và cần kiểm tra lại tag nếu dữ liệu có thể đã thay đổi.

## Giới hạn phát hiện mất kết nối

Scanner được theo dõi qua lỗi đọc và sự biến mất của đường dẫn CDC; reader qua kết nối SDK, sự kiện disconnect/lỗi reader và kiểm tra GPI. Đây là trạng thái phần mềm, không bảo đảm phát hiện tức thời mọi kiểu lỗi phần cứng: ví dụ scanner vẫn được hệ điều hành giữ cổng nhưng không còn quét. Cần kiểm thử rút cáp, mất nguồn, cắm lại và thời gian phục hồi trên firmware thực tế; muốn xác nhận scanner còn thực sự hoạt động cần cơ chế heartbeat hoặc trạng thái phần cứng được DS5502/CDC hỗ trợ và đã xác minh.

HTTP `/status` trên LAN là giao diện chỉ đọc cho màn hình, không có xác thực. ESP32 có thể báo UNKNOWN khi mạng hoặc API mất kết nối dù phần cứng reader vẫn hoạt động. Không dùng trạng thái màn hình làm bằng chứng kết quả ghi nếu chưa có phản hồi PASS từ trạm.
