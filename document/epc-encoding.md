# Quy tắc mã hóa và giải mã EPC

Tài liệu này mô tả định dạng dữ liệu hiện được sử dụng trong EPC của dự án FX9600. Đây là quy ước riêng của ứng dụng, không phải mã hóa EPC chuẩn GS1. Ứng dụng khác cần đọc đúng số byte và kiểm tra định dạng trước khi sử dụng dữ liệu.

## Định dạng barcode

Barcode gồm đúng **18 ký tự ASCII**, khớp biểu thức `[A-Z0-9]{2}[0-9]{16}`:

- Hai ký tự đầu là chữ in hoa `A`–`Z` hoặc chữ số `0`–`9`.
- Mười sáu ký tự tiếp theo đều là chữ số `0`–`9`.
- Giữ nguyên các số `0` đầu; không chuyển phần số thành kiểu số nguyên.

Ví dụ: `9K1135600660004039`.

## Cấu trúc EPC

EPC dài **10 byte (80 bit)**, thường được hiển thị thành **20 ký tự HEX**:

| Vị trí byte | Kích thước | Nội dung |
|---|---:|---|
| 0–1 | 2 byte | Hai ký tự đầu của barcode, mỗi ký tự là một byte ASCII. |
| 2–9 | 8 byte | Mười sáu chữ số còn lại, đóng gói BCD: mỗi chữ số là một nibble 4 bit, chữ số đầu ở nibble cao. |

Ví dụ:

```text
Barcode:  9K1135600660004039
ASCII:    39 4B
BCD:      11 35 60 06 60 00 40 39
EPC HEX:  394B1135600660004039
```

Phần BCD `11` biểu thị hai chữ số `1` và `1`; nó không phải hai byte ASCII `31 31`. Chữ hoa hoặc thường khi hiển thị HEX không ảnh hưởng giá trị byte.

## Cách giải mã

1. Lấy đúng **10 byte EPC**, không bao gồm CRC hoặc PC. Nếu SDK trả về chuỗi HEX, chuỗi phải có đúng 20 ký tự HEX.
2. Đọc byte 0 và 1 dưới dạng ASCII. Mỗi byte phải biểu diễn một ký tự trong `A`–`Z` hoặc `0`–`9`.
3. Với từng byte từ 2 đến 9, tách nibble cao `(byte >> 4) & 0xF` rồi nibble thấp `byte & 0xF`. Cả hai phải nằm trong `0`–`9`; ghép mỗi nibble thành một ký tự số.
4. Nối hai ký tự ASCII với 16 chữ số BCD. Kết quả phải khớp `[A-Z0-9]{2}[0-9]{16}`. Từ chối dữ liệu sai độ dài, tiền tố hoặc BCD; không tự bỏ byte hay sửa dữ liệu.

SDK RFID có thể trả về EPC dưới dạng byte hoặc chuỗi HEX. Nếu đọc trực tiếp EPC memory bank, mã nguồn dự án đọc dữ liệu EPC từ **byte offset 4**, sau CRC và PC; cần kiểm tra đơn vị offset và cách API của ứng dụng đích biểu diễn dữ liệu.

## Mẫu kiểm thử

| EPC HEX | Barcode giải mã | Kết quả |
|---|---|---|
| `394B1135600660004039` | `9K1135600660004039` | Hợp lệ |
| `39461135600660004039` | `9F1135600660004039` | Hợp lệ |
| `30300000000000000001` | `000000000000000001` | Hợp lệ; giữ các số 0 đầu |
| `394B113560066000403A` | — | Không hợp lệ: nibble BCD `A` |
| `394B1135600660004039FF` | — | Không hợp lệ: không đúng 10 byte |

Tham chiếu triển khai Java 8: `src/main/java/org/fus/domain/EpcCodec.java`. Việc đọc được EPC không tự xác nhận tag đã được ghi thành công: trạm ghi phải đọc lại và so sánh dữ liệu với EPC đã yêu cầu.
