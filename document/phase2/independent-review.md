# Phase 2 — independent review

Ngày 2026-10-31. Reviewer: subagent độc lập `/root/phase2_review`, theo quy trình
[independent-code-review SKILL.md](C:/Users/zleon/.agents/skills/independent-code-review/SKILL.md),
context riêng và chỉ dẫn chỉ đọc. Không sửa file, chạy suite sinh artifact, commit,
deploy hoặc kết nối reader. Reviewer trực tiếp chạy `sh -n` và `git diff --check`:
PASS; kết quả suite/build là evidence developer chạy, reviewer đã đọc logs/tests.

Đã inspect git status, diff, staged diff, toàn bộ source/test/script mới untracked
và tài liệu Phase 2. Hai file audit đầu vào được xác định ngoài thay đổi Phase 2.

| Finding | Mức | Fix và retest |
|---|---|---|
| Supervisor mutation trước preflight có thể xóa marker/ghi đè journal khi trùng tên runtime | Major | Validate journal/marker và mọi sidecar vs runtime names trước mutation; pin binding dưới station lock. RED→GREEN bytes-preservation + exit20 |
| Config có thể bị supervisor.status ghi đè | Major, cùng họ collision | validateConfig canonical/disjoint trước mutation, cả station và supervise/check paths; regression safe corrupt-journal RED→GREEN |
| Allowlist case-insensitive trên Windows chấp nhận RUN.SH | Minor | Ordinal/case-sensitive checksum/actual/archive names; case fault RED→GREEN |
| Rollback metadata cho phép checkpoint thiếu Phase 2 fence | Minor | rollback.requires=FXJNL01+FXRUNTIME02, verifier và runbook đồng bộ |
| Stop phụ thuộc operational config/unrelated checksum | Minor | Đọc root riêng, --stop-state, shell stop bỏ unrelated integrity gate; StopControlCheck RED→GREEN và fake Java shell tests |
| Relative-config test có thể PASS do fake Java cùng exit20 | Minor, test | Capture sentinel riêng phải không tồn tại; chứng minh Java chưa chạy |
| Default watchdog làm config Phase 1 timeout lớn không còn hợp lệ | Developer bổ sung sau review | RED→GREEN, default động giữ deadline minimum; reviewer re-review delta APPROVE |

Verdict đầu: **CHANGES_REQUESTED**. Sau sửa và independent re-review:
**APPROVE — phạm vi triển khai/kiểm chứng local.** Không còn finding
Critical/Major/Minor chưa xử lý trong source/tests đã review.

Evidence handoff: 10/10 Java suites (đủ Phase 1 regression), compile `--release 8`,
JAR major52, tar checksum/permissions/LF/syntax và packaged startup; 8/8 package
fault injections, launcher integration với fake Java đều PASS. Xem
[test-results.md](test-results.md) để tái hiện và đọc giới hạn.

Verdict không phải chấp thuận deploy. Chính xác package/lifecycle trên 3.32.37,
Java8 runtime thật, native SDK/session cleanup, flash durability, USB và hardware
SAT vẫn BLOCKED/NOT RUN. Production RFID write vẫn khóa; không claim production PnP.

Reviewer đã đọc `build-handoff.txt` sau delta timeout cuối và giữ verdict APPROVE.
Artifact cuối `target/phase2-release-handoff.tar`, SHA-256
`dd75ee863a4351ab448945c9c5f8d1658d4eee732da65c5a36f8d088d9cd84a7`.
