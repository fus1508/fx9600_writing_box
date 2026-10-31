# Phase 4 hardware/vendor blockers

2026-10-31. **LOCAL preparation có thể nghiệm thu; HARDWARE-READY/deploy/SAT toàn
hệ thống vẫn BLOCKED.** Không hardware action nào chạy trong Phase 4.

| ID | Trạng thái | Evidence cần đóng / owner đề xuất |
|---|---|---|
| A1 firmware compile | VERIFIED LOCAL — BUILD PASS | Đã đóng blocker toolchain Phase3; sample-config artifact, không deploy image |
| A2 target board/TFT/RAM | HARDWARE VERIFICATION REQUIRED | S3 module/4MB/PSRAM/wiring/glyph, heap/stack/high-water/render scheduling; embedded/QA |
| B1 package/lifecycle3.32.37 | NOT RUN / BLOCKED | Exact release notes/SDK builder/sample/layout/permissions/user/env; mẫu vendor background khác supervisor foreground; Zebra/platform |
| B2 Java8/native/ABI/license | HARDWARE VERIFICATION REQUIRED | Runtime1.8.0_102, matching SDK/native/support matrix, license distribution, RAM2JVM; Zebra/platform |
| C1 full TID/duplicate EPC | NOT RUN / BLOCKED | JavaDoc đúng SHA, tag lot serial map, discovery không aggregation mất duplicate, same-target access; RFID/vendor |
| C2 same-target/EPC80/PC | NOT RUN / BLOCKED | Prefilter vs AccessFilter semantics/status/cleanup; EPC đổi vẫn đúng TID; units/PC/readback; không suy từ Android; RFID |
| C3 RF isolation/antenna/session | HARDWARE VERIFICATION REQUIRED | Ground truth0/1/2 tag/ngoài box/swap, đúng antenna/interlock, RF thực dừng sau JVM crash; RFID/cơ khí |
| D1 DS5502 identity/raw/driver | NOT RUN / BLOCKED | Exact FW/host mode/USB identity, Linux ARM/kernel CDC, LF/raw persistence, exclusive owner, read/open/close/renumber races; scanner/platform |
| D2 scanner liveness | NOT RUN / BLOCKED | Query/response/correlation/timeout + model/FW support; tiếp tục CONNECTED/UNKNOWN, không SSI/SNAPI/RTA thử |
| E1 physical latency | NOT RUN / BLOCKED | ≥300 event-redraw; ≥30 each disconnect; clock alignment/visible evidence, p95≤500/1000ms, network max≤3s; QA/embedded |
| E2 cold boot/lifecycle | NOT RUN / BLOCKED | ≥20cold boots, ≥30 faults phù hợp, AutoStart đúng1owner, no unsafe restart, stop cleanup; QA/platform |
| E3 flash recovery/durability | HARDWARE VERIFICATION REQUIRED | FileLock/force/atomic rename/dir fsync/brownout/space/wear trên flash, backup toàn state; platform/QA |
| E4 RFID write SAT | NOT RUN / BLOCKED — ngoài nhiệm vụ | Adapter production chưa triển khai; ≥300cycles chỉ mission riêng/tag disposable/explicit write approval + independent review |

Gate Phase1–3 giữ nguyên: production access unsupported; không false PASS trước
same-target readback + durable VERIFIED, không auto ACK/replay, singleton/session
fence/restart budget không bypass. Unresolved/corrupt state vẫn khóa qua rollback.
Không có config bật write/liveness. Local benchmark không chuyển dòng hardware PASS.

Điều kiện tối thiểu trước deploy: đóng B1/B2, có approved installer và lifecycle,
state commissioning/backup/rollback, đúng board/USB/network/security policy. Read-only
SAT cần authorization riêng và không bật production write. Mở write chỉ sau đóng
C1/C2/C3/E3 và adapter/test/review riêng; tài liệu Phase4 không phê duyệt thao tác đó.
