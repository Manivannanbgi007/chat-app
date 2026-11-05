package com.chatapp.media.validation;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.chatapp.media.exception.MalwareDetectedException;
import com.chatapp.media.exception.StorageUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real malware scan with ClamAV (Phase 9). It sends the file to the "clamd" daemon over TCP with the INSTREAM command:
 *
 *   client -> "zINSTREAM\0"
 *   client -> [4 bytes length][bytes] ... [4 bytes length][bytes]  (the file in pieces)
 *   client -> [0 0 0 0]                                            (end of file)
 *   clamd  -> "stream: OK\0"   or   "stream: Eicar-Signature FOUND\0"   or   "... ERROR\0"
 *
 * Turn it on with  chatapp.media.virus-scanner=clamav  (environment: VIRUS_SCANNER=clamav). Without it the
 * NoOpVirusScanner is used, which scans nothing.
 *
 * If ClamAV cannot be reached or answers with an error, the upload is REFUSED with 503 (fail closed): an unscanned file
 * must never be stored just because the scanner is down. Users can simply try again a moment later.
 */
@Component
@ConditionalOnProperty(name = "chatapp.media.virus-scanner", havingValue = "clamav")
public class ClamAvVirusScanner implements VirusScanner {

    private static final Logger log = LoggerFactory.getLogger(ClamAvVirusScanner.class);
    private static final int CHUNK_SIZE = 64 * 1024;

    private final String host;
    private final int port;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public ClamAvVirusScanner(@Value("${chatapp.clamav.host:clamav}") String host,
                              @Value("${chatapp.clamav.port:3310}") int port,
                              @Value("${chatapp.clamav.connect-timeout-ms:3000}") int connectTimeoutMillis,
                              @Value("${chatapp.clamav.read-timeout-ms:120000}") int readTimeoutMillis) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        log.info("ClamAV virus scanner is active ({}:{})", host, port);
    }

    @Override
    public void scan(Path file) {
        String answer;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);
            answer = send(socket, file);
        } catch (IOException e) {
            log.error("ClamAV scan failed: {} ({})", e.getClass().getSimpleName(), e.getMessage());
            throw new StorageUnavailableException("The security scan is not available right now. Please try again.");
        }

        // answer looks like "stream: OK", "stream: Eicar-Signature FOUND" or "INSTREAM size limit exceeded. ERROR"
        if (answer.endsWith("FOUND")) {
            log.warn("ClamAV rejected an upload: {}", answer);   // the signature name is for the operator, never for the user
            throw new MalwareDetectedException();
        }
        if (!answer.endsWith("OK")) {
            log.error("ClamAV gave an unexpected answer: {}", answer);
            throw new StorageUnavailableException("The security scan is not available right now. Please try again.");
        }
    }

    private String send(Socket socket, Path file) throws IOException {
        OutputStream out = socket.getOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
        byte[] buffer = new byte[CHUNK_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                data.writeInt(read);          // 4 bytes, big endian: exactly what clamd expects
                data.write(buffer, 0, read);
            }
        }
        data.writeInt(0);                      // end of the stream
        data.flush();

        // clamd answers with one text ending in a zero byte
        StringBuilder answer = new StringBuilder();
        InputStream in = socket.getInputStream();
        int b;
        while ((b = in.read()) > 0) {
            answer.append((char) b);
        }
        return answer.toString().trim();
    }
}
