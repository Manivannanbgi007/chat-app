package com.chatapp.media.validation;

import java.nio.file.Path;

/**
 * Looks at an uploaded file BEFORE it is stored. An implementation throws MalwareDetectedException when
 * the file is dangerous. The default implementation scans nothing and must be replaced before production
 * (for example with a ClamAV client): see README.
 */
public interface VirusScanner {

    void scan(Path file);
}
