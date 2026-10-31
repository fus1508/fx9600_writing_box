# RFID SDK & tag identity readiness

**VERIFIED LOCAL** 2026-10-31: JAR 608931 bytes, SHA256
`b1929f3686357e3a61ecf8a817446c10c89906994dd7648bbe11fa4eb7623fe4`.
Manifest chỉ có Ant 1.9.6/Created-By Java 1.8.0_242, không Implementation-Version.
Maven `1.0.1` không chứng minh release vendor. Không có native .so/.dll trong JAR;
không chứng minh target có đủ native dependencies/ABI/license.

Public surface xuất bằng `Class.forName(..., false, loader)` và reflection, không
khởi tạo reader/native, không gọi API thao tác. Raw `target/phase4-evidence/sdk-surface.txt`.
IdentityCheck là harness mô phỏng sẵn có, chạy lại qua verify; reject duplicate EPC
với TID khác, TID thiếu/không full, discovery unverified và EPC không ổn định.
Không dùng các cờ attestation do fake cấp làm bằng chứng TID vật lý.

| Public API hiện có | Đánh giá |
|---|---|
| readWait(String, ReadAccessParams, AntennaInfo), writeWait(String, WriteAccessParams, AntennaInfo) | Không có overload boolean TID selector; String là selector chưa chứng minh same-target sau đổi EPC |
| PreFilters.PreFilter: bank, bit offset, pattern, pattern bit count | Có surface synchronous prefilter; chưa chứng minh synchronous access tôn trọng filter/session/SL này |
| readEvent/writeEvent(params, AccessFilter, AntennaInfo) | Hướng asynchronous có filter; chưa chứng minh completion, per-tag status/correlation, cancellation/cleanup |
| AccessFilter TagPatternA/B, TagPatternBase mask/bank/offset; TagData memory data/PC | Có thể khảo sát full TID read-only sau contract; không suy completeness/units chỉ từ tên setter |

[Android SDK 4.0.0.9 guide](https://techdocs.zebra.com/dcs/rfid/android/4-0-0-9/tutorials/rfiddevguide/)
và [FX API matrix 2.0.5.275](https://techdocs.zebra.com/dcs/rfid/android/2-0-5-275/tutorials/api_compatibility_matrix/fx9600-7500-apilist)
là **VERIFIED FROM VENDOR DOCUMENTATION** cho phiên bản/package riêng của chúng.
Không áp dụng overload `bTIDPrefilter` Android lên `com.mot.rfid.api3` bundled.
JavaDoc/sample đúng SHA local + firmware 3.32.37 **NOT RUN / BLOCKED**.

[Impinj MR6-P product](https://www.impinj.com/products/endpoint-ics/impinj-monza-r6-series)
xác nhận serialized Tag ID và EPC capacity 96/128 bit ở cấp IC (**VERIFIED FROM
VENDOR DOCUMENTATION**). Không xác nhận exact serial layout/length lô tag, PC80,
SDK offsets hay tag uniqueness. Không suy capacity là EPC80 write compatibility.

**HARDWARE VERIFICATION REQUIRED**, kế hoạch read-only sau phê duyệt riêng:

1. Ghi FX/Java/native/JAR SHA, tag SKU/lot và exact datasheet TID map; vendor ký rõ
   offset/count units, serial completeness, unique reporting và filter semantics.
2. Fixture ground truth 0/1/2 tag, hai tag cùng EPC khác serial, tag ngoài box và
   tag swap; antenna đã commission, không đổi RF/region/power để thử mò.
3. Inventory + read full TID trên mọi observation; so count/identity với fixture,
   kiểm EPC aggregation không làm mất duplicate. Thiếu/khóa/partial TID phải gate.
4. So sánh synchronous prefilter với asynchronous AccessFilter bằng **read** TID/
   EPC/PC; ghi mask/bank/bit offset/session/antenna/status và timeout/stop/cleanup.
   Không vừa chạy hai access path. Không suy read thành công là write contract.
5. Same-target xuyên EPC change cần test tag disposable và write authorization riêng;
   chỉ đọc không chứng minh được. EPC80/PC units/readback cũng tiếp tục BLOCKED.
6. Xác minh RF isolation vật lý/antenna scope/interlock, JVM chết không còn server-side
   session; TID không chống clone và không thay enclosure.

Production `RfidGateway` giữ unsupported capability; select/write/read vẫn ném
HARDWARE_VERIFICATION_REQUIRED. Không adapter mới, config bypass hoặc RFID write.
Java --release8 PASS chỉ là compile API/bytecode, không Java8/native runtime PASS.
