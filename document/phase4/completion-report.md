# Phase 4 — bàn giao để nghiệm thu local

2026-10-31. **LOCAL-COMPLETE — đủ điều kiện nghiệm thu phạm vi cục bộ. Reviewer
độc lập APPROVE sau fix/retest. HARDWARE-READY toàn hệ thống vẫn BLOCKED**
do thiếu exact vendor/target evidence. Không có hardware SAT PASS.

Checkpoint Phase 3 đã tồn tại `3b6f9ff` (parent e7714ea), đúng message/30 files; đã
đối chiếu completion report và regression trước branch. Không commit trùng.
Branch `feature/fx9600-phase4-hardware-readiness`, HEAD giữ nguyên 3b6f9ff, không
stage/commit Phase 4, không main/worktree/merge/push. Hai audit untracked giữ nguyên
SHA256 so với đầu nhiệm vụ; main vẫn ba92fb5.

Thay đổi source/build/test:

- `screen_writing_box/{platformio.ini,src/main.cpp,README.md}`: include đúng project
  config, thêm env compile-only placeholder kế thừa pinned libs/board/GPIO; không
  sửa config thật hoặc wiring. Firmware thật đã compile/link/bin thành công.
- `scripts/{stage-release,check-release,test-release}.ps1`: reproducible Java ZIP/tar,
  source input manifest/hash/compiler/epoch, provenance/candidate type validation,
  exact metadata keys và duplicate rejection, 12 artifact faults.
- Mới `scripts/build-screen.ps1`, `test-reproducibility.ps1`, `sat_latency.py`,
  `test_sat_latency.py`; mới `src/test/java/org/fus/ReleaseRollbackCheck.java`.
  Latency tool chỉ offline; rollback chỉ preflight/temp state hai packaged JAR.
- `document/phase4/`: đủ 11 báo cáo yêu cầu, pinned Python requirements và 4 templates
  inventory/run/latency/evidence. Template trống không chứa fake SAT results.

Tổng 27 file Phase 4: 3 file ESP32, 7 scripts, 1 Java test và 16 file tài liệu/
template/requirements. Danh sách source cụ thể ở trên; hai audit không tính vào diff.

Production Java, journal/identity/EPC/PC/TID, ACK/OperationGuard/health semantics,
runtime/supervisor/fence/singleton và deploy launchers không đổi. Production RF
write vẫn unsupported, scanner CDC vẫn CONNECTED/UNKNOWN; không bypass safety.

**VERIFIED LOCAL:** Java 13/13 suites, --release8/major52, host C++ 2/2, firmware
BUILD PASS, 12/12 artifact faults, launcher/supervisor và packaged rollback PASS,
latency tool 13 tests PASS, reproducibility hai fresh builds cùng bytes/hash,
git diff --check PASS. Chi tiết/lệnh/raw evidence: [test-results](test-results.md).
Reviewer tự chạy latency/host tests, đối chiếu manifest/artifact hashes và đọc
build/fault/rollback evidence; [independent review](independent-review.md).

Firmware RAM 47.616/327.680, flash 1.177.793/1.310.720 bytes; placeholder binary SHA
`c565cd15f821d96035449991a73e77e45053da134c4dd04c343f729350ee13d7`.
Flash headroom chỉ 132.927 bytes trong app slot; runtime heap/stack/TFT chưa đo.
Java candidate tar `target/phase4-final-a.tar`, 696320 bytes, SHA
`0e946f95881d6f9246776daee5de0617132904c515203c3d662f6ff8262c8ad0`;
twin `phase4-final-b.tar` cùng hash. Không Zebra-compatible installer; không upload.

**VERIFIED FROM VENDOR DOCUMENTATION:** Embedded guide search excerpt mô tả builder/
start-stop; UserApp API mô tả .deb/AutoStart, Impinj công bố serialized TID và RTA có
model/FW prerequisites. **INFERENCE:** các API khác version chỉ giúp định hướng;
không xác nhận firmware3.32.37/SDK bundled/DS5502 mode. Exact contracts vẫn thiếu.

**HARDWARE VERIFICATION REQUIRED / NOT RUN / BLOCKED:** package 3.32.37 (mẫu start
nền khác foreground supervisor), target Java8/native/license/RAM 2 JVM, full serialized
TID/duplicate EPC/same-target/EPC80-PC, RF isolation/session cleanup, scanner Linux
identity/exclusive/raw/read-close/liveness, flash durability/brownout và TFT latency.
Xem [hardware blockers](hardware-blockers.md), [preflight](hardware-preflight.md),
[SAT plan](sat-test-plan.md). Chưa đủ điều kiện deploy hoặc mở write.

SAT chuẩn bị ≥20 cold boots, ≥30 disconnect/reconnect mỗi loại phù hợp, ≥300 latency
samples; p95 event→redraw ≤500ms, physical→display ≤1s khi có detector, network loss→
UNKNOWN ≤3s. 300 RFID write cycles, power-cycle/reset/firmware upload/config changes
chỉ trong nhiệm vụ được phê duyệt riêng; không thực hiện ở Phase 4.
