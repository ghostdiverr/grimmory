package org.booklore.model.dto.acquisition;

import lombok.Data;
import org.booklore.model.entity.WantedBookEntity.Status;
import org.booklore.model.enums.AcquisitionCategory;

import java.time.Instant;

@Data
public class WantedBookDto {
    private Long id;
    private String title;
    private String author;
    private String isbn;
    private AcquisitionCategory category;
    private Long libraryId;
    private Status status;
    private Instant lastSearchedAt;
    private Integer lastResultCount;
    private Long acquisitionJobId;
    private Long requestedByUserId;
    private Instant createdAt;
    private Instant updatedAt;
}
