# Màn hình trạng thái FX9600

Dự án PlatformIO cho ESP32-S3 SuperMini và TFT ILI9488 SPI, chỉ hiển thị trạng thái. Chưa điều khiển thao tác ghi. Cấu hình board `esp32-s3-devkitc-1`, flash 4 MB, chế độ DIO và SPI HSPI theo dự án Clock tham chiếu; cần kiểm tra nạp và chạy trên thiết bị thực.

1. Tắt nguồn và nối theo cấu hình Clock: CS=10, RESET=8, DC/RS=9, SDI/MOSI=11, SCK=12. Nối GND chung; cấp VCC và LED/BL theo mạch và thông số module, không cấp dòng đèn nền trực tiếp từ GPIO. Kiểm tra mức logic 3,3 V; không cần nối cảm ứng, SD hoặc MISO cho hiển thị.
2. Sao chép `include/config.example.h` thành `include/config.h`, nhập Wi-Fi 2,4 GHz và URL trạm. Không đưa `config.h` vào Git.
3. Mở thư mục trong PlatformIO, build/upload và mở serial monitor 115200. Nếu board không vào chế độ nạp, kiểm tra hướng dẫn của nhà sản xuất.

Ứng dụng FX9600 cung cấp `GET /status` và `GET /status/wait?since=<revision>` qua HTTP trên cổng `status.port` (mặc định 8081). Màn hình dùng long polling: reader trả dữ liệu ngay khi có thay đổi hoặc sau tối đa hai giây nếu không có thay đổi. Sau mỗi phản hồi, màn hình gửi yêu cầu chờ tiếp; khi lỗi, chờ 500 ms trước khi thử lại. Endpoint `/status` vẫn dùng để kiểm tra thủ công. Phải cập nhật cả ứng dụng Java và firmware ESP32; phiên bản Java cũ chưa có `/status/wait`. Phản hồi yêu cầu HTTP 200 có `Content-Length` và JSON tối đa 1024 byte:

```json
{"revision":"id-phien-1","state":"WAIT_TRIGGER","scanner":"READY","reader":"READY","barcode":"9K1135600660004039","epc":"394B1135600660004039","error":""}
```

Các trạng thái có thể gồm `IDLE`, `WAIT_TRIGGER`, `WRITING`, `PASS`, `FAIL`. `barcode`, `epc`, `error` có thể rỗng; `revision` và ba trường trạng thái phải là chuỗi. Sau 5 giây không nhận được JSON hợp lệ, màn hình báo offline, hiển thị trạng thái reader/scanner là UNKNOWN và không giữ kết quả cũ. API cung cấp trạng thái mới nhất; các thay đổi quá nhanh giữa các lần phản hồi có thể không được hiển thị riêng lẻ. Wi-Fi được kiểm tra trực tiếp trên ESP32. API không xác thực và hiển thị barcode/EPC: chỉ dùng trong mạng nội bộ tin cậy, giới hạn truy cập bằng mạng hoặc firewall. Chưa kiểm thử cùng reader thực tế. Font Be Vietnam Pro được nhúng trong firmware và hỗ trợ chữ tiếng Việt có dấu (Unicode dựng sẵn). Có thể tạo lại font bằng javac tools/GenerateVietnameseFont.java rồi chạy java -cp tools GenerateVietnameseFont assets/be_vietnam_pro.ttf include/be_vietnam_pro_20.h.
