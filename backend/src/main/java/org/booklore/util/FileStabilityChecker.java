package org.booklore.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Waits for a file to stop growing (e.g. while a download client is still writing to it)
 * before it is handed off for further processing. Polls the file size at a fixed interval
 * and requires a number of consecutive stable reads before declaring the file ready.
 */
@UtilityClass
@Slf4j
public class FileStabilityChecker {

    public boolean waitForStability(Path file, long checkIntervalMs, int requiredChecks, long maxWaitMs) {
        long startTime = System.currentTimeMillis();
        long lastSize = -1;
        int stableCount = 0;

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            try {
                if (!Files.exists(file)) {
                    return false;
                }

                long currentSize = Files.size(file);

                if (currentSize == lastSize && currentSize > 0) {
                    stableCount++;
                    if (stableCount >= requiredChecks) {
                        return true;
                    }
                } else {
                    stableCount = 0;
                }

                lastSize = currentSize;
                Thread.sleep(checkIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException e) {
                log.warn("Error checking file size for stability: {}", file, e);
                return false;
            }
        }

        log.warn("File size did not stabilize after {}ms: {}", maxWaitMs, file);
        return false;
    }
}
