# Phase 2 — completion report

Baseline Phase 1 `71b761a`; branch `feature/fx9600-phase2-autostart` được tạo từ
checkpoint đã phê duyệt. Không commit/stage/push/merge/main/worktree/deploy.
Hai file audit untracked và toàn bộ tài liệu audit/Phase 1 được giữ nguyên.

Phần local đã có supervisor/ownership/session fence/progress watchdog, launcher,
stop script, tar release verifier và commissioning/rollback runbook. Giữ nguyên
production RFID write lock, journal/checkpoint/marker/fault latch/recovery Phase 1.

**Hoàn tất triển khai và kiểm chứng local; đủ điều kiện nghiệm thu phạm vi local.**
Package/lifecycle chính xác firmware 3.32.37 và hardware SAT **BLOCKED/NOT RUN** theo
[hardware-blockers.md](hardware-blockers.md). Chưa đạt Plug & Play production.

## Thay đổi bàn giao

- `src/main/java/org/fus/Main.java`, `config/StationConfig.java`, mới
  `runtime/{RuntimeState,ProcessSupervisor,ProgressWatchdog}.java`: singleton,
  bounded restart/backoff/budget bền vững, stop-control, progress deadline,
  preflight/config/evidence binding và session fence. Config Phase 1 timeout lớn
  vẫn dùng được khi chưa có thuộc tính watchdog mới.
- `deploy/run.sh`, mới `fx9600-writing-box`, `start_fx9600-writing-box.sh`,
  `stop_fx9600-writing-box.sh`, `verify-release.sh`, `.gitattributes`:
  launcher/stop LF, checksum guard khi start, state/config ngoài release.
- `scripts/{stage-release,verify}.ps1`, mới `check-release.ps1`, `check-archive.ps1`,
  `test-release.ps1`, `test-launchers.ps1`: class-path JAR Java8, tar có modes,
  archive/payload checker và fault injection. `station.properties.example` thêm
  các thuộc tính Phase 2; không phải config đã commissioning.
- Tests mới `ProcessSupervisorCheck`, `StartupCheck`, `StopControlCheck` dưới
  `src/test/java/org/fus/`; không thay đổi các suite Phase 1.
- `document/phase2/`: implementation-plan, architecture, test-results,
  hardware-blockers, independent-review, commissioning-and-rollback, completion-report.

Source domain/journal/identity/RFID gateway/operation guard và toàn bộ tài liệu
Phase 1/audit không đổi. Main giữ journal/owner lock đến process exit. Không auto
ACK/replay/write, không reset journal/checkpoint/marker/fault latch/recovery gate.
Khóa singleton áp dụng cho cooperative instances cùng commissioned state root;
commissioning phải loại trừ client khác hoặc binary cũ.

## Kiểm chứng và review

**10/10 Java suites PASS**, gồm toàn bộ 7 suite Phase 1; production/test compile
`--release 8` PASS; packaged class major52 và startup checks PASS. **8/8 lỗi package
được phát hiện**, shell launcher integration với fake Java PASS; checksum/LF/syntax/
archive allowlist/permissions PASS; `git diff --check` PASS. Không chạy Maven hoặc
Java8 runtime/firmware thật. Chi tiết: [test-results.md](test-results.md).

Independent reviewer đầu tiên yêu cầu sửa collision evidence/config và các lỗi
package/rollback/stop/test. Đã test-first RED→GREEN, sửa và re-review; verdict cuối
**APPROVE phạm vi local**, không còn finding chưa xử lý:
[independent-review.md](independent-review.md).

## Release và phần còn lại

Artifact local: `target/phase2-release-handoff.tar` + `.tar.sha256`; payload ở
`target/phase2-release-handoff/payload/`. Đây là **tar để kiểm chứng local, không
phải Zebra installer .deb**. Không có maintainer script/service tự cài vào reader.
Raw test evidence ở `target/phase2-evidence/` (ignored).

Archive 675840 bytes; SHA-256:
`dd75ee863a4351ab448945c9c5f8d1658d4eee732da65c5a36f8d088d9cd84a7`.
HEAD vẫn `71b761a`, main vẫn `ba92fb5`; Phase 2 là thay đổi chưa commit/stage.

SAT/vendor còn thiếu: đúng builder/layout/start-stop contract/AutoStart trên
3.32.37, user/env/mount/quyền/utilities/RAM hai JVM, Java8+SDK đúng release, flash
brownout/FileLock/fsync/atomic rename, RF/session cleanup sau crash/hang/stop,
boot thiếu scanner/hotplug, upgrade/rollback và cold power cycles. Mọi mục vẫn
BLOCKED; local checks không mở hardware gate. Xem runbook
[commissioning-and-rollback.md](commissioning-and-rollback.md).

Crash sau khi có thể mở SDK session sẽ latch và không tự restart. Đây là hành vi
có chủ đích khi chưa chứng minh session cleanup; không đổi safety lấy khả dụng.
Rollback yêu cầu hiểu **cả FXJNL01 và FXRUNTIME02**; không tự rollback vào Phase 1
checkpoint hoặc binary cũ. Không có automated reset latch/budget/repair journal.
