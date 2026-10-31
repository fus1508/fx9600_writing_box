# SAT plan — chuẩn bị, chưa chạy SAT

Mọi dòng phần cứng **NOT RUN / BLOCKED** ngày 2026-10-31. Test thật chỉ chạy sau
nhiệm vụ được phê duyệt riêng, đúng [preflight](hardware-preflight.md) và blockers.
Production gateway vẫn khóa; 300 RFID write cycles **không thuộc Phase 4**.

| ID / tối thiểu | Thao tác SAT sau phê duyệt | Acceptance và evidence |
|---|---|---|
| BOOT / ≥20 cold boots | Cấp nguồn từ trạng thái đã được bảo toàn, gồm boot đủ/thiếu scanner/mạng | Không cần SSH launch; đúng một owner/supervisor/SDK session, không tự RF write; ghi boot→status→UI timings, bootId và stop/start evidence từng lượt |
| LIFE / ≥30 mỗi fault phù hợp | AutoStart/start lặp/stop/restart; crash trước SDK, crash sau fence, watchdog stall, stop timeout, orphan, budget hết | Trước SDK retry bounded/backoff; có fence/hung/recovery không respawn, intentional stop không retry; duplicate rejected; state không mất |
| CDC / ≥30 unplug/replug | DS5502 đúng serial, boot thiếu, node renumber/reuse, partial frame, close/open/read stall | Không frame cũ/nhầm scanner/owner thứ hai; CONNECTED/UNKNOWN tới khi liveness verified; cleanup chưa xong không reopen; không suy idle là fault |
| RFID-LINK / ≥30 reconnect | Mất control link/SDK event, native call treo, reconnect và cleanup | Một SDK owner, không parallel GPI/access; freshness hết vẫn UNKNOWN dù HTTP200; fence giữ nếu cleanup không chứng minh |
| NET / ≥30 mỗi loại loss | Wi-Fi mất, TCP blackhole/read timeout, API chết, stale/replay/boot change, 503 load | Network loss→UNKNOWN/offline ≤3000ms **mọi mẫu**; không cached READY/PASS. WRITING bất định giữ recovery ưu tiên |
| UI / ≥300 mỗi scenario đo | Event Java không RF write, barcode hợp lệ/FAIL/health, long text, normal/load/weak Wi-Fi tách nhóm | Java event→visible redraw p95≤500ms, ghi max/missed/timeouts; không cộng p95 các đoạn; snapshot coalesced không coi là từng event đã hiển thị |
| PHYS / ≥30 mỗi thiết bị có detector | Fixture/camera ghi physical edge và visible frame cùng clock | Physical disconnect→display p95≤1000ms khi detector quan sát được; không detector đáng tin thì BLOCKED, không loại khỏi report |
| RF-ID / mọi fixture | 0/1/2 tag, duplicate EPC/full TID, ngoài box/swap/RF yếu, đúng antenna đã commission | Discovery đủ so ground truth, same identity read EPC/TID/PC, fail closed với partial/locked TID; chỉ read sau protocol review |
| STORAGE / từng boundary | Mô phỏng trước; power/brownout thật chỉ nhiệm vụ có fixture riêng | Unresolved/corrupt/pending/fault đều gate sau boot, không auto ACK/replay hoặc false PASS; backup cả bộ evidence |
| UPGRADE/ROLLBACK | Stop/quiesce đã chứng minh, version dir mới, cùng config/state root | FXJNL01+FXRUNTIME02 được giữ; không binary cũ song song, không restore journal cũ đè state mới; mọi fence/budget/recovery giữ nguyên |

Tách rõ link reader control-plane với antenna/RF failure; antenna mất có thể không
có detector subsecond. Không gọi reader READY là RF READY. Mọi lần thất bại đều
có evidence, không bỏ lượt để đạt minimum. Khi chưa có detector ghi limitation và
block metric đó; không claim toàn bộ hardware acceptance.

## Thu thập latency bằng external clock

1. Camera/logic analyzer có clock chung thấy event marker và TFT. Không thêm GPIO
   hoặc firmware instrumentation vào thiết bị trong Phase 4. Java event timestamp
   cần trace ID/revision và phép ánh xạ tới external clock được kiểm chứng; đo độ
   bất định tổng. Nếu chưa có marker/synchronization, metric event-redraw BLOCKED.
