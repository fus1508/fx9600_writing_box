# Bàn giao Pha 1 — Safety & Data Integrity

Ngày 2026-10-31. **Hoàn tất phần triển khai/kiểm chứng cục bộ. Pha 1 chưa hoàn thành
nghiệm thu toàn bộ: hardware-dependent safety vẫn BLOCKED; chưa được deploy.**

Branch `feature/fx9600-phase1-safety` tạo từ HEAD `ba92fb5` của
`audit/fx9600-writing-box`. Không worktree/commit/push/merge/rebase; không sửa main.
Giữ nguyên ba tài liệu audit chưa commit. Không thực hiện RFID write/erase thật,
không kết nối reader/ESP32 hay đổi cấu hình thiết bị.

## P0 đã xử lý trong phạm vi local

| Vấn đề | Kết quả / evidence |
|---|---|
| EPC duy nhất bị coi là một tag vật lý | Production RF access bị khóa vô điều kiện. Policy giả lập dùng full serialized TID attestation, duplicate EPC/TID khác bị từ chối; IdentityCheck/Phase1SafetyCheck PASS. Không có config bypass. |
| FAIL sau partial write bị coi như chưa thay đổi | Durable WRITE_INTENT trước write, changed sticky sau intent; fake partial write tự khai false vẫn RECOVERY_REQUIRED. Không false PASS khi write/readback/journal lỗi. |
| Marker/recovery bị scan hoặc UI/reboot xóa | Journal độc lập UI, giữ unresolved; marker cũ được nhập và giữ nguyên. Scan/tick/disconnect/restart không clear. Regression RED→GREEN. |
| Mất evidence giao dịch | Append journal đủ metadata/outcomes, checksum/hash chain và durable checkpoint. Corrupt/partial/complete-prefix truncation, missing head hoặc disk error đều gate trong local tests. |
| False PASS trước durable success | Read-back exact EPC + decode, VERIFIED append + checkpoint xong mới PASS; lỗi sau fake RF/ở VERIFIED không PASS. |
| Replay/concurrent operation | Reservation một lần, single worker, generation/disconnect/shutdown, không GPI polling song song worker; không tự replay sau restart. |
| Deadline bị status/log lock chặn | Fatal callback halt trực tiếp; child test giữ cả ba locks vẫn exit 2 và giữ evidence. |

## File source đã thay đổi/thêm

- Production: `src/main/java/org/fus/Main.java`,
  `api/StatusServer.java`, `config/StationConfig.java`,
  `device/rfid/OperationGuard.java`, `device/rfid/RfidGateway.java`,
  `domain/StationWorkflow.java`, `domain/TransactionJournal.java` (mới),
  `domain/PhysicalTagIdentity.java` (mới); các path rút gọn cùng dưới `src/main/java/org/fus/`.
- Tests dưới `src/test/java/org/fus/`: `WorkflowCheck.java`, `StatusServerCheck.java`,
  `OperationGuardCheck.java`; thêm `SafetyFixture.java`, `Phase1SafetyCheck.java`,
  `TransactionJournalCheck.java`, `IdentityCheck.java`.
- Build/config: `scripts/verify.ps1`, `station.properties.example`, `.gitignore`.
- Tài liệu thường: `document/operations.md`, `document/deployment-and-recovery.md`
  thêm banner thay thế quy trình cũ. Không thay nội dung technical audit gốc.
- Deliverables: `document/phase1/{implementation-plan,architecture,test-results,hardware-blockers,review,completion-report}.md`.

## Kiểm chứng và review

`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1`: **exit 0,
7/7 suite PASS**, gồm crash/disk/partial write/duplicate EPC/legacy marker/ACK và
concurrency. Compile production/test riêng `--release 8`: **PASS**, class major 52;
`git diff --check`: **PASS**. Java thực thi tests là JBR21, chưa runtime Java8 reader.
Không có Maven trong PATH; không chạy `mvn package`.

Independent subagent review: lượt đầu CHANGES_REQUESTED, đã sửa/retest; verdict cuối
**APPROVE cho phạm vi local**, không chấp thuận deploy. Chi tiết/tái hiện:
[test-results.md](test-results.md), [review.md](review.md).

Các success RF/TID đều là **mô phỏng**; disk-full/fsync là fault injection, crash là
halt child JVM trên Windows. Không có đo brownout/native SDK/real-tag success nào.

## Rủi ro regression và điều kiện còn thiếu

- Thay đổi chức năng có chủ đích: bản này **không ghi tag thật**; cần adapter được
  chứng minh discovery/access cùng TID và review riêng trước khi mở ghi. EPC vẫn 80 bit.
- Journal/schema/sidecar mới cần stable private persistent directory, ACL, force/atomic
  rename/lock trên flash đích; không tự rotation/repair. Nhiều lần fsync và startup scan
  có chi phí I/O/flash chưa đo. Storage lỗi sẽ làm trạm dừng ghi thay vì tiếp tục.
- ACK chỉ là operator attestation quarantine/box trống với evidence reference, không
  chứng minh RF/session cũ đã dừng hoặc tag đã sửa. Corrupt journal/fault/pending head
  cần offline investigation; chưa có tool repair được nghiệm thu.
- Không chạy binary cũ song song/rollback trước Pha 1 hoặc đổi journal path để bỏ gate.
  Checkpoint không chống xóa/rollback đồng bộ toàn bộ evidence bằng quyền quản trị.
- API additive safety fields chưa có UI ESP32 mới; LAN auth/health/PnP/supervisor,
  native call ngoài transaction và CDC liveness vẫn là backlog ngoài phạm vi.

Hardware SAT tiếp: xác minh đúng SDK/MR6P/full TID và duplicate discovery; RF enclosure;
same-TID read/write xuyên EPC change; crash/power-cut/disk-full trên flash thật;
session cleanup/single-owner; quarantine/ACK; ≥300 end-to-end cycles và ≥20 power cycles
sau khi được phê duyệt. Tất cả hiện **BLOCKED/NOT RUN**, xem
[hardware-blockers.md](hardware-blockers.md). Không đổi các mục đó thành PASS từ tests local.
