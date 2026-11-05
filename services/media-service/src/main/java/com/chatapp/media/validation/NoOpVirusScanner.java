package com.chatapp.media.validation;

import java.nio.file.Path;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Accepts every file. Used when chatapp.media.virus-scanner is not set (local development and tests).
 * Production must set VIRUS_SCANNER=clamav, see ClamAvVirusScanner and DEPLOYMENT_GUIDE.md.
 */
@Component
@ConditionalOnProperty(name = "chatapp.media.virus-scanner", havingValue = "none", matchIfMissing = true)
public class NoOpVirusScanner implements VirusScanner {

    private static final Logger log = LoggerFactory.getLogger(NoOpVirusScanner.class);

    @PostConstruct
    void warn() {
        log.warn("NoOpVirusScanner is active: uploaded files are NOT scanned for malware. "
                + "Set VIRUS_SCANNER=clamav before production.");
    }

    @Override
    public void scan(Path file) {
        // intentionally empty
    }
}
