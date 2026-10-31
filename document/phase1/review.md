# Review độc lập Pha 1 — 2026-10-31

Reviewer: subagent `/root/phase1_review`, lượt chỉ đọc tách khỏi agent triển khai,
theo [independent-code-review SKILL.md](C:/Users/zleon/.agents/skills/independent-code-review/SKILL.md).
Không sửa file, không chạy test sinh output, không commit hoặc truy cập phần cứng.

Đã inspect `git status`, diff/staged diff, **toàn bộ source/test mới untracked**,
Main/workflow/status/config/guard/gateway/verify, `.gitignore`, architecture và
hardware-blockers. Ba tài liệu audit là đầu vào giữ nguyên, không phải code mới.
Test PASS là evidence agent triển khai thực thi; reviewer đọc tests, không tự chạy.

| Phát hiện | Mức | Xử lý và bằng chứng |
|---|---|---|
| Prefix journal đầy đủ có thể mất unresolved khi bị truncate | Major | Test RED; thêm `.head` seq/hash/checksum, atomic pending/rename và startup cross-check. Truncate header/record boundary cùng missing/corrupt/crash-gap đều gate. |
| HTTP mở trước safety snapshot | Minor | Start sau constructor workflow; không có cửa sổ startup recovery=false trước khi load safety. |
| Safety boolean có thể stale | Minor | Lease ≤250 ms và ≤barcode validity; test expiry khi không tick. |
| Lease không renew khi tick bình thường | Minor, lượt review lại | Test integration RED rồi fix cuối tick; test renew/expire PASS. |
| TransactionId WRITING có thể trỏ giao dịch cũ | Minor | Clear khi reserve, publish ngay sau durable PREPARED. |
| Tài liệu recovery cũ cho scan xóa warning | Minor | Banner Pha 1 trong deployment/operations, dẫn quy trình mới. |
| Coverage rename checkpoint cần evidence cụ thể | Ghi chú test | Thêm failure tại VERIFIED sau fake read-back bằng destination head là directory; không PASS, restart gate. |

Verdict lượt đầu: **CHANGES_REQUESTED**. Sau fix và review lại source/test:
**APPROVE — phạm vi triển khai cục bộ, không phải chấp thuận deploy.**
Không còn finding Critical/Major/Minor chưa xử lý trong source/test đã review.

Delta cuối được reviewer xem riêng: production `OperationGuard.startFatal` dùng cùng
callback với child giữ status/stdout/stderr locks; Main ACK integration kiểm tra
online lock rejection, ACK offline durable và không khởi tạo scanner/HTTP/reader.
Verdict delta: **APPROVE**. Full retest cuối do agent triển khai chạy, xem
[test-results.md](test-results.md).

Giới hạn giữ nguyên: chưa test native SDK/firmware/flash/RF enclosure hoặc manual
reconciliation vật lý. Checksum không chống privileged rollback/xóa đồng bộ state.
ACL, backup toàn bộ sidecar, không binary cũ song song/rollback, RF dừng sau JVM chết
và mọi bước deploy vẫn phải được nghiệm thu riêng. Reviewer không cho phép mở RF.
