# Phase 2 — kế hoạch triển khai local

Ngày 2026-10-31. Baseline được phê duyệt: `71b761a`; branch
`feature/fx9600-phase2-autostart`. Không worktree, commit, push, deploy hoặc RF write.

1. Viết checks trước code: child JVM crash/exit/config/stop/hang, duplicate owner,
   session fence, journal lỗi và scanner thiếu. Ghi RED rồi triển khai.
2. Thêm supervisor Java 8 với restart backoff có giới hạn bền vững, stop request,
   khóa supervisor + khóa station; không dùng PID reuse hoặc killall.
3. Main load config/journal trước SDK; fence bền vững trước tạo session, chỉ clear
   sau disconnect/Dispose thành công. Watchdog tiến triển độc lập log/status;
   hung/uncertain session phải dừng restart, không tự ACK/replay.
4. Release tar có JAR/SDK/scripts LF/mode executable/checksums và manifest schema.
   Kiểm tra package offline; không phát hành .deb giả định layout chưa xác minh.
5. Đối chiếu Zebra chính thức; commissioning/rollback phải giữ state/config ngoài
   release và không chạy binary trước Phase 1. Firmware-specific lifecycle và
   package builder đích chưa có evidence phải BLOCKED.
6. Full regression + `--release 8`, review độc lập chỉ đọc, sửa finding, retest,
   bàn giao evidence và hardware blockers. Local PASS không phải production SAT.
