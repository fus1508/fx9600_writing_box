# Tổng quan trạm ghi RFID FX9600

Dự án xây dựng trạm ghi EPC chạy trực tiếp trên Zebra FX9600. Người vận hành quét barcode bằng scanner USB, đặt một tag vào box và nhấn nút GPI1. Ứng dụng mã hóa barcode, inventory và ghi tag, sau đó đọc lại để xác minh. Màn hình ESP32 hiển thị trạng thái qua mạng nội bộ.

## Luồng chính

1. Scanner gửi barcode ASCII kết thúc bằng LF. Trạm kiểm tra định dạng và chờ nhấn nút.
2. Khi nhấn GPI1, trạm khóa lượt hiện tại, tìm tag và ghi EPC 80 bit nếu các điều kiện ghi được đáp ứng.
3. Trạm đọc lại EPC và đối chiếu cả byte lẫn barcode giải mã. Chỉ kết quả xác minh thành công mới được báo `PASS`; lỗi được báo `FAIL`.

Barcode có dạng `[A-Z0-9]{2}[0-9]{16}`. Ví dụ `9K1135600660004039` được mã hóa thành `394B1135600660004039`; chi tiết và ví dụ giải mã ở [epc-encoding.md](epc-encoding.md).

## Những điều cần biết trước khi chạy

- Phải cách ly một tag vật lý trong vùng đọc: inventory theo EPC không phát hiện chắc chắn nhiều tag trùng EPC.
- EPC cũ phải dài 80 bit. Thay đổi độ dài EPC trên MR6P chưa được xác minh và hiện bị chặn.
- Lỗi sau khi bắt đầu ghi có thể để tag ở trạng thái đã thay đổi; kiểm tra tag trước khi xử lý tiếp.
- API trạng thái HTTP không xác thực, chỉ dành cho mạng nội bộ tin cậy. Màn hình không phát lệnh ghi.
- Các kiểm tra phần mềm mô phỏng không thay thế thử nghiệm ghi, đọc lại và phục hồi trên FX9600 và tag thực.

## Đọc tiếp

| Nhu cầu | Tài liệu |
|---|---|
| Hiểu cấu trúc mã nguồn | [project-structure.md](project-structure.md) |
| Hiểu thiết bị và giới hạn | [project-context.md](project-context.md) |
| Vận hành một lượt ghi | [operations.md](operations.md) |
| Build, chạy thử, timeout và phục hồi | [deployment-and-recovery.md](deployment-and-recovery.md) |
| Trạng thái và điều kiện tag | [station-status-and-tags.md](station-status-and-tags.md) |
| Kiểm tra dữ liệu scanner | [scanner-cdc-test.md](scanner-cdc-test.md) |
| Thiết lập màn hình ESP32 | [README màn hình](../screen_writing_box/README.md) |

[implementation-design.md](implementation-design.md) lưu thiết kế ban đầu để tham khảo; mã nguồn và các tài liệu vận hành ở trên mô tả hành vi hiện tại.

Trên Windows, chạy `powershell -File scripts/verify.ps1` để biên dịch mã tương thích Java 8 và chạy các kiểm tra độc lập. `mvn package` dùng JAR SDK trong `library/`; `mvn test` không chạy các kiểm tra dạng chương trình `main`.
