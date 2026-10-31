# Phase 2 — kiểm thử local

Ngày 2026-10-31. Windows PowerShell, JBR/Javac 21.0.8, GNU tar 1.35 và Git sh
có sẵn. Không có Maven/jar.exe trong PATH; build dùng .NET ZIP cho class-path JAR.
Không test trên Java 8 runtime hoặc reader/ESP32 thật. Không RFID operation thật.

## Lệnh tái hiện

```powershell
$env:PATH='D:\Tool\ideaIU-2025.2.4.win\jbr\bin;' + $env:PATH
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/stage-release.ps1 `
  -OutputDirectory target/phase2-release-rebuild `
  -TarCommand D:/Tool/Git/usr/bin/tar.exe -ShellCommand D:/Tool/Git/bin/sh.exe
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-release.ps1 `
  -Directory target/phase2-release-rebuild/payload `
  -TarCommand D:/Tool/Git/usr/bin/tar.exe -ShellCommand D:/Tool/Git/bin/sh.exe
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-launchers.ps1 `
  -Directory target/phase2-release-rebuild/payload -ShellCommand D:/Tool/Git/bin/sh.exe
git diff --check
```

Output directory phải mới. `stage-release` tự chạy full verify và startup checks
từ packaged JAR. Tests tạo thư mục/child JVM local; không dùng network reader.

## Coverage

| Check | Evidence local |
|---|---|
| 7 suite Phase 1 | BarcodeFramer/Workflow/StatusServer/OperationGuard/Phase1Safety/TransactionJournal/Identity giữ toàn bộ regression, fake RF và filesystem faults |
| ProcessSupervisorCheck | Child halt(7), 3 launches với budget 2; backoff tối thiểu; latch qua invocation; clean/config exit không restart; duplicate supervisor/station/orphan; cooperative stop, stop trong backoff; stop timeout với child không đáp ứng; real watchdog halt(24) dù stdout/stderr locks bị giữ; session fence cấm retry; corrupt/pending budget cấm launch; progress renew/expire |
| StartupCheck | Main --check-startup child JVM: healthy config/scanner thiếu, duplicate owner, unresolved/corrupt journal không bị rewrite, missing/invalid config exit 20, fence exit 24, state binding drift bị chặn; ScannerSource thật thiếu CDC báo offline, retry và đóng được |
| StopControlCheck | Stop qua config có status.port=0 và stop chỉ dùng canonical state root vẫn kết thúc cooperative child; không dùng reader thật |
| Release checker | SHA256SUMS đủ file, LF/no BOM, sh -n, JAR major 52, tar flat allowlist, regular files, scripts 0755/data 0644, archive checksum, extracted payload; chạy startup checks từ packaged JAR |
| Release fault injection | 8 case: checksum tamper, CRLF có checksum đúng, thiếu file, thừa file, class major 65 có checksum đúng, mode sai, path lồng thư mục, sai hoa/thường tên file |
| Shell launcher integration | Git sh chạy exec chain với fake Java: quoting paths có spaces, config absolute, propagation exit, relative config không được gọi Java, stop không phụ thuộc unrelated checksum và state-only stop |

Startup check là offline preflight; ScannerSource được thử riêng. Chưa chạy full
Main + native SDK trên target, chưa chứng minh hotplug/boot USB hoặc RF cleanup.
Crash test là halt trên Windows, không phải brownout flash thật. Directory fsync
bị bỏ qua trên Windows vì FileChannel không hỗ trợ; Linux target phải SAT.

## Test-first và vòng sửa trước review

1. Viết ProcessSupervisorCheck trước runtime implementation: RED compilation
   do thiếu ProcessSupervisor/RuntimeState, rồi GREEN sau triển khai.
2. Viết release validator trước packaging: RED `deploy/SHA256SUMS` chưa tồn tại.
   Bổ sung package/scripts/checksums/permissions rồi GREEN.
3. Startup fixture ban đầu sai độ dài barcode; sửa fixture theo EpcCodec, không
   thay contract production. Regression GREEN.
4. Build phát hiện môi trường không có jar.exe, .NET ZIP cần assembly Compression
   và entry separator `/`; sửa build rồi validator/class loading GREEN.
5. Windows test poll status trùng atomic rename gây AccessDenied; thay test chờ
   durable budget boundary bằng exists (không giữ handle đọc status). Không nới
   production durability. Stop-start handshake được serialize bằng control lock.

Trước review: 9/9 Java suites PASS; package positive checks PASS, fault injection
7 case PASS. Raw evidence trước review: `target/phase2-evidence/build-before-review.txt`.
## Review → Fix → Retest

- **Major, collision evidence/config:** test trước sửa tái hiện supervisor xóa marker
  `stop.request`, ghi đè journal/config `supervisor.status`. Fixture journal corrupt
  bảo đảm RED không thể mở SDK. Fix validate mọi path/sidecar/config trước mutation
  và pin binding dưới station lock; GREEN kiểm tra bytes giữ nguyên + exit 20.
- **Minor, case-sensitive package:** RED package có `RUN.SH` vẫn được chấp nhận
  trên NTFS. Fix Ordinal/case-sensitive names ở checksum, actual files và archive;
  fault case GREEN (rejected).
- **Minor, metadata/stop/test:** metadata yêu cầu cả FXJNL01+FXRUNTIME02; stop đọc
  root độc lập operational config, có --stop-state và không verify unrelated payload;
  test relative config xác nhận fake Java không được gọi. Stop RED exit20 trước fix,
  GREEN cả config invalid và state-only sau fix.
- **Tương thích bổ sung:** RED cấu hình Phase 1 hợp lệ operation.timeout.ms=300000
  bị default watchdog 90000 từ chối. Default mới tăng theo timeout cũ, explicit
  deadline quá ngắn vẫn bị từ chối. Reviewer đã review delta, không finding mới.

Artifact bàn giao: `target/phase2-release-handoff.tar` và `.tar.sha256`. Raw evidence
ở `target/phase2-evidence/`: `build-handoff.txt`, `release-faults-handoff.txt`,
`launchers-handoff.txt`; RED riêng: `review-collision-red.txt`,
`review-config-collision-red.txt`, `review-case-red.txt`, `review-stop-red.txt`,
`legacy-timeout-red.txt`. Chỉ artifact handoff là bản bàn giao; các build trước được
giữ ignored để điều tra, không phải release được nghiệm thu.

Kết quả handoff: **10/10 Java suite PASS**, **8/8 release faults PASS**, shell launcher
integration **PASS**, package/class major 52/checksum/mode/LF/syntax **PASS**,
`git diff --check` **PASS**. Mọi lệnh handoff exit 0. Cảnh báo javac source/target8
obsolete và PowerShell wrapper stderr `NativeCommandError` trong logs không phải
test failure; exit code và suite verdict được kiểm tra. Không chạy mvn package.
