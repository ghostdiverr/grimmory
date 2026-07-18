package org.booklore.model.dto.acquisition;

import lombok.Data;
import org.booklore.model.entity.AcquisitionJobEntity.Status;
import org.booklore.model.enums.AcquisitionCategory;

import java.time.Instant;

@Data
public class AcquisitionJobDto {
    private Long id;
    private Long bookId;
    private Long libraryId;
    private String query;
    private AcquisitionCategory category;
    private String releaseTitle;
    private String releaseGuid;
    private Long indexerId;
    private String indexerName;
    private String protocol;
    private Long sizeBytes;
    private Status status;
    private String errorMessage;
    private Long requestedByUserId;
    private Instant grabbedAt;
    private Instant completedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
