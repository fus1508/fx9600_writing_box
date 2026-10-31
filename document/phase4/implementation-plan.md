# Phase 4 — Hardware Readiness & SAT Preparation

2026-10-31. Chỉ thực hiện local; không deploy, upload, truy cập thiết bị, RFID
write/erase, ACK, reset hoặc thay đổi RF/scanner. Không commit Phase 4.

Checkpoint Phase 3 **đã tồn tại**: `3b6f9ff`, parent `e7714ea`, đúng message
`Phase 3 — Realtime Device Health & ESP32 UI`. Đối chiếu đủ 30 file (11 sửa/19 mới)
với completion report, tracked/staged diff rỗng; 13 Java suites baseline PASS.
Không tạo commit trùng. Branch mới `feature/fx9600-phase4-hardware-readiness`.
Hai audit untracked giữ nguyên; không worktree/main/merge/push.

1. Đọc audit, Phase 1–3, Java/ESP32, scripts và release hiện có; giữ toàn bộ gate.
2. A: cài Python/PlatformIO cục bộ dưới target nếu khả dụng; build đúng pinned
   dependencies, ghi RAM/flash và phân biệt sample-config build với firmware deploy.
   Mọi source fix phải có test/build RED trước, GREEN sau.
3. B: nghiên cứu nguồn Zebra chính thức; kiểm định reproducibility bằng hai build
   độc lập trước fix; bổ sung deterministic archive/JAR và provenance nếu cần.
   Giữ tar candidate, không tự tạo installer khi thiếu firmware contract.
4. C/D: xuất public SDK surface không khởi tạo SDK/native; đối chiếu tài liệu đúng
   version, giữ block khi không có contract TID/CDC/liveness đã xác minh.
5. E: công cụ latency phân tích CSV offline, test-first dữ liệu sai/mất mẫu/clock
   uncertainty; preflight read-only, SAT matrix, evidence templates và rollback.
6. Chạy Java regression/release8, host C++, PlatformIO, artifact faults, launcher/
   supervisor và rollback simulation; lưu raw evidence dưới target/phase4-evidence.
7. Reviewer subagent độc lập chỉ đọc source/tests/build/security/concurrency/
   rollback/assumptions; sửa findings, retest, hoàn thiện 11 báo cáo bắt buộc.

Nhãn bằng chứng: VERIFIED LOCAL; VERIFIED FROM VENDOR DOCUMENTATION; INFERENCE;
HARDWARE VERIFICATION REQUIRED; NOT RUN / BLOCKED. Không dùng local PASS thay SAT.
