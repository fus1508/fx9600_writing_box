# Kết quả kiểm tra cục bộ — 2026-10-31

**PASS phạm vi local; hardware SAT NOT RUN/BLOCKED.** Không RFID write/erase thật,
không kết nối reader, không deploy FX9600/ESP32, không đổi cấu hình thiết bị.

## Môi trường và cách tái hiện

Windows PowerShell, JBR OpenJDK 21.0.8+1-1038.73-jcef đã có sẵn tại
`D:\Tool\ideaIU-2025.2.4.win\jbr\bin`. Không cài tool mới. Java/Javac ban đầu không
có trong PATH; chỉ thêm vào environment của tiến trình kiểm tra:

```powershell
$env:PATH='D:\Tool\ideaIU-2025.2.4.win\jbr\bin;' + $env:PATH
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1
```

Script tự chọn `--release 8` trên JDK9+, `-source 8 -target 8` trên JDK8 thực; compile
cả production và test rồi chạy đủ bảy standalone checks. Không chỉ dùng `mvn test`.
Lần retest cuối **exit 0**, cả bảy suite PASS. Cảnh báo javac về source/target 8
obsolete là cảnh báo compiler, không bỏ qua lỗi test. Không có runtime Java8/reader
để thực thi trên thiết bị; `--release 8` chứng minh API/bytecode compile, không SAT.

Kiểm tra compile riêng (đã thực hiện, exit 0):

```powershell
$production = @(Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
$checks = @(Get-ChildItem src/test/java -Recurse -Filter *.java | ForEach-Object FullName)
javac -encoding UTF-8 --release 8 -cp library/Symbol.RFID.API3.jar -d target/phase1-release8/classes $production
javac -encoding UTF-8 --release 8 -cp 'target/phase1-release8/classes;library/Symbol.RFID.API3.jar' -d target/phase1-release8/test-classes $checks
git diff --check
```

Tất cả class sinh trong lượt compile riêng có major version 52. Raw local evidence
ở `target/phase1-evidence/` (ignored, không phải source bàn giao); mã test có thể chạy
lại độc lập qua verify script. Maven không có trong PATH nên không chạy `mvn package`.

## Suite và phạm vi thực sự

| Suite | Kiểm tra đã chạy | Kết quả |
|---|---|---|
| BarcodeFramerCheck | Framing, timeout/overflow theo contract cũ | PASS, thuần Java |
| WorkflowCheck | Codec/BCD/zero đầu, barcode hết hạn/tiêu thụ/thay thế, disconnect, nhiều tag giả lập, reservation, result timeout, GPI debounce | PASS, fake RF |
| StatusServerCheck | Long-poll/heartbeat/ordinary GET, escape JSON, warning không mất sau scan/disconnect, recovery/safety fields, lease expire khi stall, renew khi tick còn tiến triển | PASS, localhost |
| OperationGuardCheck | Marker cũ không bị ghi đè, timer generation, timeout giữ evidence, child halt(23), production fatal deadline halt(2) trong khi giữ khóa status/stdout/stderr | PASS, file tạm/child JVM |
| Phase1SafetyCheck | Durable intent đủ metadata trước write; durable VERIFIED trước PASS; một reservation chạy tối đa một lần; partial write tự khai changed=false vẫn gate; read lỗi/mismatch; disconnect/shutdown; unknown identity/EPC96/EPC128 bị chặn; concurrent scan/trigger và abort | PASS, fake RF |
| TransactionJournalCheck | 5 crash boundaries; 3 crash giữa force journal/head update; 18 lỗi disk-full/partial-write/fsync; lỗi checkpoint trước/sau RF, atomic rename tại VERIFIED; ACK lỗi/stale/invalid/online; corrupt tail/hash/length/header; truncate đúng boundary; head thiếu/hỏng/pending, journal mất; legacy import/ACK/marker mới; exclusive lock/path lỗi; offline Main ACK và reopen | PASS, filesystem tạm/child JVM, lỗi đĩa được inject |
| IdentityCheck | Duplicate EPC/TID khác bị từ chối, thiếu serial/unverified discovery/unstable EPC/no tag/HEX sai; lặp observation cùng identity; production capability mặc định false; reflection public SDK không khởi tạo native | PASS, policy giả lập và public API inspection |

Lỗi disk-full/fsync được inject tại Appender, không làm đầy ổ đĩa thật. Rename failure
được tạo trong thư mục test bằng destination `.head` là directory. Process halt
không mô phỏng cache flash mất điện. Các test success RF chỉ dùng fake adapter có
attestation rõ ràng; không chứng minh TID full hoặc selection SDK thực.

## Bằng chứng test-first và vòng sửa

1. Baseline: bốn suite cũ PASS, `verify.ps1` exit 0.
2. Regression RED trước thay đổi hành vi: `scan cleared recovery`,
   `existing marker overwritten`, `reservation replayed RF write`.
3. Journal failure RED: `journal failure lost after restart PREPARED/0`; thêm
   persistent fault latch, rồi PASS. Các lỗi append cũng bị checkpoint mismatch chặn.
4. Independent review phát hiện complete-prefix truncation. Thêm test trước sửa:
   RED `complete-prefix truncation lost unresolved transaction`. Thêm durable head,
   mở rộng missing/corrupt/checkpoint-gap tests, PASS.
5. Review phát hiện lease chưa renew khi tick bình thường. Test trước sửa RED
   `healthy progress did not renew lease`; publishSafety mỗi tick rồi PASS.
6. Fix và full retest sau review: bảy suite PASS, compile riêng `--release 8` PASS,
   `git diff --check` PASS. Không còn test local FAIL tại handoff.

## SDK evidence và giới hạn

JAR SHA-256:
`B1929F3686357E3A61ECF8A817446C10C89906994DD7648BBE11FA4EB7623FE4`.
IdentityCheck in chính xác các overload synchronous/asynchronous. Probe chỉ reflection
thêm `PreFilters`, `PreFilter`, `AccessFilter`, `TagPatternBase`, `TagData`,
`TagStorageSettings`; log ở `target/phase1-evidence/sdk-surface.txt`. Không decompile,
không gọi inventory/access, không suy SDK implementation từ signatures.

Full TID discovery/selection, enclosure, MR6P/EPC/PC, filesystem brownout, RF còn chạy
sau crash, operator quarantine và commissioning: **NOT RUN/BLOCKED**, xem
[hardware-blockers.md](hardware-blockers.md). Không dùng local PASS để mở hardware gate.
