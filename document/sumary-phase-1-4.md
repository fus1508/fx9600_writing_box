# RFID Writing Box — Tóm tắt tiến độ Phase 1–4

**Ngày cập nhật:** 31/10/2026  
**Thiết bị mục tiêu:** Zebra FX9600, máy quét barcode DS5502, màn hình ESP32-S3.  
**Trạng thái:** Đã hoàn thành **phát triển và kiểm thử cục bộ** Phase 1–4. **Chưa triển khai hoặc nghiệm thu trên phần cứng thật.**

## Những gì đã thực hiện

### Phase 1 — An toàn ghi RFID và dữ liệu
- Bổ sung **transaction journal** để lưu lịch sử và trạng thái giao dịch bền vững.
- Thêm **recovery gate**: chặn ghi tiếp khi giao dịch trước chưa xác định kết quả; có quy trình xác nhận xử lý offline.
- Ngăn ghi lặp/song song và báo **PASS** khi chưa xác minh kết quả; bổ sung kiểm tra định danh tag/TID ở mức mô phỏng.
- **Kết quả:** 7/7 bộ kiểm thử Java PASS; reviewer chấp thuận phạm vi local.

### Phase 2 — Tự khởi động và phục hồi ứng dụng
- Xây dựng **supervisor** để quản lý tiến trình Java, restart có giới hạn và backoff.
- Bổ sung **singleton lock, session fence, watchdog**, launcher/stop và quy trình rollback an toàn.
- Chuẩn bị release package và kiểm thử các tình huống crash, stop, restart, lỗi cấu hình.
- **Kết quả:** 10/10 bộ kiểm thử PASS; 8/8 ca lỗi package PASS; reviewer chấp thuận phạm vi local.

### Phase 3 — Giám sát thiết bị và giao diện
- Xây dựng **health model** cho reader, scanner và ứng dụng; mở rộng **HTTP Status API v2**.
- Cập nhật màn hình ESP32 với trạng thái kết nối, lỗi, phục hồi và cảnh báo giao dịch chưa rõ kết quả.
- Xử lý dữ liệu trạng thái cũ, tránh hiển thị **READY** khi không đủ bằng chứng; tách xử lý mạng khỏi vòng cập nhật màn hình.
- **Kết quả:** 13/13 bộ Java và 2/2 bộ C++ PASS; reviewer chấp thuận phạm vi local.

### Phase 4 — Chuẩn bị tích hợp và kiểm thử phần cứng
- **Build thành công firmware ESP32-S3 bằng PlatformIO** (cấu hình kiểm tra, chưa nạp lên thiết bị).
- Chuẩn bị release candidate có **checksum**, kiểm tra tính tái lập, launcher, supervisor và rollback.
- Xây dựng công cụ đo độ trễ, checklist thiết bị, kế hoạch SAT và biểu mẫu ghi nhận kết quả.
- **Kết quả:** 13/13 bộ Java, 2/2 bộ C++, 13 bài kiểm tra công cụ latency và 12/12 ca lỗi package PASS; reviewer chấp thuận phạm vi local.

## Hiện trạng và bước tiếp theo

- **Đã có:** mã nguồn, kiểm thử tự động, firmware build được, release candidate và tài liệu chuẩn bị thử nghiệm.
- **Chưa xác minh:** Zebra AutoStart trên firmware thực, Java/native SDK, định danh TID và chống ghi nhầm tag, scanner DS5502, độ bền lưu trữ khi mất điện, khả năng hiển thị và độ trễ trên màn hình thật.
- **Chưa thực hiện:** deploy, nạp firmware lên ESP32 thật, kiểm thử RFID write và Hardware SAT.
- **Bước tiếp theo:** đồng nghiệp có thiết bị thực hiện **hardware preflight/read-only**, sau đó tiến hành các bài bench/SAT khi được phê duyệt.

> **Lưu ý an toàn:** Chức năng **ghi RFID trên thiết bị thật vẫn đang bị khóa**. Release candidate hiện tại **chưa phải installer Zebra đã được xác nhận tương thích** và chưa được phép dùng cho production.
