# DS5502 readiness

Target DS5502 firmware/SKU/USB VID/PID/serial/host mode chưa được đọc lại.
**HARDWARE VERIFICATION REQUIRED**; Linux FX9600 không được suy tương thích từ
CDC driver Windows hoặc tên SDK Linux.

Nguồn chính thức thử ngày 2026-10-31:

- [DS5502 PRG](https://www.zebra.com/content/dam/support-dam/en/documentation/unrestricted/guide/product/ds5502-prg-en.pdf):
  fetch 406; **NOT RUN / BLOCKED** cho xác minh đầy đủ revision/firmware contract.
  Audit cũ có bản mirror Zebra-authored nhưng nhiệm vụ này không nâng mirror thành
  bằng chứng driver/kernel hay command protocol của scanner thực.
- [RTA guide](https://techdocs.zebra.com/dcs/scanners/rta/dev-guide/),
  [supported firmware](https://techdocs.zebra.com/dcs/scanners/rta/sample-apps/):
  **VERIFIED FROM VENDOR DOCUMENTATION**: cần model/firmware hỗ trợ và transport
  phù hợp; SSI/SNAPI/RSM khác plain CDC decode. Bảng đã đọc chưa liệt kê DS5502;
  đó là thiếu bằng chứng hỗ trợ, không khẳng định scanner chắc chắn không hỗ trợ.
  Idle alert có ngưỡng theo phút, không chứng minh subsecond liveness.

**VERIFIED LOCAL:** ScannerHealthCheck dùng injected blocking transport kiểm
unplug/replug/renumber identity, bỏ partial frame/bytes fd cũ, invalidation race,
close once, read trả về nhưng close treo vẫn chặn reopen, close exception giữ gate.
Production yêu cầu Unix character node và fileKey, monitor mỗi 250ms, không quét
tất cả ACM hay mở owner thứ hai để né blocked read/close. Raw/LF provision bên ngoài.

**INFERENCE / giới hạn:** fileKey/real path nhận diện node OS, chưa là DS5502 serial;
TOCTOU giữa inspect/open và kernel tái dùng node chưa được chứng minh an toàn trên
target. FileInputStream không có TIOCEXCL/native exclusive ownership; cooperative
station lock không ngăn process khác đọc tty. Close/read/open deadlines phụ thuộc
JVM/kernel/driver; local fake không xác nhận đóng fd thật.

Giữ CONNECTED/readiness UNKNOWN và usbPresence UNKNOWN; port-open, decode gần đây
và operator idle không nâng READY. Không gửi SSI/RSM/SNAPI/RTA hay ESC thử nghiệm.

SAT read-only/provisioning review riêng cần:

1. Lưu model/SKU/FW/VID/PID/serial/cáp bằng OS descriptors; đối chiếu label/PRG đúng
   revision. Xác nhận stable by-id symlink nếu có, quyền app và persistence qua boot.
2. Đọc termios bằng `stty -a` chỉ sau khi xác nhận không tranh chấp fd; raw/LF bằng
   capture scanner đã provision ở bench riêng. Không dùng `cat /dev/ttyACM*` lúc
   app sở hữu port. Không tự sửa stty, suffix hoặc host mode trong Phase 4.
3. ≥30 unplug/replug, node renumber/tái dùng, boot thiếu scanner, partial barcode
   trước/sau unplug; chỉ đúng serial được mở và không nhận scan từ fd cũ.
4. Đo blocking read/close/open thật; khi cleanup không xác minh thì giữ gated.
   Chốt chính sách exclusive owner/native bridge trong thay đổi được review riêng.
5. Chỉ thiết kế liveness query sau khi có opcode/framing/checksum/correlation/
   timeout và compatibility DS5502-FW/host cụ thể; phân biệt decode traffic và reply.

Không có production scanner query implementation mới; chưa đủ evidence để READY.
