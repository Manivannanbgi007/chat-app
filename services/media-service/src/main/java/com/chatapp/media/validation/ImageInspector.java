package com.chatapp.media.validation;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import com.chatapp.media.config.MediaProperties;
import com.chatapp.media.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Looks inside image files safely.
 *  - Size: read from the file header WITHOUT decoding the picture, so a "decompression bomb" (a tiny file that
 *    claims to be 30000 x 30000 pixels) is refused before it can use any memory.
 *  - Thumbnail: a small JPEG (default 320 px). The picture is decoded with "sub-sampling" (skipping pixels while
 *    reading), so even a 12000 px photo needs little memory.
 * Everything that cannot be read as an image gives 400 INVALID_IMAGE.
 */
@Component
public class ImageInspector {

    private static final Logger log = LoggerFactory.getLogger(ImageInspector.class);

    public record Size(int width, int height) {
    }

    private final int maxSidePx;
    private final long maxPixels;
    private final int thumbnailMaxPx;

    public ImageInspector(MediaProperties properties) {
        this.maxSidePx = properties.media().maxImageSidePx();
        this.maxPixels = properties.media().maxImagePixels();
        this.thumbnailMaxPx = properties.media().thumbnailMaxPx();
        ImageIO.setUseCache(false);       // do not write temporary files while reading
    }

    /** Width and height of the image, checked against the limits. */
    public Size readSize(Path file, FileFormat format) {
        Size size = format == FileFormat.WEBP ? readWebpSize(file) : readWithImageIo(file);
        if (size.width() <= 0 || size.height() <= 0
                || size.width() > maxSidePx || size.height() > maxSidePx
                || (long) size.width() * size.height() > maxPixels) {
            throw invalid("The image is too large or has invalid dimensions");
        }
        return size;
    }

    /** A JPEG thumbnail of JPEG / PNG / GIF images. WebP is not supported by Java: no thumbnail (null). */
    public byte[] createThumbnail(Path file, FileFormat format) {
        if (!format.isDecodableImage()) {
            return null;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            ImageReader reader = firstReader(input);
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                // Skip pixels while reading: keep at least thumbnailMaxPx on the longer side.
                int step = Math.max(1, Math.max(width, height) / thumbnailMaxPx);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage decoded = reader.read(0, param);
                return encodeJpeg(scaleDown(decoded));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            log.info("Could not make a thumbnail ({}), refusing the image", e.getClass().getSimpleName());
            throw invalid("The image could not be read");
        }
    }

    // ------------------------------------------------------------- internals

    private Size readWithImageIo(Path file) {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            ImageReader reader = firstReader(input);
            try {
                reader.setInput(input, true, true);
                return new Size(reader.getWidth(0), reader.getHeight(0));    // header only, no pixels decoded
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw invalid("The image could not be read");
        }
    }

    private ImageReader firstReader(ImageInputStream input) throws IOException {
        if (input == null) {
            throw new IIOException("no input");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            throw new IIOException("no reader for this image");
        }
        return readers.next();
    }

    /** Draws the image at most thumbnailMaxPx wide/high on a white background (JPEG has no transparency). */
    private BufferedImage scaleDown(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min(1.0, (double) thumbnailMaxPx / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.8f);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(output);
                writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
            }
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /** Reads the size from the first bytes of a WebP file (the three WebP variants store it differently). */
    private Size readWebpSize(Path file) {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(32);
        } catch (IOException e) {
            throw invalid("The image could not be read");
        }
        if (head.length < 30) {
            throw invalid("The image could not be read");
        }
        String chunk = new String(head, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        switch (chunk) {
            case "VP8X":      // extended: canvas size minus one, 24 bits each
                return new Size(1 + le24(head, 24), 1 + le24(head, 27));
            case "VP8 ":      // lossy: 14 bits each after the start code 9D 01 2A
                if ((head[23] & 0xFF) != 0x9D || (head[24] & 0xFF) != 0x01 || (head[25] & 0xFF) != 0x2A) {
                    throw invalid("The image could not be read");
                }
                return new Size(le16(head, 26) & 0x3FFF, le16(head, 28) & 0x3FFF);
            case "VP8L":      // lossless: signature 0x2F, then 14 bits width-1 and 14 bits height-1
                if ((head[20] & 0xFF) != 0x2F) {
                    throw invalid("The image could not be read");
                }
                long bits = (head[21] & 0xFFL) | ((head[22] & 0xFFL) << 8) | ((head[23] & 0xFFL) << 16)
                        | ((head[24] & 0xFFL) << 24);
                return new Size((int) (bits & 0x3FFF) + 1, (int) ((bits >> 14) & 0x3FFF) + 1);
            default:
                throw invalid("The image could not be read");
        }
    }

    private static int le16(byte[] b, int offset) {
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8);
    }

    private static int le24(byte[] b, int offset) {
        return le16(b, offset) | ((b[offset + 2] & 0xFF) << 16);
    }

    private BadRequestException invalid(String message) {
        return new BadRequestException(message, "INVALID_IMAGE");
    }
}
