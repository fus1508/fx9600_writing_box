# Phase 3 — bàn giao local

Baseline `e7714ea`, checkpoint `Phase 2 — Plug & Play` đã tồn tại trước nhiệm vụ.
Đúng branch Phase 2, tracked/staged diff rỗng, không tạo commit trùng. Branch triển
khai `feature/fx9600-phase3-health-ui`. Không worktree/merge/push/main/deploy/upload,
không commit Phase 3. Hai file audit đầu vào giữ nguyên untracked.

**LOCAL-COMPLETE — đủ điều kiện nghiệm thu phạm vi cục bộ.** Reviewer độc lập
APPROVE source/tests sau ba finding Major đã sửa, toàn bộ final regression PASS.
Không đạt production/hardware acceptance; mọi blocker Phase 1/2 tiếp tục có hiệu lực.

Source thay đổi:

- Java: `Main.java`, `api/StatusServer.java`, `device/rfid/RfidGateway.java`,
  `device/scanner/ScannerSource.java`; mới `health/DeviceHealth.java`.
- Java checks: cập nhật `StatusServerCheck`; mới `DeviceHealthCheck`, `HealthApiCheck`,
  `ScannerHealthCheck`, `HealthLatencyCheck` dưới `src/test/java/org/fus/`.
- ESP32: `src/main.cpp`, `include/config.example.h`; mới `include/health_ui.h`,
  `include/status_parser.h`, `test/{health_ui_check,status_parser_check}.cpp`.
- Scripts: verify, stage-release (metadata phase3/schema2), mới verify-screen.
- Docs: README màn hình, banner station-status-and-tags, 9 tài liệu `document/phase3/`.

Contract trước/sau: giữ các field workflow/safety cũ; thêm schemaVersion2, boot ID,
snapshot sequence/uptime, health 3 đối tượng với connection/readiness/evidence/age/
reason/safe flag. Top scanner/reader có đủ 5 state; plain CDC không READY. Lease
health 1000ms, heartbeat HTTP 400ms, UI network deadline 2500ms độc lập HTTP task.
Chi tiết [health-contract](health-contract.md), [architecture](architecture.md),
[ui-state-design](ui-state-design.md).

13/13 Java suite PASS trong lượt build bàn giao cuối; 2/2 host C++ suite PASS. Phase 1/2
regression, compile --release8, package check +8 faults và launcher fake Java PASS.
Benchmark cuối 300 mẫu event→HTTP localhost p50=728us, p95=1019us, max=8675us,
missed=0, timeouts=0; không phải hardware/end-to-end
redraw. Xem [test-results](test-results.md), [latency plan](latency-test-plan.md).

Independent review: ba Major cleanup CDC, correlation transaction ID rỗng và race
activation được tái hiện RED trước fix, GREEN sau fix và re-review APPROVE. Không
còn finding mở. Chi tiết [independent-review](independent-review.md); reviewer chạy
trực tiếp C++ binaries/read-only diff, còn Java/package là evidence developer.

Sau gián đoạn đã đối chiếu lại branch/HEAD, tất cả diff tracked/untracked, chín
tài liệu và logs. Không có source bỏ dở; phần pending review/final regression/report
được hoàn tất. Tổng 30 file Phase 3: 11 tracked sửa, 19 mới; hai file audit không stage.

Không sửa domain/journal/identity/EPC/PC/TID, recovery ACK, OperationGuard,
ProcessSupervisor/ProgressWatchdog/RuntimeState/singleton/session fence. Production
write vẫn unsupported, safeToTrigger=false. Scanner plain CDC thiếu liveness query
đã xác minh nên CONNECTED/readiness UNKNOWN; không suy lỗi từ operator idle.

Còn BLOCKED: PlatformIO build/toolchain, target Java8/native SDK, driver CDC identity/
raw/read-close behavior, DS5502 management contract, TFT scheduling/RAM/render và
các KPI latency phần cứng. Hardware NOT RUN; không unlock write/auto ACK.
[Danh sách blockers](hardware-blockers.md). Tar local trong target chỉ là artifact
kiểm chứng, không Zebra installer và chưa được triển khai.

Artifact bàn giao cuối: `target/phase3-release-handoff-final.tar` và `.tar.sha256`,
payload ở `target/phase3-release-handoff-final/payload/`. Version `3.0.0-local`,
status schema2, journal FXJNL01/runtime FXRUNTIME02 giữ nguyên. Tar 696320 bytes,
SHA256 `a94bc9c9744169d63dcbc1335a8a0f862e4cd2b5d2c845c169ed69fe0af25936`.
Các artifact `*-review` và `phase3-release-handoff` trước fix activation được giữ
để điều tra, **đã bị thay thế**, không phải bản bàn giao cuối. Không deploy chúng.

HEAD vẫn `e7714ea04c52437ad2d354c0666dad72b639c27d`, main vẫn `ba92fb5`, staged
rỗng. Không commit/push Phase 3. Không hardware latency PASS, không auto ACK hay
mở production write; SAT tiếp theo theo blockers/latency plan cần nhiệm vụ riêng.
