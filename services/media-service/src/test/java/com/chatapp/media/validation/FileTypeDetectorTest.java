package com.chatapp.media.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;

import com.chatapp.media.support.Samples;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** No Spring, no Docker: the detector only looks at the bytes of a file. */
class FileTypeDetectorTest {

    private final FileTypeDetector detector = new FileTypeDetector();

    @TempDir
    Path dir;

    private Optional<FileFormat> detect(byte[] content, String extension) {
        return detector.detect(Samples.write(dir, "sample.bin", content), extension);
    }

    // ----------------------------------------------------------- supported types

    @Test
    void recognizesImages() {
        assertThat(detect(Samples.jpeg(20, 10), "jpg")).contains(FileFormat.JPEG);
        assertThat(detect(Samples.png(20, 10), "png")).contains(FileFormat.PNG);
        assertThat(detect(Samples.gif(20, 10), "gif")).contains(FileFormat.GIF);
        assertThat(detect(Samples.webpExtended(20, 10), "webp")).contains(FileFormat.WEBP);
    }

    @Test
    void recognizesVideoAndAudioContainers() {
        assertThat(detect(Samples.mp4(), "mp4")).contains(FileFormat.MP4);
        assertThat(detect(Samples.m4a(), "m4a")).contains(FileFormat.M4A);
        assertThat(detect(Samples.mov(), "mov")).contains(FileFormat.MOV);
        assertThat(detect(Samples.webm(), "webm")).contains(FileFormat.WEBM);
        assertThat(detect(Samples.mp3WithId3(), "mp3")).contains(FileFormat.MP3);
        assertThat(detect(Samples.mp3Frame(), "mp3")).contains(FileFormat.MP3);
        assertThat(detect(Samples.ogg(), "ogg")).contains(FileFormat.OGG);
        assertThat(detect(Samples.wav(), "wav")).contains(FileFormat.WAV);
    }

    @Test
    void recognizesDocuments() {
        assertThat(detect(Samples.pdf(), "pdf")).contains(FileFormat.PDF);
        assertThat(detect(Samples.docx(), "docx")).contains(FileFormat.DOCX);
        assertThat(detect(Samples.xlsx(), "xlsx")).contains(FileFormat.XLSX);
        assertThat(detect(Samples.pptx(), "pptx")).contains(FileFormat.PPTX);
    }

    @Test
    void detectionIgnoresTheFileName() {
        // a PNG called ".jpg" is still a PNG: the extension is checked later by MediaRules
        assertThat(detect(Samples.png(5, 5), "jpg")).contains(FileFormat.PNG);
        assertThat(detect(Samples.pdf(), "")).contains(FileFormat.PDF);
    }

    // ------------------------------------------------------ things that are refused

    @Test
    void executablesAreNeverRecognized() {
        assertThat(detect(Samples.windowsExecutable(), "jpg")).isEmpty();
        assertThat(detect(Samples.windowsExecutable(), "exe")).isEmpty();
        assertThat(detect(Samples.windowsExecutable(), "txt")).isEmpty();      // has zero bytes: not text
        assertThat(detect(Samples.elfExecutable(), "png")).isEmpty();
    }

    @Test
    void htmlSvgAndScriptsAreRefusedEvenWhenCalledText() {
        assertThat(detect(Samples.text("<html><body>hi</body></html>"), "txt")).isEmpty();
        assertThat(detect(Samples.text("  \n <HTML>"), "txt")).isEmpty();
        assertThat(detect(Samples.text("<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>"), "txt")).isEmpty();
        assertThat(detect(Samples.text("<script>alert(1)</script>"), "csv")).isEmpty();
        assertThat(detect(Samples.text("<?xml version='1.0'?><a/>"), "txt")).isEmpty();
        assertThat(detect(Samples.text("<!DOCTYPE html><p>x</p>"), "txt")).isEmpty();
        assertThat(detect(Samples.text("<svg/>"), "png")).isEmpty();
    }

    @Test
    void aPlainZipIsNotAnOfficeFile() {
        assertThat(detect(Samples.plainZip(), "zip")).isEmpty();
        assertThat(detect(Samples.plainZip(), "docx")).isEmpty();
        assertThat(detect(Samples.zip("[Content_Types].xml", "other/file.xml"), "docx")).isEmpty();
        // a zip that claims to be two Office kinds at once
        assertThat(detect(Samples.zip("[Content_Types].xml", "word/a.xml", "xl/b.xml"), "docx")).isEmpty();
    }

    @Test
    void zipsWithTooManyEntriesAreRefusedWithoutUnpacking() {
        assertThat(detect(Samples.zipWithManyEntries(2500), "docx")).isEmpty();
        assertThat(detect(Samples.zipWithManyEntries(50), "docx")).contains(FileFormat.DOCX);
    }

    @Test
    void brokenZipsAreRefused() {
        byte[] zip = Samples.docx();
        byte[] cut = java.util.Arrays.copyOf(zip, zip.length / 2);
        assertThat(detect(cut, "docx")).isEmpty();
    }

    @Test
    void otherContainersAreNotSupported() {
        assertThat(detect(Samples.matroska(), "mkv")).isEmpty();                    // Matroska looks like WebM at the start
        assertThat(detect(Samples.avi(), "avi")).isEmpty();
        assertThat(detect(Samples.aacAdts(), "aac")).isEmpty();                     // AAC is not MP3
        assertThat(detect(Samples.isoMedia("heic"), "heic")).isEmpty();             // images in the MP4 family
        assertThat(detect(Samples.isoMedia("avif"), "avif")).isEmpty();
    }

    @Test
    void emptyFileIsNothing() {
        assertThat(detect(new byte[0], "txt")).isEmpty();
    }

    // ------------------------------------------------------------------- text

    @Test
    void plainTextAndCsvNeedTheRightExtension() {
        assertThat(detect(Samples.text("hello world\nsecond line"), "txt")).contains(FileFormat.TEXT);
        assertThat(detect(Samples.text("name,age\nAnn,30\n"), "csv")).contains(FileFormat.TEXT);
        assertThat(detect(Samples.text("hello world"), "")).isEmpty();
        assertThat(detect(Samples.text("hello world"), "jpg")).isEmpty();
        assertThat(detect(Samples.text("hello world"), "html")).isEmpty();
    }

    @Test
    void textMayContainAnyUnicodeAndABom() {
        assertThat(detect(Samples.text("﻿namaste नमस्ते 😀"), "txt")).contains(FileFormat.TEXT);
    }

    @Test
    void textWithZeroBytesOrBrokenUtf8IsNotText() {
        assertThat(detect(new byte[]{'a', 'b', 0, 'c'}, "txt")).isEmpty();
        assertThat(detect(new byte[]{'a', (byte) 0xC3, 0x28, 'b'}, "txt")).isEmpty();      // invalid UTF-8
        assertThat(detect(new byte[]{(byte) 0xFE, (byte) 0xFF, 0x61, 0x62}, "txt")).isEmpty();      // UTF-16 text
        assertThat(detect(new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0xFF, 0x62}, "txt")).isEmpty(); // invalid MP3 header too
    }

    @Test
    void aMultiByteCharacterCutAtTheEndOfTheSampleIsNotAnError() {
        // 8191 ASCII bytes + the 3-byte character (U+0928): the 8 KB sample ends in the middle of it
        StringBuilder text = new StringBuilder();
        text.append("a".repeat(8191)).append("न").append("b".repeat(100));
        assertThat(detect(Samples.text(text.toString()), "txt")).contains(FileFormat.TEXT);
    }
}
