package org.booklore.model.dto.acquisition;

import org.booklore.model.enums.AcquisitionCategory;

/**
 * Request to add a free-form entry to the wanted list. Not tied to an existing
 * {@code BookEntity} — Grimmory has no placeholder-book concept, so title/author/isbn
 * identify the desired book and {@code libraryId} is where a matching grab gets imported.
 */
public record CreateWantedBookRequest(
        String title,
        String author,
        String isbn,
        AcquisitionCategory category,
        Long libraryId
) {
}
