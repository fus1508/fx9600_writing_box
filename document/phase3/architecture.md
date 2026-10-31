# Phase 3 — kiến trúc health

Baseline `e7714ea`. Chỉ triển khai local, không mở production write.

```text
SDK owner (Main) -- connect / GPI đã trả về --> DeviceHealth atomic evidence
SDK callback -- disconnect/exception ----------^ (không gọi SDK/workflow/log)
Scanner read + node monitor ------------------^ (node/port/decode riêng)
                                                |
workflow/journal --> StatusServer safety + result | snapshot monotonic
                       |                        |
                  HTTP GET workers (8, queue 16, AbortPolicy)
                  long-poll workers (4, không queue, overflow 503)
                       | schema v2, no-store, heartbeat <=400ms
                  ESP32 HTTP task --> mailbox một snapshot
                  ESP32 loop --> Controller + TFT, mỗi vòng nghỉ 20ms
```

`DeviceHealth` không gọi SDK/filesystem/HTTP/log hoặc giữ khóa workflow. Chỉ một
owner SDK; GPI vẫn dừng trong operation. Lấy evidence reader sau GPI hợp lệ hoàn
tất; connect riêng chỉ CONNECTED. Callback làm session unusable và publish fault
atomic ngay cả khi GPI chưa trả về. Fault đầu được giữ đến lần connect mới.
Không có inventory/probe RFID bổ sung, không đổi EPC/PC/TID.

Tuổi bằng `nanoTime`, expiry 1000ms, không dùng wall clock. Snapshot tự tính lại
health; HTTP 200, revision hoặc timer health không gia hạn progress. Timer status
50ms chỉ đánh thức waiter khi derived state/lease đổi. Nếu timer dừng, snapshot
vẫn tính expiry; nếu cả HTTP dừng, deadline độc lập phía ESP32 loại READY.
Trong operation dài, GPI không tiến triển nên reader có thể UNKNOWN; đây là chủ
đích bảo thủ, không thêm SDK concurrency để giữ màu xanh. App progress chỉ được
gia hạn sau một vòng điều khiển hoàn tất. Watchdog fatal Phase 2 không phụ thuộc
health/status timer và không sửa logic deadline của watchdog/operation guard.

Scanner có một owner read. `Access` là seam dùng fake transport trong checks;
production yêu cầu character node Unix, fileKey và real path. Có thể commissioning
`scanner.device` là symlink ổn định đã xác minh; không dò mọi tty hay chọn nhầm ACM
khác. Monitor so node identity mỗi 250ms, invalidate session trước async close,
không chuyển phần barcode còn dở sang session mới. Byte từ fd đã invalidate bị bỏ.
Closer một daemon + queue 2, không tạo luồng vô hạn; close bị treo không chặn monitor.
Mỗi session close tối đa một lần; chỉ reopen sau close trả về thành công. Cleanup
pending giữ RECOVERING, cleanup throw giữ UNKNOWN/CDC_CLEANUP_FAILED và chặn reopen.
Run-loop giữ session đến cleanup completion; executor chỉ shutdown sau loop, không
bỏ close của stream vừa mở khi shutdown chạy đồng thời.
Activation và invalidation cùng dùng stateLock; marker invalidated được đặt trước
publish/async close. Kết quả inspect đã chụp trước unplug không hồi sinh session cũ.
Nếu read/open vẫn treo thì không tạo owner khác để reopen: health hết hạn, ghi
BLOCKED cho SAT driver/JVM. Retry reopen vẫn dùng `reconnect.ms` hiện có (3s mẫu).
Node identity không chứng minh DS5502 USB serial; `usbPresence=UNKNOWN` tách biệt.
Raw/LF/quyền/exclusive port và renumber cần commissioning, không tự đổi thiết bị.

Status state và safety được lấy dưới khóa ngắn; không socket I/O/log/SDK dưới khóa.
Waiter giữ response của riêng mình nên long polling không chiếm ordinary GET pool.
Queue bounded và bỏ CallerRunsPolicy tránh biến dispatcher thành blocking waiter.
Đây là bảo vệ saturation trong LAN; chưa chứng nhận chống mọi slow-client/DoS.
Không có authentication/TLS mới; giữ LAN/ACL theo baseline.

ESP32 network task không chạm TFT/controller. Loop đọc mailbox không chờ HTTP,
kiểm tra Wi-Fi và expiry, vẽ các dòng đổi nội dung/màu. Schema/types/enum/ages và
identity của response được kiểm tra; cùng boot, sampleSequence phải tăng, revision
có thể giữ nguyên. Không nhận response mất >=1000ms, kể cả thời gian trong mailbox;
tuổi evidence cộng toàn bộ thời gian request một cách bảo thủ. Sau Wi-Fi loss phải
có request mới sau loss mới khôi phục READY. Đây là bound logic, chưa đo scheduling/TFT.

Journal, ACK offline, session fence, singleton, restart/stop/latch/budget giữ nguyên.
Preflight safety gate vẫn có thể chặn Main trước HTTP; màn hình lúc đó chỉ biết
STATION UNREACHABLE. Supervisor terminal reason vẫn ở durable `supervisor.status`,
không có endpoint thứ hai giả READY khi child chết.
