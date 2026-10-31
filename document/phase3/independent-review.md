# Phase 3 — independent review

2026-10-31. Reviewer độc lập `/root/phase3_review`, context riêng, chỉ đọc theo
[independent-code-review SKILL.md](C:/Users/zleon/.agents/skills/independent-code-review/SKILL.md).
Không sửa file, tạo artifact, compile Java, cài tool, commit hoặc truy cập phần cứng.

**VERDICT cuối: APPROVE — phạm vi source/tests local.** Không có finding
Critical/Major/Minor còn mở; đây không phải phê duyệt deploy hay mở production write.

| Finding | Mức | Sửa và evidence |
|---|---|---|
| Read trả về trong khi close treo vẫn reopen; queue DiscardPolicy có thể bỏ close và tích lũy fd | Major | Session close at-most-once bằng CAS, cleanup completion/failure chặn reopen, AbortPolicy, executor shutdown sau read loop. `close-gate-red.txt` FAIL trước fix; ScannerHealthCheck GREEN kiểm close hang/read return, close count=1 và close throw giữ gate/reason |
| Tx ID rỗng được coi là wildcard, PASS/FAIL không liên quan xóa unknown outcome | Major | Latch giữ boot/tx gốc; chỉ clear khi ID đã biết và exact match. Empty ID giữ cảnh báo. `empty-tx-red.txt` FAIL trước fix; C++ tests PASS cả PASS/FAIL với tx rỗng và transaction khác |
| Monitor invalidate giữa inspect sau-open và activation; owner có thể hồi sinh session cũ | Major, vòng re-review | Marker invalidated được ghi/đọc cùng stateLock. Barrier test chụp Node A, monitor thấy missing, rồi trả captured A; owner không activate/emit scan. `activation-race-red.txt` FAIL và `activation-race-green.txt` PASS |
| Báo cáo final ghi nhầm cleanup/race thuộc C++ checks | Minor tài liệu, final closeout | Sửa coverage: cleanup/activation race thuộc ScannerHealthCheck Java; C++ kiểm parser/correlation/freshness/wrap. Không đổi code/tests nên không cần chạy lại |

Các lượt đầu CHANGES_REQUESTED; sau fix và re-review đủ ba finding, reviewer xác nhận
APPROVE. Developer không tự suy APPROVE từ test PASS. Ngoài ra developer bổ sung
test correlation transaction khác trước review, full millis wrap không revive cache
và reconnect sau outage >24 ngày; reviewer đã kiểm delta và không thấy finding mới.

Reviewer inspect git status/diff/staged diff, toàn bộ source/tests/scripts/docs mới
untracked, mission Phase 3, AGENTS và ràng buộc Phase 1/2. Hai file audit là đầu vào
giữ nguyên; branch/HEAD đúng `feature/fx9600-phase3-health-ui` / `e7714ea`, staged rỗng.
Không thay domain/journal/recovery ACK/EPC hoặc watchdog/supervisor/session fence.

Reviewer trực tiếp chạy chỉ đọc `health_ui_check.exe`, `status_parser_check.exe`
với fixture Java: PASS; `git diff --check`: PASS. Java 13 suites, compile --release8,
package, 8 faults, launcher và benchmark là evidence **developer** chạy; reviewer
đọc source/logs và các RED/GREEN, không giả nhận đã tự chạy lệnh sinh artifact.

Final regression/artifact sau verdict do developer chạy và ghi ở
[test-results.md](test-results.md). Firmware PlatformIO, runtime Java8/native SDK,
CDC/TFT thật và latency phần cứng tiếp tục BLOCKED/NOT RUN. Reviewer không chứng
nhận các giới hạn phần cứng từ host simulation.

Final closeout: reviewer đọc build-handoff-final (đủ 13 suite PASS), đối chiếu
SHA256/sidecar/size tar và benchmark N300/0 missed/0 timeout/p95=1019us; trực tiếp
chạy lại hai C++ binaries cuối với JSON fixture mới: PASS. Sửa Minor coverage trên
không thay source/test đã APPROVE.
