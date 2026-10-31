# Phase 3 — kế hoạch triển khai local

2026-10-31. Baseline `e7714ea` đã có commit `Phase 2 — Plug & Play` trên
`feature/fx9600-phase2-autostart`; diff tracked/staged rỗng. Dùng checkpoint hiện có,
không tạo commit trùng. Regression baseline: 10/10 suite PASS, JBR21.
Branch triển khai: `feature/fx9600-phase3-health-ui`, không worktree/push/merge/deploy.
Giữ nguyên hai file audit untracked.

1. Test-first health với fake monotonic clock, scanner transport giả và API localhost.
2. Evidence model độc lập SDK; tiến triển chỉ đến từ owner sau lời gọi SDK hoàn tất.
   Callback disconnect chỉ ghi bằng chứng; không thêm SDK concurrency hoặc I/O watchdog.
3. CDC tách node presence/port-open/decode/liveness; bỏ partial frame sau mất identity.
   Plain CDC không báo READY. Không tự đổi protocol hay cấu hình scanner thật.
4. API v2 additive, freshness tự hết hạn, long poll hữu hạn và pool riêng.
5. ESP32 network task + bounded mailbox, TFT một owner; reducer có stale deadline,
   recovery/unknown write outcome ưu tiên; kiểm thử host và build nếu có toolchain.
6. Full regression, Java --release 8, package checks; benchmark local và SAT plan.
7. Reviewer độc lập chỉ đọc, sửa và retest, bàn giao báo cáo; không commit Phase 3.

Không sửa EPC/PC/TID, journal/recovery ACK, supervisor/singleton/session fence.
Mọi latency phần cứng và scanner management chưa có evidence: BLOCKED/NOT RUN.

Tiếp tục sau gián đoạn: đã xác minh lại đúng branch/HEAD `e7714ea`, staged diff rỗng,
bảo toàn toàn bộ phần triển khai và hai file audit. Phần dang dở là ghi nhận review,
re-review bản sửa và final regression/artifact/report; không triển khai lại từ đầu.
Review yêu cầu sửa cleanup gate, correlation tx rỗng và race activation CDC. Mỗi
finding có test RED thật trước fix, rồi regression GREEN; chi tiết ở review/results.
