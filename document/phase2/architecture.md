# Phase 2 — kiến trúc và giới hạn

Baseline `71b761a`, Java 8. Đây là triển khai local, chưa phải nghiệm thu reader.

## Process và quyền sở hữu

```text
Zebra Application Manager / AutoStart (SAT BLOCKED)
  -> start_fx9600-writing-box.sh (foreground candidate)
  -> fx9600-writing-box -> Main --supervise <external config>
       supervisor.lock (toàn vòng đời), control.lock (start/stop handshake)
       -> Main <external config>
            station-owner.lock (giữ đến JVM exit)
            state.binding -> journal + marker cố định
            TransactionJournal Phase 1 (exclusive lock, checksum, checkpoint, fault)
            progress watchdog -> scanner/status -> SDK session fence -> RFID loop
```

Mọi instance được commissioning phải dùng cùng canonical persistent state directory.
Khóa file không bao phủ client RFID bên ngoài/ứng dụng vendor, binary trước Phase 2
hay admin đổi state root. Commissioning phải loại trừ các client đó. Không xóa file
lock khi process còn sống; lock file giữ nguyên inode qua restart.

Supervisor chỉ spawn một child, đợi child thực sự exit trước khi xét restart. Child
tự lấy station lock; supervisor mới kiểm tra orphan lock trước spawn. Parent chết
không làm orphan mất khóa. Không dùng PID file để kill; Process object chỉ trỏ
child do chính supervisor tạo. Duplicate start fail closed, exit 23.

## Restart, stop và hung process

| Exit / tình trạng | Hành vi |
|---|---|
| 0 / stop request | Dừng, không respawn |
| 20 config lỗi | Terminal; sửa config offline |
| 21 journal/recovery/startup state | Terminal; giữ toàn bộ evidence |
| 23 owner/storage/lock unavailable | Terminal; không bypass lock |
| 24 hung/session uncertain hoặc còn fence | Latch, không respawn |
| Exit khác, child đã chết và không fence | Exponential backoff, bounded restart |
| Hết budget | `restart.exhausted`, exit 25; không tự reset khi boot/start lại |

`restart.count` cập nhật force + atomic rename trước retry; pending/corrupt count
không được reset ngầm. Mặc định tối đa 3 restart trong toàn lịch sử budget hiện
tại; backoff 1/2/4 giây, cap 30 giây (cấu hình được kiểm tra range). Không tự reset
sau khoảng uptime. Terminal status ghi `supervisor.status`, không chứa barcode,
password hay config dump. `CHILD_RUNNING` chỉ xác nhận child process đang sống.

Stop ghi `stop.request`, Main tự dừng ở boundary vòng SDK, đợi worker rồi
disconnect/Dispose. Supervisor chờ timeout cấu hình; nếu không thoát, tạo
`hung.latch`, terminate riêng child và giữ ownership đến khi child exit. Nếu OS
không kết thúc process thì supervisor giữ khóa, lệnh stop trả timeout. Không có
respawn sau stop/hang. Shutdown hook của supervisor gửi cùng stop request và đợi
run loop; SIGKILL/power loss không có cam kết chạy hook.

Watchdog daemon đọc monotonic progress của SDK-owning loop, không lấy khóa
status/log và không phụ thuộc HTTP. Connect/GPI/cleanup/worker stall quá timeout
sẽ halt(24); operation guard Phase 1 vẫn halt(2). Fence còn lại biến exit 2 thành
session recovery, không crash retry. Timeout phải lớn hơn budget operation,
connect và reconnect. Không xem scanner im lặng là hung SDK.
Khi không khai báo progress timeout, mặc định là
`max(90000, max(operation.timeout.ms, reconnect.ms, reader.timeout.ms) + 1000)` ms,
giữ tương thích config Phase 1 có operation deadline lớn. Giá trị khai báo rõ quá
ngắn vẫn bị từ chối.

## Startup, session và safety

Config + canonical state root + station lock + state binding + journal được kiểm
tra trước scanner/HTTP/SDK. Journal unresolved/corrupt/fault/pending head làm child
exit 21, không mở session. Chế độ `--check-startup` chạy cùng preflight journal
nhưng không mở scanner/HTTP/reader; có thể tạo journal mới khi commissioning.
Trước cả supervisor mutation, Main kiểm tra journal/marker và mọi journal sidecar
không trùng tên runtime/control; ghim binding dưới station lock. Marker không được
trùng stop.request; journal không được trùng supervisor.status hoặc file lock/budget.
Config cũng phải có canonical path riêng, không trùng runtime, journal/sidecar/marker;
không được đặt config dưới tên supervisor.status rồi để startup ghi đè nó.

`sdk-session.pending` được CREATE_NEW + force trước cả constructor RFIDReader.
Chỉ xóa sau khi worker kết thúc, disconnect (nếu connected) và Dispose đều trả về
thành công; sync thư mục sau xóa. Constructor lỗi/cleanup lỗi/hang/crash giữ fence.
Không tự suy RF đã dừng chỉ từ JVM exit. Java return thành công cũng chưa chứng
minh cleanup native trên firmware thật: SAT vẫn là điều kiện bắt buộc.

ScannerSource chạy riêng, thiếu device báo DISCONNECTED và retry; không chặn
preflight. Tests local kiểm tra CDC node thiếu, không kiểm tra driver USB thật.
Main không ACK, replay write, sửa journal, xóa marker/fault/checkpoint hoặc tự mở
hardware gate. `RfidGateway` và capability mặc định false giữ nguyên Phase 1;
production write vẫn bị khóa vô điều kiện. HTTP có thể không mở khi startup bị
gate; dùng supervisor status/exit để chẩn đoán.

## State và release

State/config ở thư mục private bền vững, ngoài versioned release. Ví dụ `/apps/`
chỉ là vị trí dự kiến, phải xác minh flash/mount/user/ACL trên reader. `state.binding`
ghim absolute journal và marker trong cùng thư mục; không được đổi path để bỏ gate.
Giữ journal và mọi sidecar Phase 1, binding, session/hung latch và restart budget
qua upgrade/rollback. Không có rotation/repair/migration tự động.

Local tar có allowlist 10 regular files; script mode 0755, data 0644, không
symlink/path entry; SHA256SUMS và archive SHA-256; compile `--release 8`, kiểm tra
class major 52. Script được normalize LF/no BOM và `sh -n`. Checksums chống lỗi
truyền/lưu trữ, không phải chữ ký bảo mật. Gói không chứa config thật/state/secret.
Gói tar chưa phải installer Zebra và không tự cài service/init/systemd.
Metadata `rollback.requires=FXJNL01+FXRUNTIME02` bắt buộc cả journal và runtime fence
contract; checkpoint Phase 1 không phải đích rollback tự động hợp lệ.
