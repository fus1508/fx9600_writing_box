# Cấu trúc dự án

```text
src/main/java/org/fus/
├── Main.java              Khởi động và kết nối các thành phần
├── domain/                Nghiệp vụ trạm, mã hóa EPC, lỗi nghiệp vụ/kỹ thuật
├── api/                   API HTTP chỉ đọc trạng thái
├── config/                Đọc và kiểm tra cấu hình
└── device/
    ├── scanner/           Đọc USB CDC và tách barcode
    ├── gpi/               Xử lý nút GPI1 và chống dội
    └── rfid/              Kết nối SDK, inventory, ghi và đọc lại tag

src/test/java/org/fus/     Các chương trình kiểm tra không cần phần cứng
screen_writing_box/        Firmware PlatformIO cho màn hình ESP32-S3
library/                   Thư viện Zebra RFID SDK
document/                  Tài liệu dự án và vận hành
station.properties.example Cấu hình mẫu cho FX9600
```

Điểm chạy Java vẫn là `org.fus.Main`. Build bằng `mvn package` hoặc chạy `powershell -File scripts/verify.ps1` trên Windows để biên dịch và kiểm tra. Khi triển khai, sao chép toàn bộ cây `target/classes/org` để bao gồm các package mới, cùng JAR SDK và cấu hình; xem `operations.md`.
