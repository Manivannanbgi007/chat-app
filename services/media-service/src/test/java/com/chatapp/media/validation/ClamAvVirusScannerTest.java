package com.chatapp.media.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import com.chatapp.media.exception.MalwareDetectedException;
import com.chatapp.media.exception.StorageUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ClamAV client speaks the real clamd INSTREAM protocol. Here a small fake "clamd" (a plain TCP server) checks that
 * the bytes on the wire are exactly what clamd expects and answers like clamd does. The same scanner is tried against a
 * real ClamAV with the EICAR test file in the Phase 9 live check (see IMPLEMENTATION_CHECKLIST.md).
 */
class ClamAvVirusScannerTest {

    private ServerSocket server;
    private final AtomicReference<byte[]> received = new AtomicReference<>();
    private final AtomicReference<String> command = new AtomicReference<>();

    @AfterEach
    void stop() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    /** Starts the fake clamd; it reads the INSTREAM request and answers with the given text. */
    private ClamAvVirusScanner fakeClamd(String answer) throws IOException {
        server = new ServerSocket(0);
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                InputStream in = socket.getInputStream();
                byte[] header = in.readNBytes("zINSTREAM\0".length());
                command.set(new String(header, StandardCharsets.US_ASCII));
                DataInputStream data = new DataInputStream(in);
                ByteArrayOutputStream file = new ByteArrayOutputStream();
                int length;
                while ((length = data.readInt()) > 0) {
                    file.write(data.readNBytes(length));
                }
                received.set(file.toByteArray());
                socket.getOutputStream().write((answer + "\0").getBytes(StandardCharsets.US_ASCII));
            } catch (IOException ignored) {
                // the test checks the outcome
            }
        });
        thread.setDaemon(true);
        thread.start();
        return new ClamAvVirusScanner("127.0.0.1", server.getLocalPort(), 2000, 5000);
    }

    private Path file(Path dir, byte[] content) throws IOException {
        return Files.write(dir.resolve("upload.bin"), content);
    }

    @Test
    void cleanFileIsAcceptedAndSentExactlyAsClamdExpectsIt(@TempDir Path dir) throws Exception {
        byte[] content = new byte[200_000];                      // bigger than one 64 KB piece
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        ClamAvVirusScanner scanner = fakeClamd("stream: OK");

        assertThatCode(() -> scanner.scan(file(dir, content))).doesNotThrowAnyException();

        assertThat(command.get()).isEqualTo("zINSTREAM\0");
        assertThat(received.get()).isEqualTo(content);          // every byte arrived, in order
    }

    @Test
    void infectedFileIsRejected(@TempDir Path dir) throws Exception {
        ClamAvVirusScanner scanner = fakeClamd("stream: Eicar-Signature FOUND");

        assertThatThrownBy(() -> scanner.scan(file(dir, "X5O!P%@AP".getBytes(StandardCharsets.US_ASCII))))
                .isInstanceOf(MalwareDetectedException.class)
                .hasMessageNotContaining("Eicar");              // the user never sees the signature name
    }

    @Test
    void emptyFileIsFine(@TempDir Path dir) throws Exception {
        ClamAvVirusScanner scanner = fakeClamd("stream: OK");

        assertThatCode(() -> scanner.scan(file(dir, new byte[0]))).doesNotThrowAnyException();
    }

    @Test
    void clamdErrorMeansTheUploadIsRefusedNotAccepted(@TempDir Path dir) throws Exception {
        ClamAvVirusScanner scanner = fakeClamd("INSTREAM size limit exceeded. ERROR");

        assertThatThrownBy(() -> scanner.scan(file(dir, new byte[]{1, 2, 3})))
                .isInstanceOf(StorageUnavailableException.class);
    }

    @Test
    void scannerThatCannotBeReachedMeansTheUploadIsRefused(@TempDir Path dir) throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        ClamAvVirusScanner scanner = new ClamAvVirusScanner("127.0.0.1", freePort, 500, 500);

        assertThatThrownBy(() -> scanner.scan(file(dir, new byte[]{1})))
                .isInstanceOf(StorageUnavailableException.class);
    }
}
