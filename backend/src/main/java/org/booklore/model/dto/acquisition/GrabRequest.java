package org.booklore.model.dto.acquisition;

/**
 * Request to grab a release the user selected from search results.
 * {@code release} is the exact {@link ProwlarrReleaseDto} returned by the search
 * endpoint; {@code query} is the original search query (persisted on the job for
 * traceability); {@code bookId}/{@code libraryId} optionally associate the grab
 * with a book being searched from.
 */
public record GrabRequest(
        ProwlarrReleaseDto release,
        String query,
        Long bookId,
        Long libraryId
) {
}
