package org.booklore.service.acquisition.normalize;

import java.nio.file.Path;
import java.util.List;

/**
 * Outcome of {@link ReleaseNormalizationService#normalize(Path, org.booklore.model.enums.AcquisitionCategory)}.
 *
 * <p>{@link Ready} carries the file(s) that are ready to be handed off to the BookDrop
 * ingestion pipeline. {@link Rejected} carries a user-facing reason the release could not
 * be normalized, meant to be stored verbatim as an acquisition job's error message.
 */
public sealed interface NormalizationResult permits NormalizationResult.Ready, NormalizationResult.Rejected {

    record Ready(List<Path> bookdropReadyFiles) implements NormalizationResult {
    }

    record Rejected(String reason) implements NormalizationResult {
    }
}
