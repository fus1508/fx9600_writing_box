# Phase 3 — kết quả kiểm thử local

2026-10-31, Windows PowerShell, JBR/javac 21.0.8. Baseline Phase 2 đã có commit
`e7714ea`; 10/10 suite trước thay đổi PASS. Lần gọi đầu vướng execution policy và
PATH thiếu javac; dùng Bypass chỉ cho process và JBR đã có, không đổi policy máy.

Test-first: DeviceHealthCheck/HealthApiCheck compile RED do chưa có DeviceHealth;
ScannerHealthCheck compile RED do chưa có Node/Access. Sau triển khai GREEN. UI
reducer tests được viết trước reducer; compiler được chuẩn bị sau. Parser test
bổ sung sau triển khai, không ghi nhận giả một lượt RED C++ chưa thực thi. Ca UI
correlation bổ sung tái hiện RED thật: PASS transaction khác xóa unknown outcome;
giữ boot/tx tại thời điểm latch rồi GREEN. Evidence `ui-correlation-red.txt`.
Portable Zig 0.13.0 tải từ ziglang.org và kiểm
SHA256 theo index, chỉ giải nén trong target; ArduinoJson single header đúng 6.21.5
từ release upstream chỉ dùng host dưới target. Không cài tool global.

| Checks | Kết quả thực tế |
|---|---|
| 10 regression Phase 1/2 | PASS, gồm journal corruption/crash/checkpoint/ACK, write/read-back giả, watchdog không chờ status/log, supervisor restart/stop/budget/fence/lock/startup |
| DeviceHealthCheck | PASS, fake clock: SDK connect chưa READY, GPI/app stall 1000ms→UNKNOWN, callback fault, reconnect, CDC idle/port-open/decode không READY, stop sticky |
| HealthApiCheck | PASS, localhost: schema v2/boot/age, HTTP 200 không gia hạn dead backend, recovery/terminal app, giữ WRITING khi disconnect, 16 long-poll clients và ordinary GET |
| ScannerHealthCheck | PASS, injected blocking CDC: unplug/replug, identity change, partial frame discard, old fd không emit scan, close bị latch chặn không reopen, close tối đa một lần, close throw giữ gate/reason, từ chối regular file; barrier post-open không hồi sinh session đã invalidate |
| health_ui_check.cpp | PASS C++11 thực thi reducer firmware: 1000 transitions, Wi-Fi loss/reconnect, stale/replay/timeout, recovery, WRITING outcome giữ đúng boot/tx, tx rỗng không wildcard, boot change, full millis wrap và outage >24 ngày |
| status_parser_check.cpp | PASS C++11 + ArduinoJson 6.21.5: JSON thực từ Java HTTP → parser → reducer; version/type/length/enum/progress/safe gate lỗi bị từ chối |
| Java --release 8 | PASS production/test qua verify và release build, packaged classes major52 |
| Artifact/scripts | PASS checksum/LF/no BOM/sh syntax/allowlist/POSIX modes/extract/startup từ packaged JAR, 8/8 package faults rejected, fake Java launcher integration |
| git diff --check | PASS |
| PlatformIO / target Java8 / TFT / SDK/tag thật | NOT RUN/BLOCKED; không có deploy/upload/RFID thật |

StatusServerCheck vẫn kiểm tra safety lease 250ms renew/expire của workflow qua
diagnostic `workflowSafeToTrigger`. `safeToTrigger` station thêm health gate nên
không cho true từ scanner plain CDC. Assertion scanner cũ được cập nhật CONNECTED,
heartbeat 400ms; không bỏ journal/recovery/read-back assertions hoặc sửa domain.

Lệnh tái hiện:

