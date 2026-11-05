package com.chatapp.media.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;

import com.chatapp.media.exception.BadRequestException;
import com.chatapp.media.support.Samples;
import com.chatapp.media.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageInspectorTest {

    private final ImageInspector inspector = new ImageInspector(TestProperties.defaults());

    @TempDir
    Path dir;

    private Path file(byte[] content) {
        return Samples.write(dir, "image.bin", content);
    }

    private BufferedImage decode(byte[] jpeg) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BadRequestException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("INVALID_IMAGE"));
    }

    // -------------------------------------------------------------------- size

    @Test
    void readsTheSizeOfEveryImageType() {
        assertThat(inspector.readSize(file(Samples.jpeg(120, 80)), FileFormat.JPEG)).isEqualTo(new ImageInspector.Size(120, 80));
        assertThat(inspector.readSize(file(Samples.png(33, 77)), FileFormat.PNG)).isEqualTo(new ImageInspector.Size(33, 77));
        assertThat(inspector.readSize(file(Samples.gif(10, 20)), FileFormat.GIF)).isEqualTo(new ImageInspector.Size(10, 20));
    }

    @Test
    void readsTheSizeOfAllThreeWebpVariantsFromTheHeader() {
        assertThat(inspector.readSize(file(Samples.webpExtended(640, 480)), FileFormat.WEBP)).isEqualTo(new ImageInspector.Size(640, 480));
        assertThat(inspector.readSize(file(Samples.webpLossy(1024, 768)), FileFormat.WEBP)).isEqualTo(new ImageInspector.Size(1024, 768));
        assertThat(inspector.readSize(file(Samples.webpLossless(321, 123)), FileFormat.WEBP)).isEqualTo(new ImageInspector.Size(321, 123));
    }

    @Test
    void aPngThatClaimsHugeDimensionsIsRefusedWithoutDecodingIt() {
        // the file is a few dozen bytes; decoding 30000 x 30000 pixels would need gigabytes
        byte[] bomb = Samples.pngClaiming(30000, 30000);
        assertThat(bomb.length).isLessThan(200);
        long before = System.nanoTime();
        assertInvalid(() -> inspector.readSize(file(bomb), FileFormat.PNG));
        assertThat((System.nanoTime() - before) / 1_000_000).as("refused quickly").isLessThan(2000);
    }

    @Test
    void sideLimitAndPixelLimitAreBothEnforced() {
        assertInvalid(() -> inspector.readSize(file(Samples.pngClaiming(12001, 10)), FileFormat.PNG));
        assertInvalid(() -> inspector.readSize(file(Samples.pngClaiming(10, 12001)), FileFormat.PNG));
        // 8000 x 8000 = 64 million pixels: each side is fine, but together over 50 million
        assertInvalid(() -> inspector.readSize(file(Samples.pngClaiming(8000, 8000)), FileFormat.PNG));
        assertThat(inspector.readSize(file(Samples.pngClaiming(7000, 7000)), FileFormat.PNG)).isEqualTo(new ImageInspector.Size(7000, 7000));
        assertInvalid(() -> inspector.readSize(file(Samples.webpExtended(13000, 100)), FileFormat.WEBP));
    }

    @Test
    void thingsThatAreNotImagesAreRefused() {
        assertInvalid(() -> inspector.readSize(file(Samples.text("this is not a picture")), FileFormat.PNG));
        assertInvalid(() -> inspector.readSize(file(new byte[10]), FileFormat.JPEG));
        assertInvalid(() -> inspector.readSize(file(new byte[10]), FileFormat.WEBP));
        assertInvalid(() -> inspector.readSize(file(Samples.mp4()), FileFormat.GIF));
    }

    // --------------------------------------------------------------- thumbnail

    @Test
    void bigImagesGetAThumbnailOf320PixelsOnTheLongSide() throws IOException {
        byte[] thumbnail = inspector.createThumbnail(file(Samples.jpeg(1600, 800)), FileFormat.JPEG);

        BufferedImage image = decode(thumbnail);
        assertThat(image.getWidth()).isEqualTo(320);
        assertThat(image.getHeight()).isEqualTo(160);
    }

    @Test
    void portraitImagesAreScaledByTheirHeight() throws IOException {
        BufferedImage image = decode(inspector.createThumbnail(file(Samples.png(500, 1500)), FileFormat.PNG));
        assertThat(image.getHeight()).isEqualTo(320);
        assertThat(image.getWidth()).isBetween(106, 108);
    }

    @Test
    void smallImagesAreNotEnlarged() throws IOException {
        BufferedImage image = decode(inspector.createThumbnail(file(Samples.png(100, 50)), FileFormat.PNG));
        assertThat(image.getWidth()).isEqualTo(100);
        assertThat(image.getHeight()).isEqualTo(50);
    }

    @Test
    void thumbnailsAreJpegsAlsoForTransparentPngAndForGif() throws IOException {
        byte[] fromAlpha = inspector.createThumbnail(file(Samples.pngWithAlpha(800, 400)), FileFormat.PNG);
        byte[] fromGif = inspector.createThumbnail(file(Samples.gif(640, 640)), FileFormat.GIF);

        for (byte[] thumbnail : new byte[][]{fromAlpha, fromGif}) {
            assertThat(thumbnail[0] & 0xFF).isEqualTo(0xFF);          // JPEG signature FF D8 FF
            assertThat(thumbnail[1] & 0xFF).isEqualTo(0xD8);
            BufferedImage image = decode(thumbnail);
            assertThat(Math.max(image.getWidth(), image.getHeight())).isEqualTo(320);
        }
    }

    @Test
    void aLargePictureIsDecodedWithSubsamplingAndStillFitsTheLimit() throws IOException {
        // 4000 x 3000 px: subsampling by 12 while reading, then scaled to 320
        BufferedImage image = decode(inspector.createThumbnail(file(Samples.jpeg(4000, 3000)), FileFormat.JPEG));
        assertThat(image.getWidth()).isEqualTo(320);
        assertThat(image.getHeight()).isBetween(239, 241);
    }

    @Test
    void webpAndNonImagesGetNoThumbnail() {
        assertThat(inspector.createThumbnail(file(Samples.webpExtended(100, 100)), FileFormat.WEBP)).isNull();
        assertThat(inspector.createThumbnail(file(Samples.pdf()), FileFormat.PDF)).isNull();
    }

    @Test
    void anUndecodableImageIsRefused() {
        assertInvalid(() -> inspector.createThumbnail(file(Samples.brokenJpeg()), FileFormat.JPEG));
        assertInvalid(() -> inspector.createThumbnail(file(Samples.text("not an image")), FileFormat.PNG));
    }
}
