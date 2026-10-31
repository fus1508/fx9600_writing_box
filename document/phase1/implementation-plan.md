# Kế hoạch Pha 1 — 2026-10-31

Baseline: HEAD `ba92fb5`, branch nguồn `audit/fx9600-writing-box`. Làm trên
`feature/fx9600-phase1-safety`, không worktree/commit/push/deploy. Giữ nguyên ba
tài liệu audit chưa tracked. Chỉ kiểm tra cục bộ, không kết nối thiết bị.

1. Test-first: tái hiện warning bị scan xóa, marker bị ghi đè, lỗi write/readback,
   duplicate EPC, crash trước/sau các ranh giới journal; ghi kết quả RED.
2. Identity: kiểm tra public API của JAR hiện có. Thêm mô hình quan sát EPC/full
   serialized TID với provenance rõ ràng cho mô phỏng. Chặn production selection,
   write/read bằng lỗi HARDWARE_VERIFICATION_REQUIRED; không có config bypass.
   Không suy ra uniqueness từ Set EPC hay một lần đọc TID theo EPC.
3. Journal: append-only, version, sequence, checksum, fsync, khóa file độc quyền.
   Lưu txId, bootId, barcode, old/expected EPC, TID, stage/outcome/error/time/ack.
   Durable intent trước write; durable verified result trước PASS. Disk failure,
   tail hỏng hoặc record thiếu giữ recovery gate, không tự truncate/replay.
4. Recovery: gate sống qua scan/tick/disconnect/reboot. Legacy marker được nhập
   vào journal và giữ nguyên bằng chứng. Acknowledgement offline sau cách ly tag,
   kiểm tra txId và tham chiếu đối soát, journal khóa độc quyền; không HTTP control.
5. Concurrency: một reservation chạy tối đa một lần, một RFID worker, không GPI
   polling song song operation; disconnect/shutdown vô hiệu hóa generation. Timeout
   halt không chờ status/log. Journal và marker không bị dọn khi outcome bất định.
6. Chạy `scripts/verify.ps1`, compile production/test `--release 8`, fault injection
   với file tạm và child JVM. Review độc lập bằng subagent chỉ đọc, sửa lỗi và retest.
7. Bàn giao architecture, test-results, hardware-blockers, review, completion-report.
   TID access, brownout/fsync filesystem FX9600 và SAT vẫn BLOCKED khi chưa đo thật.

GRN chưa có mapping nghiệp vụ riêng: lưu nguyên barcode được yêu cầu, không tuyên
bố đối soát chứng từ bên ngoài. Không thay EPC layout/PC/giới hạn 80 bit.

Điều chỉnh sau review: bổ sung checkpoint `.head` độc lập để phát hiện truncation
đúng ranh giới record, `.head.pending` cho atomic replace và `.fault` giữ lỗi I/O;
test-first RED→GREEN cho truncation và lease renewal. Kết quả/hạn chế trong
[completion-report.md](completion-report.md), không thay các blocker hardware thành PASS.
