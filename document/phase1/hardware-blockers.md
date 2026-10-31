# BLOCKED — Hardware verification required

Ngày 2026-10-31. **Không được deploy bản này hoặc bật ghi TID. Không SAT phần cứng
nào đã chạy.** FX9600 FW 3.32.37, Java 1.8.0_102, MR6P/DS5502 là baseline tài liệu,
chưa truy cập để xác minh trong nhiệm vụ.

| Blocker | Bằng chứng còn thiếu / bước SAT tiếp theo | Trạng thái |
|---|---|---|
| Full serialized TID discovery | JavaDoc đúng JAR; xác nhận độ dài/layout serial đúng lô MR6P; inventory/access reporting phân biệt hai tag trùng EPC, không aggregation mất tag; thiếu/khóa TID phải fail closed. | BLOCKED |
| Same-target access xuyên EPC change | Thử riêng prefilter+synchronous vs AccessFilter+asynchronous; mask/bank/offset đủ toàn TID, antenna/session rõ; callback correlation/completion/status/cancel/cleanup; không dùng EPC mới để nhận lại tag. | BLOCKED |
| RF enclosure | Ground truth 0/1/2 tag, duplicate EPC, tag ngoài box, hoán đổi, RF yếu; chứng minh shielding/interlock/antenna chứ không chỉ power. TID không chống clone. | BLOCKED |
| EPC80/PC | Đơn vị byte/word, PC bits và read-back trên SDK bundled/MR6P. Giữ chặn EPC96/EPC128; resize không thuộc Pha 1. | BLOCKED |
| Journal trên flash thật | `force(true)`, atomic replace, directory fsync, advisory lock, quyền rfidadm, filesystem/mount, dung lượng/flash wear. Đo brownout ở từng write/head.pending/rename/fsync và sau durable result. Windows process-halt test không thay thế. | BLOCKED |
| RF còn chạy sau JVM chết | Kiểm tra session/access server-side đã dừng trước reopen/ACK; không hai instance, không parallel SDK access; intentional stop và worker native treo. | BLOCKED |
| Manual reconciliation | Operator quarantine tất cả suspect tags, box rỗng, đối soát/evidence reference, quyền ACK và stop supervisor được SAT. CLI chỉ kiểm tra cú pháp/state/lock và lưu attestation. | BLOCKED |
| Migration/rollback/repair | Preserve journal + head + pending + fault + legacy marker cùng cấu hình/working dir. Không binary cũ song song/rollback. Tool repair corrupt evidence chưa có; không xóa file để mở gate. | BLOCKED |
| Deployment nền tảng | User Application/AutoStart/supervisor, native SDK/license, staging/stop/permissions và rollback an toàn chưa nghiệm thu; không triển khai Pha 2/3/4 trong nhiệm vụ. | BLOCKED |

Trình tự SAT chỉ thực hiện trong nhiệm vụ riêng có operator/tag disposable và phê
duyệt ghi thật:

1. Chốt SDK/firmware, JavaDoc, quyền phân phối native, schema TID và phương án filter.
2. Kiểm tra RF read-only/discovery với ground truth duplicate EPC, full serial và
   enclosure; thiếu bằng chứng uniqueness thì không chuyển sang bước ghi.
3. Triển khai adapter trong thay đổi riêng có review; giữ EPC80, same-TID read-back
   và durable gate. Không có công tắc cấu hình bật adapter trong bản hiện tại.
4. Trên tag disposable: write lỗi/partial, tag swap, disconnect, process crash và
   power-cut từng boundary, disk-full/permission/atomic-move failure; không false PASS,
   không wrong-tag write, không replay, unresolved vẫn khóa sau reboot.
5. Nghiệm thu quarantine/ACK, retention/capacity/flash và restore/rollback giữ evidence.
6. Theo backlog audit: ≥300 chu kỳ end-to-end có fixture barcode/GRN độc lập,
   ≥20 power cycles; USB/ESP32 latency/PnP thuộc các pha sau nếu được phê duyệt.

Rủi ro còn lại: checksum/checkpoint không chống privileged rollback/xóa toàn bộ bộ
state, storage nói dối về fsync hoặc flash mất cả evidence. Một journal path không
khóa được binary cũ hoặc instance cấu hình path khác. Production hiện chặn RF write
vô điều kiện, nhưng cần commissioning/single-owner thực tế trước khi mở feature.