```powershell
$env:PATH='D:\Tool\ideaIU-2025.2.4.win\jbr\bin;' + $env:PATH
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1
java -cp 'target/classes;target/test-classes' org.fus.HealthLatencyCheck
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-screen.ps1 `
  -Compiler target/phase3-tools/zig-windows-x86_64-0.13.0/zig.exe -Zig `
  -JsonInclude target/phase3-tools/json
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/stage-release.ps1 `
  -OutputDirectory target/phase3-release-handoff-final `
  -TarCommand D:/Tool/Git/usr/bin/tar.exe -ShellCommand D:/Tool/Git/bin/sh.exe
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-release.ps1 `
  -Directory target/phase3-release-handoff-final/payload `
  -TarCommand D:/Tool/Git/usr/bin/tar.exe -ShellCommand D:/Tool/Git/bin/sh.exe
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-launchers.ps1 `
  -Directory target/phase3-release-handoff-final/payload -ShellCommand D:/Tool/Git/bin/sh.exe
```

Staging cần output mới; không ghi đè artifact. Raw evidence ignored:
`target/phase3-evidence/{build-review,screen-review,release-faults-review,launchers-review}.txt`,
`event-http.csv`, `latency-summary.txt`, `java-status-fixture.json`.
Các warnings source/target8 của JBR và NativeCommandError cho expected rejection
CONFIG_PATH_MUST_BE_ABSOLUTE không phải test FAIL; exit codes đã được kiểm tra.

Benchmark lượt đầu N300, missed0, timeout0: p50=457us, p95=802us, max=5373us.
Chỉ **SIMULATED localhost Java-event→HTTP body**; không gọi đây là p95 ESP32 redraw.
Hardware latency, PlatformIO build và real RF toàn bộ NOT RUN.

Independent review và retest cuối được ghi ở [independent-review.md](independent-review.md).

Review → Fix evidence: `close-gate-red.txt` (read trả về nhưng close treo vẫn reopen),
`empty-tx-red.txt` (tx rỗng xóa unknown outcome), `activation-race-red.txt` (inspect
đã chụp trước unplug hồi sinh session invalid). Ba case đều FAIL thật trước fix;
cleanup completion + at-most-once, strict tx match và marker invalidated dưới cùng
stateLock làm GREEN. `activation-race-green.txt`, `java-fix.txt`, `screen-fix.txt`
là evidence các vòng sửa. Không dùng snapshot test trùng code để che regression.

## Retest bàn giao cuối

Sau sửa activation và reviewer APPROVE, chạy build từ source cuối vào **output mới**
`target/phase3-release-handoff-final`; không ghi đè candidate cũ. Tất cả lệnh exit 0:

- `build-handoff-final.txt`: compile toàn production/test `--release 8`, đủ 13/13
  suites PASS gồm regression Phase 1/2/3, cleanup/activation race trong
  ScannerHealthCheck và startup từ packaged JAR; package
  checksum/allowlist/LF/no BOM/sh syntax/major52/modes/extract PASS.
- `release-faults-handoff-final.txt`: 8/8 faults rejected.
- `launchers-handoff-final.txt`: fake Java shell integration PASS.
- `latency-handoff-final.txt`: N300, missed0, timeout0, p50=728us, p95=1019us,
  max=8675us. Raw `event-http.csv`/`latency-summary.txt` là lượt cuối này.
- `screen-handoff-final.txt`: rebuild C++11 `-Wall -Wextra -Werror`, 2/2 checks PASS
  với **JSON mới từ API Java của lượt cuối**, gồm parser/correlation/freshness/wrap tests.

`git diff --check` PASS, staged rỗng, protected domain/runtime/OperationGuard/deploy/
config mẫu/vendor JAR không đổi. Benchmark cũ 802us chỉ là lịch sử, không lấy thay
lượt cuối 1019us. Không cộng các p95 để suy hardware end-to-end.

PlatformIO không có trong PATH/toolchain hiện có nên **firmware build NOT RUN**;
chưa thực thi target Java8/native SDK hoặc TFT/RFID thật. Host C++ chỉ kiểm parser/
reducer, không chứng minh display rendering/scheduling/latency hay scanner liveness.
