# Đo latency — harness local và SAT

Harness `org.fus.HealthLatencyCheck`, chạy sau verify:

```powershell
java -cp 'target/classes;target/test-classes' org.fus.HealthLatencyCheck
```

300 event tuần tự, fake evidence clock, server localhost và một outstanding long-poll.
Timestamp monotonic ngay trước status event tới body HTTP hoàn tất cùng máy.
Kiểm tra marker từng event, timeout là failure, không loại outlier. Raw
`target/phase3-evidence/event-http.csv`, summary cùng thư mục. Đây chỉ là Java event
→ backend publish + HTTP localhost, không đo physical detection, LAN hay ESP32.
Nearest-rank p50=index149, p95=index284 của N300 đã sort, max=index299 (zero-based).
Không cộng p95 host với các p95 khác để suy p95 end-to-end.

Lượt bàn giao cuối: N300, missed0, timeout0, p50=728us, p95=1019us, max=8675us;
raw CSV và `latency-handoff-final.txt` ở thư mục evidence trên. Kết quả chỉ áp dụng
đoạn Java event → HTTP localhost trong môi trường này, không phải KPI TFT/hardware.

SAT sau khi có phê duyệt thiết bị riêng:

1. Ghi model/firmware/SDK SHA/Java/ESP build, RSSI/network topology, clock uncertainty,
   TFT SPI/render config và load clients. Không dùng production write để benchmark.
2. >=300 normal observable events (health/WAIT_TRIGGER/FAIL; WRITING/PASS chỉ fake
   backend hoặc mission RFID được phê duyệt riêng). Timestamp event/revision/body;
   ESP log revision/bootId/sequence + receive/parse/render-done qua serial diagnostic
   hoặc GPIO test được review. Correlate từng sample; tính số missed/coalesced.
3. >=30 lần mỗi disconnect có ground truth: reader link/SDK event, scanner unplug;
   lấy t_physical từ fixture/camera và t_visible cùng external clock. Đo detection
   riêng (physical→Java event), publish riêng, HTTP riêng, render riêng và toàn pipeline.
4. >=30 lần Wi-Fi loss, route blackhole, TCP connect/read timeout, stale response
   replay và JVM/supervisor terminal stop. Đo lần cuối hợp lệ tới UNKNOWN/offline;
   verify không READY quá 3s. Kiểm tra SDK/GPI và monitor treo, recovery giữa lỗi.
5. Replug/renumber, boot thiếu thiết bị, reboot backend đổi bootId, hung CDC close,
   restore Wi-Fi/API. Không reuse partial barcode hoặc result từ boot trước.
6. Lặp dưới 4+ wait clients/503, ordinary GET, Wi-Fi yếu, chữ dài/đổi state liên tục.
   Đo stack high-water/heap của network+render tasks, freshness timer scheduling.

Lưu CSV mỗi sample: scenario, bootId, revision, sampleSequence, txId, event source,
clock/reference quality, t_detect, t_publish, t_http_receive, t_render_done,
physical_edge/visible_frame, timeout/missed/coalesced, trạng thái thực tế.
Báo N/p50/p95/max, lỗi/missed/timeouts riêng; không bỏ tail hoặc tính khác đồng hồ
Java nanoTime và ESP millis nếu chưa chứng minh offset/uncertainty.

KPI: Java event→visible redraw p95<=500ms; physical disconnect→visible p95<=1s chỉ
khi có detector đáng tin; network loss→UNKNOWN<=3s; tự reconnect và không stale READY.
Tất cả KPI phần cứng hiện **BLOCKED/NOT RUN**, không suy từ sleep/SPI MHz/timeout constants.
