# ESP32 — state và ưu tiên

Reducer thật ở `include/health_ui.h`, parser thật ở `include/status_parser.h`,
được dùng chung trong firmware và host C++ checks, không viết bản mô phỏng khác.

Thứ tự: RECOVERY REQUIRED (recovery gate hoặc outcome write chưa rõ) → STATION
UNREACHABLE → WAITING FOR STARTUP khi chưa có sample trong boot grace → UNKNOWN
khi app stale → READER OFFLINE / SCANNER OFFLINE → RECOVERING → WRITING → PASS / FAIL
→ UNKNOWN thiết bị → READY khi toàn health READY và safeToTrigger → CONNECTED.

WAITING FOR STARTUP hiện lúc setup; Wi-Fi chưa kết nối hiện lỗi rõ ngay. Sau 2500ms
không có response hợp lệ hoặc Wi-Fi loss: STATION UNREACHABLE, badges UNKNOWN;
không “Đang chờ mã”, không PASS/READY cũ. App/reader/monitor mất tiến triển chuyển
UNKNOWN ở lease 1000ms cộng timer/scheduling; handler 200 hoặc revision không đổi
không thay evidence tuổi. Controller nhận sample mới kể cả cùng revision để tính
lại tuổi; duplicate/decreasing sampleSequence cùng boot bị từ chối.

Mất mạng, reader hoặc app stale/disconnect trong WRITING latch cảnh báo outcome
chưa rõ; ưu tiên trên READY/offline. Result PASS/FAIL fresh cùng boot/transaction
mới được resolve cảnh báo suy luận; gate recovery thực chỉ lấy backend journal,
không ACK trên màn hình. Boot đổi giữa write giữ cảnh báo, cần đối soát offline.
Không dùng kết quả transaction khác để xóa unknown outcome.
Transaction ID chưa được publish tại reservation là chưa biết, không phải wildcard:
cảnh báo đó không tự clear từ PASS/FAIL bất kỳ; cần operator đối soát offline.

UI: badge Wi-Fi/Reader/Scanner; dòng riêng ghi chữ R/S với state, không chỉ dùng màu.
READY/PASS xanh; CONNECTED/UNKNOWN/RECOVERING vàng; offline/fail/recovery đỏ. Giữa
màn hình là tên state rõ ràng, dưới có hướng dẫn tiếng Việt, barcode nếu còn online,
app state và txId rút gọn. Một TFT owner, cache từng dòng cả text/màu. Không đổi wiring,
font assets hoặc GPIO. Host tests chứng minh lựa chọn state, không chứng minh glyph,
độ dễ đọc/độ sáng hoặc thời gian redraw TFT thật.

Network task riêng có timeout connect/read nhưng UI không phụ thuộc timeout đó.
Mailbox dài 1 giữ snapshot mới nhất; không cam kết hiện từng event quá nhanh đã bị
coalesce. Controller dùng unsigned delta cho millis wrap; sau mất Wi-Fi cần request
mới trước khi hiển thị READY lại. Request chậm >=1000ms bị bỏ để không revive cache.
