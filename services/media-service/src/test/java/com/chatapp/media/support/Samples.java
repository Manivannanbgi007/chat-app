package com.chatapp.media.support;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/** Builds small but REAL sample files for tests (nothing is read from disk, nothing is downloaded). */
public final class Samples {

    private Samples() {
    }

    // ----------------------------------------------------------------- images

    public static BufferedImage picture(int width, int height, boolean alpha) {
        BufferedImage image = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(30, 120, 200));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.ORANGE);
        g.fillOval(width / 4, height / 4, Math.max(1, width / 2), Math.max(1, height / 2));
        g.dispose();
        return image;
    }

    public static byte[] jpeg(int width, int height) {
        return encode(picture(width, height, false), "jpg");
    }

    public static byte[] png(int width, int height) {
        return encode(picture(width, height, false), "png");
    }

    public static byte[] pngWithAlpha(int width, int height) {
        return encode(picture(width, height, true), "png");
    }

    public static byte[] gif(int width, int height) {
        return encode(picture(width, height, false), "gif");
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("no writer for " + format);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A real PNG whose header (IHDR) claims another size: the pixel data is NOT that big. Used for "bomb" tests. */
    public static byte[] pngClaiming(int width, int height) {
        byte[] png = png(1, 1);
        // layout: 8 bytes signature, 4 length, 4 "IHDR", 13 bytes data (width, height, ...), 4 CRC
        writeInt(png, 16, width);
        writeInt(png, 20, height);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 4 + 13);
        writeInt(png, 29, (int) crc.getValue());
        return png;
    }

    /** A JPEG cut in half: starts like a JPEG, but the picture cannot be decoded. */
    public static byte[] brokenJpeg() {
        byte[] full = jpeg(200, 200);
        byte[] head = new byte[Math.min(full.length, 200)];
        System.arraycopy(full, 0, head, 0, head.length);
        return head;
    }

    /** First bytes of a WebP file with the given size in the "extended" (VP8X) header. Enough for header checks. */
    public static byte[] webpExtended(int width, int height) {
        byte[] b = new byte[40];
        ascii(b, 0, "RIFF");
        writeIntLe(b, 4, 32);
        ascii(b, 8, "WEBP");
        ascii(b, 12, "VP8X");
        writeIntLe(b, 16, 10);
        writeLe24(b, 24, width - 1);
        writeLe24(b, 27, height - 1);
        return b;
    }

    public static byte[] webpLossy(int width, int height) {
        byte[] b = new byte[40];
        ascii(b, 0, "RIFF");
        writeIntLe(b, 4, 32);
        ascii(b, 8, "WEBP");
        ascii(b, 12, "VP8 ");
        writeIntLe(b, 16, 20);
        b[23] = (byte) 0x9D;
        b[24] = 0x01;
        b[25] = 0x2A;
        b[26] = (byte) (width & 0xFF);
        b[27] = (byte) ((width >> 8) & 0x3F);
        b[28] = (byte) (height & 0xFF);
        b[29] = (byte) ((height >> 8) & 0x3F);
        return b;
    }

    public static byte[] webpLossless(int width, int height) {
        byte[] b = new byte[40];
        ascii(b, 0, "RIFF");
        writeIntLe(b, 4, 32);
        ascii(b, 8, "WEBP");
        ascii(b, 12, "VP8L");
        writeIntLe(b, 16, 20);
        b[20] = 0x2F;
        long bits = (width - 1L) | ((height - 1L) << 14);
        b[21] = (byte) (bits & 0xFF);
        b[22] = (byte) ((bits >> 8) & 0xFF);
        b[23] = (byte) ((bits >> 16) & 0xFF);
        b[24] = (byte) ((bits >> 24) & 0xFF);
        return b;
    }

    // ------------------------------------------------------- audio and video

    /** An ISO media file start: [size]"ftyp"[brand]... (the rest is padding). */
    public static byte[] isoMedia(String brand) {
        byte[] b = new byte[64];
        writeInt(b, 0, 32);
        ascii(b, 4, "ftyp");
        ascii(b, 8, brand);
        ascii(b, 12, "0000");
        ascii(b, 16, brand);
        return b;
    }

    public static byte[] mp4() {
        return isoMedia("isom");
    }

    public static byte[] m4a() {
        return isoMedia("M4A ");
    }

    public static byte[] mov() {
        return isoMedia("qt  ");
    }

    public static byte[] webm() {
        byte[] b = new byte[80];
        b[0] = 0x1A;
        b[1] = 0x45;
        b[2] = (byte) 0xDF;
        b[3] = (byte) 0xA3;
        b[4] = (byte) 0x9F;
        ascii(b, 12, "webm");
        return b;
    }

    public static byte[] matroska() {
        byte[] b = new byte[80];
        b[0] = 0x1A;
        b[1] = 0x45;
        b[2] = (byte) 0xDF;
        b[3] = (byte) 0xA3;
        ascii(b, 12, "matroska");
        return b;
    }

    public static byte[] mp3WithId3() {
        byte[] b = new byte[64];
        ascii(b, 0, "ID3");
        b[3] = 0x03;
        return b;
    }

    public static byte[] mp3Frame() {
        byte[] b = new byte[64];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xFB;       // MPEG 1 layer III
        b[2] = (byte) 0x90;
        return b;
    }

    public static byte[] aacAdts() {
        byte[] b = new byte[64];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xF1;       // layer bits 00: AAC, not MP3
        return b;
    }

    public static byte[] ogg() {
        byte[] b = new byte[64];
        ascii(b, 0, "OggS");
        return b;
    }

    public static byte[] wav() {
        byte[] b = new byte[44];
        ascii(b, 0, "RIFF");
        writeIntLe(b, 4, 36);
        ascii(b, 8, "WAVE");
        ascii(b, 12, "fmt ");
        return b;
    }

    public static byte[] avi() {
        byte[] b = new byte[44];
        ascii(b, 0, "RIFF");
        ascii(b, 8, "AVI ");
        return b;
    }

    // -------------------------------------------------------------- documents

    public static byte[] pdf() {
        return "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n"
                .getBytes(StandardCharsets.US_ASCII);
    }

    /** An Office file is a zip with these parts. */
    public static byte[] docx() {
        return zip("[Content_Types].xml", "word/document.xml");
    }

    public static byte[] xlsx() {
        return zip("[Content_Types].xml", "xl/workbook.xml");
    }

    public static byte[] pptx() {
        return zip("[Content_Types].xml", "ppt/presentation.xml");
    }

    /** A plain zip archive (not an Office file). */
    public static byte[] plainZip() {
        return zip("readme.txt", "data/file.bin");
    }

    public static byte[] zip(String... entryNames) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (String name : entryNames) {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A zip with very many entries (more than the service allows). */
    public static byte[] zipWithManyEntries(int count) {
        String[] names = new String[count];
        names[0] = "[Content_Types].xml";
        for (int i = 1; i < count - 1; i++) {
            names[i] = "word/part" + i + ".xml";
        }
        names[count - 1] = "word/document.xml";
        return zip(names);
    }

    // --------------------------------------------------------- dangerous files

    public static byte[] windowsExecutable() {
        byte[] b = new byte[128];
        b[0] = 'M';
        b[1] = 'Z';
        return b;
    }

    public static byte[] elfExecutable() {
        return new byte[]{0x7F, 'E', 'L', 'F', 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    }

    public static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- helpers

    public static Path write(Path directory, String name, byte[] content) {
        try {
            return Files.write(directory.resolve(name), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void ascii(byte[] target, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static void writeIntLe(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    private static void writeLe24(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
    }
}
