# Phase 4 — independent review

Reviewer subagent `/root/phase4_review`, context riêng, chỉ đọc theo
[independent-code-review SKILL.md](C:/Users/zleon/.agents/skills/independent-code-review/SKILL.md).
User đã explicit yêu cầu subagent review; không yêu cầu worktree/commit/deploy.
Không chỉnh source/cài/compile/tạo artifacts/network/hardware hoặc escalation.

Lượt sơ bộ **CHANGES_REQUESTED**, không Critical/Major được phát hiện. Một Minor:

| Finding | Fix và test-first evidence |
|---|---|
| check-release metadata regex dùng dấu chấm wildcard, near-match key được nhận; duplicate package.type mâu thuẫn vẫn nhận | Test RED thật `review-metadata-red.txt`; thay bằng parser exact Ordinal, key/value bắt buộc, reject duplicate; GREEN12faults rồi final12faults PASS |

Reviewer đã trực tiếp chạy 13 latency unit tests và 2 host C++ binaries: PASS;
git diff --check PASS; hash hai tar green khớp và source manifest digest đúng.
Một lần parser invocation của reviewer dùng sai tên fixture gây assertion input.good;
chạy lại fixture đúng java-status-fixture.json PASS, không phải source regression.
Java regression/build/rollback/fault injection/firmware là evidence developer,
reviewer không tự nhận đã compile hoặc chạy chúng.

**VERDICT cuối: APPROVE — bàn giao Phase 4 cục bộ và SAT preparation.** Không còn
finding Critical/Major/Minor cần sửa trong phạm vi review. Đây không là phê duyệt
hardware readiness/deploy/AutoStart/RFID write hoặc SAT thật.

Final re-review đã đọc toàn bộ diff và source/script/test untracked, 7 scripts,
rollback harness, 3 file ESP32, đủ 11 reports/4 templates/requirements và hai audit
input. Reviewer xác nhận production Java/domain/runtime/deploy/gates không đổi;
sample config, concurrency/RAM/vendor/recovery limitations được ghi rõ.

Reviewer tự xác minh 51 input hashes khớp checkout, manifest digest khớp metadata,
tar có 10 regular files/epoch/owner đúng, 696320 bytes; hai tar final cùng SHA256
`0e946f95881d6f9246776daee5de0617132904c515203c3d662f6ff8262c8ad0`.
Firmware/ELF/bootloader/partition hashes khớp report. Tự chạy lại 13 latency tests,
2 host binaries và git diff --check PASS. Hai lượt Java regression 13/13, firmware
build, 12 faults, launcher và packaged rollback chỉ là evidence developer reviewer
đã đọc; không tự compile/tạo artifact/network/hardware hoặc kiểm lại nguồn vendor
trực tuyến. Các nguồn vendor do developer nghiên cứu, luôn ghi giới hạn truy cập.

Sau verdict chỉ cập nhật hai báo cáo review/completion; không thay source/tests.
Hardware acceptance vẫn NOT RUN/BLOCKED theo [hardware-blockers](hardware-blockers.md).
