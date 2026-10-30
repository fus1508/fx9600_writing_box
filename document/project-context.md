# Bối cảnh phần cứng

Trạm chạy Java 8 trực tiếp trên Zebra FX9600, kết nối RFID SDK nội bộ và scanner Zebra DS5502 qua USB CDC. Nút ghi nối GPI1, active-low. Màn hình ESP32 lấy trạng thái qua HTTP trong mạng nội bộ; màn hình chỉ hiển thị, không điều khiển ghi.

Trong các lần khảo sát, reader dùng firmware 3.32.37 và OpenJDK 1.8.0_102; scanner xuất hiện tại `/dev/ttyACM0`. Đường dẫn có thể thay đổi sau khi cắm lại hoặc khởi động. GPI1 đã được kiểm tra: nhấn LOW, nhả HIGH. Cần xác minh lại cấu hình CDC raw, hậu tố LF và quyền truy cập trên thiết bị triển khai.

Vùng đọc phải cách ly **một tag vật lý**. Inventory theo EPC không phân biệt được hai tag trùng EPC. Ứng dụng chỉ ghi khi EPC hiện tại dài 80 bit; thay đổi độ dài EPC và PC trên MR6P vẫn cần xác minh. Chỉ báo PASS sau khi đọc lại và đối chiếu dữ liệu.

Các giới hạn và thử nghiệm còn thiếu được ghi trong [deployment-and-recovery.md](deployment-and-recovery.md). Quy trình xác minh scanner nằm trong [scanner-cdc-test.md](scanner-cdc-test.md).
