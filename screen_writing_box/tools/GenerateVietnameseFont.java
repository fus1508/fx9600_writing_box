import java.awt.*;
import java.awt.font.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

// Generate the TFT_eSPI VLW array from the supplied font using only the JDK.
public class GenerateVietnameseFont {
    public static void main(String[] args) throws Exception {
        int size = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        Font font = Font.createFont(Font.TRUETYPE_FONT, new File(args[0])).deriveFont((float)size);
        BufferedImage canvas = new BufferedImage(128, 128, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = canvas.createGraphics();
        g.setFont(font);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        FontRenderContext context = g.getFontRenderContext();
        FontMetrics metrics = g.getFontMetrics();
        ArrayList<Integer> codes = new ArrayList<>();
        for (int c = 33; c <= 0x1eff; c++) {
            if ((c <= 0x024f || c >= 0x1ea0) && font.canDisplay((char)c)) codes.add(c);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(codes.size()); out.writeInt(11); out.writeInt(size); out.writeInt(0);
        out.writeInt(metrics.getAscent()); out.writeInt(metrics.getDescent());
        ByteArrayOutputStream bitmaps = new ByteArrayOutputStream();
        for (int c : codes) {
            GlyphVector glyph = font.createGlyphVector(context, new char[]{(char)c});
            Rectangle bounds = glyph.getPixelBounds(context, 0, 0);
            int advance = Math.round(glyph.getGlyphMetrics(0).getAdvanceX());
            out.writeInt(c); out.writeInt(bounds.height); out.writeInt(bounds.width);
            out.writeInt(advance); out.writeInt(-bounds.y); out.writeInt(bounds.x); out.writeInt(0);
            g.setColor(Color.BLACK); g.fillRect(0, 0, 128, 128);
            g.setColor(Color.WHITE); g.drawGlyphVector(glyph, -bounds.x, -bounds.y);
            for (int y = 0; y < bounds.height; y++)
                for (int x = 0; x < bounds.width; x++) bitmaps.write(canvas.getRaster().getSample(x, y, 0));
        }
        out.write(bitmaps.toByteArray());
        try (PrintWriter header = new PrintWriter(args[1], "UTF-8")) {
            header.println("#pragma once\n#include <Arduino.h>\n// Generated from assets/be_vietnam_pro.ttf by tools/GenerateVietnameseFont.java\nconst uint8_t beVietnamPro" + size + "[] PROGMEM = {");
            byte[] data = bytes.toByteArray();
            for (int i = 0; i < data.length; i++) {
                if (i % 16 == 0) header.print("    ");
                header.printf("0x%02X,", data[i] & 255);
                if (i % 16 == 15) header.println();
            }
            header.println("\n};");
        }
        g.dispose();
        System.out.println("Generated " + codes.size() + " glyphs, " + bytes.size() + " bytes");
    }
}