2. Lưu bootId/revision/sequence/transactionId và camera frame/reference vào evidence
   chi tiết. Mỗi input event có ID và outcome `ok/timeout/missed/coalesced`; API
   latest snapshot không hứa hiển thị mọi event. Pace events để quan sát được;
   ghi riêng stress/coalescence, không bỏ lost samples khỏi denominator.
3. Điền [latency-samples.csv](templates/latency-samples.csv): cùng start_clock và
   end_clock, ms đã unwrap, uncertainty_ms gồm clock alignment + frame resolution.
   Không trừ Java nanoTime với ESP millis. Nếu dùng offset mapping, chuyển cả hai
   sang external reference và lưu calibration; tên clock bằng nhau không tự chứng minh
   đã sync. External evidence phải được người review kiểm tra.
4. Chạy offline `python scripts/sat_latency.py <csv>`; stdout JSON, exit0 chỉ khi
   mọi nhóm trong input đủ mẫu và đạt bound, exit1 FAIL/BLOCKED, exit2 invalid data.
   Tool không xác nhận đủ coverage toàn SAT; so cả test matrix và inventory.
5. Tool dùng nearest rank và **duration + uncertainty**; event-redraw N≥300 p95≤500,
   physical-display N≥30 p95≤1000 từng device/scenario, network-unknown N≥30 max≤3000.
   Duplicate/khác clock/NaN/inf/negative/provenance thiếu bị từ chối. Lost/timeout/
   coalesced khiến FAIL; không trộn SIMULATED/HARDWARE. Kết quả luôn
   hardware_acceptance=NOT_ASSESSED, cần operator/reviewer ký từ raw evidence.

Fixture tests của tool chỉ là SIMULATED, không sample SAT. Header-only template
không chứa số liệu giả; phân tích nó phải báo No samples/SAT NOT RUN.

## Transaction recovery/brownout (procedure, không thực hiện)

Local hiện có fault injection PREPARED/SELECTED/WRITE_INTENT/WRITE_RETURNED/VERIFIED,
partial append/fsync, journal→head.pending→rename→directory sync và checkpoint
mismatch. Power-loss FX flash không được suy từ process halt Windows.

SAT riêng phải định nghĩa trigger/camera/evidence ở từng boundary trên, trước/sau
durable result, sau native write partial và khi SDK/session cleanup treo. Dừng
supervisor theo contract, xác minh RF đã ngừng, quarantine toàn bộ tag nghi vấn;
reboot không cho replay hay xóa marker/fault/head/fence. Scan/reconnect không clear.
Đọc/đối soát độc lập; ACK offline chỉ sau authorization và evidence reference riêng.
Journal hỏng/pending/fault không có tool repair được nghiệm thu: giữ gated.
300 chu kỳ ghi với tag disposable, wrong-tag/duplicate/partial write/read-back
và EPC80/PC chỉ thực hiện trong mission RFID SAT riêng có phê duyệt ghi cụ thể.

## Evidence & rollback

Dùng [inventory](templates/device-inventory.csv), [runs](templates/sat-runs.csv),
[evidence record](templates/evidence-record.md); lưu archive/firmware/input hashes,
tool versions, raw video/log/CSV, exceptions, counts và operator/reviewer sign-off.
Mask secrets trong bản chia sẻ; raw trace có barcode/EPC chỉ ở kho được cấp quyền.

Khi Major/Critical, wrong identity, false READY/PASS hoặc evidence corruption: dừng
lượt thử, giữ box/tag và toàn state, không reset latch/budget để tiếp tục. Disable
AutoStart/stop chỉ theo lifecycle đã SAT và phê duyệt; stop timeout phải chứng minh
RF cleanup trước activation khác. Backup gồm config, journal/.head/.head.pending/
.fault/marker, state.binding(.pending), sdk-session.pending, hung.latch, restart.*,
supervisor.status(.pending), stop.request và lock files/permissions. Không xóa lock.
Rollback chỉ tới release hiểu FXJNL01+FXRUNTIME02, cùng state root, sau quiescence;
không deploy Phase1/pre-Phase1 hoặc restore snapshot cũ đè evidence hiện hành.
Không có rollback tương thích được xác minh thì giữ trạm dừng/gated.
