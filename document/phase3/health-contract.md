# Status contract v2

GET `/status`, GET `/status/wait?since=<revision>`, HTTP 200 JSON UTF-8 no-store;
4 waiter tối đa, thêm waiter trả 503, heartbeat tối đa 400ms (trước đây 2s).
Không control, ACK hay SDK call trong handler.

| Trước (Phase 2) | Sau (Phase 3) |
|---|---|
| revision, state, scanner, reader, barcode, epc, error | Giữ tên và kiểu |
| recoveryRequired, safeToTrigger, transactionId, safetyReason | Giữ; safeToTrigger thêm gate health, không nới safety |
| scanner/reader READY từ workflow connection | Lấy derived health; có thêm CONNECTED/UNKNOWN/RECOVERING |
| Không version/boot/freshness | schemaVersion=2, bootId, sampleSequence, sampleUptimeMs, freshnessLimitMs=1000, health |
| Safety lease <=250ms | Giữ; workflowSafeToTrigger chỉ là diagnostic lease của workflow, không quyền ghi |

Client cũ vẫn đọc trường cũ nhưng không được coi CONNECTED là READY. ESP32 Phase 3
từ chối schema cũ. Cần nâng Java + ESP32 cùng đợt commissioning riêng. Response
v2 lớn hơn giới hạn 1024 byte cũ; firmware nhận Content-Length 1..8192, JSON arena
6144 byte, mọi chuỗi có bound riêng. Không bỏ safety chỉ vì payload không hợp lệ.

`state` vẫn IDLE / WAIT_TRIGGER / WRITING / PASS / FAIL. Connection loss xóa barcode
đang chờ; giữ WRITING tới khi workflow có outcome, tránh che giao dịch chưa xong.
Recovery không bị scan/connection/HTTP xóa. PASS vẫn chỉ do workflow sau durable
VERIFIED và read-back; API không tự suy PASS từ health hay transactionId.

`bootId` UUID mỗi StatusServer/process instance, `revision=bootId-counter` chỉ đổi
khi status/safety/derived health đổi. `sampleSequence` tăng mỗi response snapshot,
kể cả cùng revision. `sampleUptimeMs` và ages là monotonic duration trong JVM,
không phải UTC và không được trừ trực tiếp với millis ESP32. Ages chưa có = -1.
Sample cũ/lặp không gia hạn freshness của màn hình.

`health.reader`, `health.scanner`, `health.application` cùng shape:

```json
{"connection":"CONNECTED","readiness":"UNKNOWN","healthState":"CONNECTED",
 "observedAgeMs":12,"lastProgressAgeMs":-1,"reason":"CDC_LIVENESS_UNVERIFIED",
 "evidence":"OS_NODE_AND_CDC","safeToOperate":false,"portOpen":true,
 "identity":"configured-real-path:OS-file-key"}
```

connection là bằng chứng kết nối cuối: CONNECTED/DISCONNECTED/UNKNOWN; có thể còn
CONNECTED khi health đã UNKNOWN. readiness READY/NOT_READY/UNKNOWN, tách connection.
healthState dùng đúng năm giá trị sau:

| State | Bằng chứng |
|---|---|
| UNKNOWN | Chưa có evidence hoặc observed/progress >=1000ms |
| RECOVERING | Owner đang connect/reopen; chưa hoạt động |
| CONNECTED | SDK session hoặc CDC port mở, thiếu progress/liveness đủ chuẩn |
| READY | Reader: GPI hợp lệ đã trả về và còn mới; application: control loop hoàn tất còn mới |
| DISCONNECTED | SDK disconnect/error, node/CDC lỗi hoặc application terminal stop |

Fault DISCONNECTED giữ tới connect/reopen mới, không biến thành READY bởi heartbeat.
`safeToOperate` là khả năng của **phạm vi evidence**: reader control-plane hoặc
application loop, không chứng nhận RF/antenna/tag identity/ghi. False khi app stop.
Scanner plain CDC luôn false, không có đường nâng READY chỉ từ decode hay idle.
Quyền trigger toàn station chỉ lấy `safeToTrigger`, còn cần toàn bộ gate Phase 1.
Production gateway vẫn unsupported nên luôn false; không có config bypass.

`health.lastDecodeAgeMs` là quan sát data riêng, không dùng thời gian không quét để
kết luận scanner hỏng. `health.usbPresence=UNKNOWN`: node/port không phải USB probe
được xác minh. Identity là OS node, không phải USB serial hay DS5502 attestation.
Các error/evidence có bound; không chứa access password/Wi-Fi credentials.
