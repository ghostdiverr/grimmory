package org.booklore.model.dto.acquisition;

import org.booklore.model.enums.AcquisitionCategory;

public record ProwlarrReleaseDto(
        String guid,
        Long indexerId,
        String indexerName,
        String title,
        Long size,
        Integer seeders,
        Integer leechers,
        String publishDate,
        String downloadUrl,
        String protocol,
        AcquisitionCategory category
) {
}
