package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.model.enums.AcquisitionCategory;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "acquisition_job")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AcquisitionJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "book_id")
    private Long bookId;

    @Column(name = "library_id")
    private Long libraryId;

    @Column(name = "query", columnDefinition = "TEXT", nullable = false)
    private String query;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20, nullable = false)
    private AcquisitionCategory category;

    @Column(name = "release_title", length = 1024, nullable = false)
    private String releaseTitle;

    @Column(name = "release_guid", length = 512, nullable = false)
    private String releaseGuid;

    @Column(name = "indexer_id", nullable = false)
    private Long indexerId;

    @Column(name = "indexer_name", length = 255)
    private String indexerName;

    @Column(name = "protocol", length = 20)
    private String protocol;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private Status status = Status.GRABBED;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "requested_by_user_id", nullable = false)
    private Long requestedByUserId;

    @Column(name = "grabbed_at", nullable = false)
    private Instant grabbedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public enum Status {
        GRABBED,
        WAITING_FOR_FILE,
        NORMALIZING,
        IMPORTING,
        COMPLETED,
        FAILED,
        TIMED_OUT
    }
}
