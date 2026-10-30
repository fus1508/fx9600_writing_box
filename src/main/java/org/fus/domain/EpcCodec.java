package org.fus.domain;

/** Barcode format: two ASCII prefix bytes and sixteen packed BCD digits. */
public final class EpcCodec {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private EpcCodec() { }

    public static String encode(String barcode) {
        if (barcode == null || !barcode.matches("[A-Z0-9]{2}[0-9]{16}")) {
            throw new IllegalArgumentException("Barcode must contain two uppercase letters or digits followed by 16 digits");
        }
        StringBuilder epc = new StringBuilder(20);
        for (int i = 0; i < 2; i++) {
            int value = barcode.charAt(i);
            epc.append(HEX[value >>> 4]).append(HEX[value & 15]);
        }
        return epc.append(barcode.substring(2)).toString();
    }

    public static String decode(String epc) {
        if (epc == null || !epc.matches("[0-9A-Fa-f]{20}")) {
            throw new IllegalArgumentException("EPC must contain exactly 20 hexadecimal characters");
        }
        StringBuilder barcode = new StringBuilder(18);
        for (int i = 0; i < 4; i += 2) {
            barcode.append((char) Integer.parseInt(epc.substring(i, i + 2), 16));
        }
        barcode.append(epc.substring(4));
        String result = barcode.toString();
        if (!result.matches("[A-Z0-9]{2}[0-9]{16}")) {
            throw new IllegalArgumentException("Invalid ASCII prefix or BCD digits");
        }
        return result;
    }
}
