package org.fus;

import org.fus.device.scanner.BarcodeFramer;
public final class BarcodeFramerCheck {
    private static void expect(boolean value) { if (!value) throw new AssertionError(); }
    private static String send(BarcodeFramer f, String s, long time) {
        String result = null;
        for (int i = 0; i < s.length(); i++) result = f.accept(s.charAt(i), time++);
        return result;
    }
    public static void main(String[] args) {
        BarcodeFramer f = new BarcodeFramer(18, 100);
        expect(send(f, "9K1135600", 0) == null);
        expect("9K1135600660004039".equals(send(f, "660004039\n", 20)));
        expect(send(f, "OLD", 50) == null);
        expect("9K1135600660004039".equals(send(f, "9K1135600660004039\n", 200)));
        expect(send(f, "1234567890123456789\n", 300) == null);
        expect("9K1135600660004039".equals(send(f, "9K1135600660004039\n", 400)));
        System.out.println("Barcode framing checks passed");
    }
}
