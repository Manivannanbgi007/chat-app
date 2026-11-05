package com.chatapp.media.validation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.springframework.stereotype.Component;

/**
 * Finds out what a file REALLY is by reading its first bytes ("magic numbers"), whatever its name or the
 * browser's Content-Type say. Anything unknown (programs, scripts, HTML, SVG, archives ...) gives "empty".
 *
 * Protection against "zip bombs": for Office files we only read the list of names in the zip (the central
 * directory); we never unpack anything, and we refuse zips with too many entries or absurd declared sizes.
 */
@Component
public class FileTypeDetector {

    private static final int HEADER_BYTES = 8192;
    private static final int MAX_ZIP_ENTRIES = 2000;
    private static final long MAX_ZIP_DECLARED_BYTES = 1024L * 1024 * 1024;   // 1 GB when unpacked (never done)

    /**
     * @param file      the uploaded file on disk
     * @param extension the lower-case extension from the file name without the dot, or "" (only used to accept
     *                  plain text: we only treat a file as text when it is called .txt or .csv)
     */
    public Optional<FileFormat> detect(Path file, String extension) {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(HEADER_BYTES);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (head.length == 0) {
            return Optional.empty();
        }

        if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(FileFormat.JPEG);
        }
        if (startsWith(head, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(FileFormat.PNG);
        }
        if (startsWithAscii(head, 0, "GIF87a") || startsWithAscii(head, 0, "GIF89a")) {
            return Optional.of(FileFormat.GIF);
        }
        if (startsWithAscii(head, 0, "RIFF") && head.length >= 12) {
            if (startsWithAscii(head, 8, "WEBP")) {
                return Optional.of(FileFormat.WEBP);
            }
            if (startsWithAscii(head, 8, "WAVE")) {
                return Optional.of(FileFormat.WAV);
            }
            return Optional.empty();          // AVI and other RIFF files are not supported
        }
        if (head.length >= 12 && startsWithAscii(head, 4, "ftyp")) {
            return isoBrand(head);
        }
        if (startsWith(head, 0x1A, 0x45, 0xDF, 0xA3)) {
            return webmOnly(head);
        }
        if (startsWithAscii(head, 0, "OggS")) {
            return Optional.of(FileFormat.OGG);
        }
        if (startsWithAscii(head, 0, "%PDF-")) {
            return Optional.of(FileFormat.PDF);
        }
        if (isMp3(head)) {
            return Optional.of(FileFormat.MP3);
        }
        if (startsWith(head, 'P', 'K', 0x03, 0x04)) {
            return officeDocument(file);
        }
        if (("txt".equals(extension) || "csv".equals(extension)) && looksLikeSafeText(head)) {
            return Optional.of(FileFormat.TEXT);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------ containers

    /** The brand after "ftyp" tells what kind of ISO media file it is. */
    private Optional<FileFormat> isoBrand(byte[] head) {
        String brand = new String(head, 8, 4, StandardCharsets.ISO_8859_1);
        switch (brand) {
            case "qt  ":
                return Optional.of(FileFormat.MOV);
            case "M4A ", "M4B ", "M4P ":
                return Optional.of(FileFormat.M4A);
            case "heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis":
                return Optional.empty();      // image files in the same container family: not supported
            default:
                return Optional.of(FileFormat.MP4);
        }
    }

    /** Matroska (.mkv) uses the same start as WebM; only files that say "webm" are accepted. */
    private Optional<FileFormat> webmOnly(byte[] head) {
        int scan = Math.min(head.length, 64);
        String start = new String(head, 0, scan, StandardCharsets.ISO_8859_1);
        return start.contains("webm") ? Optional.of(FileFormat.WEBM) : Optional.empty();
    }

    private boolean isMp3(byte[] head) {
        if (startsWithAscii(head, 0, "ID3")) {
            return true;
        }
        if (head.length < 4) {
            return false;
        }
        int b1 = head[1] & 0xFF;
        int b2 = head[2] & 0xFF;
        boolean frameSync = (head[0] & 0xFF) == 0xFF && (b1 & 0xE0) == 0xE0;
        boolean versionOk = ((b1 >> 3) & 0x03) != 0x01;   // 01 is a reserved version
        boolean layerOk = ((b1 >> 1) & 0x03) != 0x00;     // 00 would be ADTS/AAC or invalid
        boolean bitrateOk = (b2 >> 4) != 0x0F;            // 1111 is not allowed
        boolean sampleRateOk = ((b2 >> 2) & 0x03) != 0x03; // 11 is reserved
        // The file name must still end in .mp3 (or have no extension), see MediaRules; this check only
        // keeps obvious non-audio (for example a UTF-16 text file that starts with FF FE) from matching.
        return frameSync && versionOk && layerOk && bitrateOk && sampleRateOk;
    }

    /** DOCX / XLSX / PPTX are zip files with a known set of parts. Only names are read, nothing is unpacked. */
    private Optional<FileFormat> officeDocument(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.size() > MAX_ZIP_ENTRIES) {
                return Optional.empty();
            }
            boolean contentTypes = false;
            boolean word = false;
            boolean excel = false;
            boolean powerPoint = false;
            long declaredTotal = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                declaredTotal += Math.max(0, entry.getSize());
                if (declaredTotal > MAX_ZIP_DECLARED_BYTES) {
                    return Optional.empty();
                }
                contentTypes |= "[Content_Types].xml".equals(name);
                word |= name.startsWith("word/");
                excel |= name.startsWith("xl/");
                powerPoint |= name.startsWith("ppt/");
            }
            if (!contentTypes) {
                return Optional.empty();
            }
            if (word && !excel && !powerPoint) {
                return Optional.of(FileFormat.DOCX);
            }
            if (excel && !word && !powerPoint) {
                return Optional.of(FileFormat.XLSX);
            }
            if (powerPoint && !word && !excel) {
                return Optional.of(FileFormat.PPTX);
            }
            return Optional.empty();
        } catch (ZipException e) {
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ text

    /**
     * Plain text: no zero bytes, valid UTF-8, and it does not look like markup that a browser could run
     * (HTML, SVG, scripts, XML). Only the first 8 KB are inspected.
     */
    private boolean looksLikeSafeText(byte[] head) {
        for (byte b : head) {
            if (b == 0) {
                return false;
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer out = CharBuffer.allocate(head.length + 1);
        // endOfInput=false: a multi-byte character cut off at the end of our 8 KB sample is not an error
        CoderResult result = decoder.decode(ByteBuffer.wrap(head), out, head.length < HEADER_BYTES);
        if (result.isError()) {
            return false;
        }
        out.flip();
        String text = out.toString().stripLeading().toLowerCase(Locale.ROOT);
        if (text.startsWith("﻿")) {
            text = text.substring(1).stripLeading();
        }
        return !(text.startsWith("<html") || text.startsWith("<svg") || text.startsWith("<script")
                || text.startsWith("<?xml") || text.startsWith("<!doctype") || text.startsWith("<!--")
                || text.startsWith("<head") || text.startsWith("<body") || text.startsWith("<iframe"));
    }

    // --------------------------------------------------------------- helpers

    private static boolean startsWith(byte[] data, int... expected) {
        if (data.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((data[i] & 0xFF) != (expected[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] data, int offset, String expected) {
        byte[] bytes = expected.getBytes(StandardCharsets.US_ASCII);
        if (data.length < offset + bytes.length) {
            return false;
        }
        return Arrays.equals(Arrays.copyOfRange(data, offset, offset + bytes.length), bytes);
    }
}
