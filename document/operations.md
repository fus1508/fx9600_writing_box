# Vận hành trạm FX9600

> **Pha 1 (2026-10-31): đường ghi thật đang BLOCKED**, không thực hiện quy trình
> ghi của bản cũ bên dưới. Recovery chỉ được ACK offline sau khi dừng ứng dụng,
> xác nhận RF/session cũ đã dừng, cách ly tag và có tham chiếu đối soát. Xem
> [quy trình Pha 1](phase1/architecture.md) và
> [điều kiện phần cứng](phase1/hardware-blockers.md). Không xóa journal/marker để mở khóa.

Để chuẩn bị bản chạy thử, sao chép lên reader và xử lý sự cố hoặc phục hồi, làm theo [deployment-and-recovery.md](deployment-and-recovery.md). Cấu hình mẫu nằm tại [station.properties.example](../station.properties.example); mật khẩu truy cập tag được cung cấp qua biến môi trường `FX9600_ACCESS_PASSWORD`, mặc định bằng 0.

1. Xác nhận scanner CDC ở chế độ raw, hậu tố LF, reader và scanner sẵn sàng, và chỉ có một tag vật lý trong box.
2. Quét barcode hợp lệ. Trạm hiển thị `WAIT_TRIGGER`; barcode mới nhất có hiệu lực mặc định 60 giây.
3. Nhấn nút GPI1 để bắt đầu. Trạm chuyển sang `WRITING`, sau đó báo `PASS` khi EPC đọc lại khớp và giải mã khớp barcode, hoặc `FAIL` khi không hoàn tất hoặc không xác minh được.
4. Nếu `tagMayHaveChanged=true` hoặc có cảnh báo thao tác trước chưa hoàn tất, cách ly và kiểm tra tag trước khi xử lý tiếp. `EPC_RESIZE_UNVERIFIED` cho biết EPC cũ không dài 80 bit và thao tác bị từ chối.

Kết quả hiển thị 5 giây; có thể quét mã mới trong thời gian này. Mất kết nối scanner hoặc reader sẽ xóa barcode đang chờ. Trạng thái scanner chỉ phản ánh cổng và lỗi I/O, không chứng minh mọi lỗi phần cứng đều được phát hiện. Xem [station-status-and-tags.md](station-status-and-tags.md) để hiểu các trạng thái và giới hạn nhận diện tag.
