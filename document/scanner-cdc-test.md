# Kiểm tra dữ liệu scanner USB CDC trên FX9600

## Mục đích và trạng thái

Xác định byte barcode, tiền tố và hậu tố mà ứng dụng đọc được từ scanner. Thiết bị đã xuất hiện tại `/dev/ttyACM0` và Java trên reader là OpenJDK 1.8.0_102. Khi đọc với cấu hình cổng trước đó, dữ liệu giữa các mẫu có `0a 0a`; chưa thể kết luận scanner gửi hai LF vì cấu hình serial có thể đã chuyển đổi byte. Tên thiết bị có thể thay đổi sau khi cắm lại hoặc khởi động lại.

Cần kiểm tra bằng barcode biết trước nội dung, quét một lần và đối chiếu byte. Phép thử ở chế độ raw cho thấy dữ liệu ứng dụng đọc từ cổng CDC; không phải phân tích gói USB ở mức bus.

## Chuẩn bị

- Kết nối SSH vào FX9600, cắm scanner đã cấu hình USB CDC Host.
- Dừng các ứng dụng hoặc lệnh khác đang đọc scanner; không mở đồng thời hai bộ đọc.
- Kiểm tra thiết bị và quyền:

```sh
ls -l /dev/ttyACM* /dev/ttyUSB*
id
test -r /dev/ttyACM0 && echo 'Co quyen doc' || echo 'Khong co quyen doc'
```

Các ví dụ sau dùng `/dev/ttyACM0`: thay bằng thiết bị thực tế. Nếu không có quyền đọc hoặc cấu hình cổng, nhờ quản trị viên cấp quyền phù hợp; không tự thay đổi quyền rộng cho thiết bị. `dmesg` có thể bị từ chối với tài khoản `rfidadm` và không bắt buộc cho phép thử này.

## Đọc byte ở chế độ raw

Chạy từng lệnh và kiểm tra lỗi trước khi sang bước tiếp theo. Lưu cấu hình hiện tại:

```sh
stty -F /dev/ttyACM0 -g > /tmp/scanner-stty-backup
cat /tmp/scanner-stty-backup
```

Nếu lệnh không thành công hoặc tệp rỗng, dừng lại; chưa thay đổi cấu hình cổng. Nếu firmware không hỗ trợ `stty -F`, cần tìm cú pháp `stty` phù hợp trước khi tiếp tục.

Chuyển cổng sang raw và đọc HEX:

```sh
stty -F /dev/ttyACM0 raw -echo
od -An -tx1 -v /dev/ttyACM0
```

Quét barcode đã biết, ghi lại toàn bộ dữ liệu xuất hiện và nhấn `Ctrl+C` dừng `od`. `od` có thể đệm dữ liệu, vì vậy quét cùng barcode thêm một lần nếu chưa thấy đủ dữ liệu; khi đối chiếu phải tính tới các lần quét lặp. Không rút scanner hoặc đóng phiên trước khi khôi phục cấu hình nếu có thể.

Khôi phục ngay sau thử nghiệm, kể cả khi `od` báo lỗi:

```sh
stty -F /dev/ttyACM0 "$(cat /tmp/scanner-stty-backup)"
```

Nếu khôi phục báo lỗi, ghi lại lỗi và không tiếp tục vận hành cho tới khi cấu hình được khôi phục hoặc thiết lập lại đúng. Không dùng bản sao lưu này cho thiết bị khác.

## Đọc kết quả

Mỗi cặp chữ số HEX là một byte. Ví dụ, nếu quét `9K1135600660004039` và scanner gửi CRLF, dữ liệu sẽ là:

```text
39 4b 31 31 33 35 36 30 30 36 36 30 30 30 34 30 33 39 0d 0a
```

- `39 4b ... 33 39`: các byte ASCII của nội dung barcode.
- `0d`: CR; `0a`: LF; `0d 0a`: CRLF. Có thể có prefix hoặc suffix khác tùy cấu hình scanner.
- `0a 0a` là hai LF trong dữ liệu đã đọc; so sánh kết quả raw với trước đó để phát hiện ảnh hưởng cấu hình cổng.

Đối chiếu số byte và thứ tự với barcode in trên nhãn. Thử ít nhất hai barcode đã biết, bao gồm mã bắt đầu bằng số 0; kiểm tra dữ liệu không bị thêm tiền tố, mất ký tự hoặc thay đổi thứ tự. Ghi lại chế độ scanner, đường dẫn cổng và hậu tố thực tế.

Ứng dụng trạm hiện dùng delimiter LF. Nếu kết quả raw khác, cấu hình scanner cho phù hợp và kiểm tra lại trước khi chạy trạm. Sau phép thử, khôi phục cấu hình cổng như hướng dẫn ở trên, rồi thiết lập chế độ raw phù hợp cho ứng dụng.
