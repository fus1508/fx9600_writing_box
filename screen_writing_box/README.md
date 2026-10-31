# Màn hình trạng thái FX9600

Dự án PlatformIO cho ESP32-S3 SuperMini và TFT ILI9488 SPI, chỉ hiển thị trạng thái. Chưa điều khiển thao tác ghi. Cấu hình board `esp32-s3-devkitc-1`, flash 4 MB, chế độ DIO và SPI HSPI theo dự án Clock tham chiếu; cần kiểm tra nạp và chạy trên thiết bị thực.

1. Tắt nguồn và nối theo cấu hình Clock: CS=10, RESET=8, DC/RS=9, SDI/MOSI=11, SCK=12. Nối GND chung; cấp VCC và LED/BL theo mạch và thông số module, không cấp dòng đèn nền trực tiếp từ GPIO. Kiểm tra mức logic 3,3 V; không cần nối cảm ứng, SD hoặc MISO cho hiển thị.
2. Sao chép `include/config.example.h` thành `include/config.h`, nhập Wi-Fi 2,4 GHz và URL trạm. Không đưa `config.h` vào Git.
3. Mở thư mục trong PlatformIO và build bằng `pio run`. Upload/chạy thật thuộc SAT được phê duyệt riêng; Phase 3 không upload firmware.

Phase 3 yêu cầu Java API schema v2, `GET /status` và `/status/wait?since=<revision>`
trên cổng `status.port` (mặc định 8081). Long poll trả khi event/health đổi hoặc sau
400ms; lỗi retry 200ms. Network task riêng, mailbox một snapshot; `loop()` là TFT
owner và kiểm tra freshness độc lập HTTP. Không còn chờ HTTP trong render loop.

Parser nhận Content-Length 1..8192, JSON arena 6144 byte, kiểm tra version, kiểu,
enum, length, recovery/safety và evidence ages. Cùng boot, sampleSequence phải tăng;
cùng revision vẫn hợp lệ khi snapshot còn mới. Request + mailbox delay >=1000ms
bị loại. Age cộng thời gian request bảo thủ, không gia hạn SDK progress từ HTTP 200.

Mất Wi-Fi hoặc sau 2500ms không có response hợp lệ: STATION UNREACHABLE, hai thiết
bị UNKNOWN; không giữ READY hay “Đang chờ mã”. Vòng điều khiển backend cũ chuyển
UNKNOWN theo lease 1000ms. Sau Wi-Fi loss cần response từ request mới để phục hồi.
Recovery và write outcome chưa rõ ưu tiên cả khi offline. Scanner CDC chỉ CONNECTED,
không báo decode-engine READY; production RFID write vẫn khóa. Có badge chữ R/S,
app state và transaction ID. Chi tiết đầy đủ: [contract](../document/phase3/health-contract.md),
[state design](../document/phase3/ui-state-design.md), [SAT](../document/phase3/latency-test-plan.md).

Host checks dùng chính reducer/parser firmware, C++11 + ArduinoJson 6.21.5:
chạy Java verify và `org.fus.HealthLatencyCheck` tạo JSON API fixture, sau đó
`scripts/verify-screen.ps1 -Compiler <g++/clang++> -JsonInclude <folder-ArduinoJson.h>`;
dùng `-Zig` nếu compiler là zig.exe. Không có PlatformIO trong môi trường Phase 3;
host checks không thay build/chạy firmware, độ trễ TFT hoặc scheduling thật.

API là latest snapshot, có thể coalesce event; journal giữ outcome giao dịch.
HTTP chưa auth/TLS, chỉ dùng LAN/ACL được provision. Font Be Vietnam Pro giữ nguyên;
tool tạo font: `tools/GenerateVietnameseFont.java`. Chưa có SAT reader/ESP32 thật.
