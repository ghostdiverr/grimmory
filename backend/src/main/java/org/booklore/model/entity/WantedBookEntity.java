package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.model.enums.AcquisitionCategory;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "wanted_book")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WantedBookEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", length = 512, nullable = false)
    private String title;

    @Column(name = "author", length = 255)
    private String author;

    @Column(name = "isbn", length = 32)
    private String isbn;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20, nullable = false)
    private AcquisitionCategory category;

    @Column(name = "library_id", nullable = false)
    private Long libraryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(name = "excluded_guids", columnDefinition = "TEXT")
    private String excludedGuids;

    @Column(name = "last_searched_at")
    private Instant lastSearchedAt;

    @Column(name = "last_result_count")
    private Integer lastResultCount;

    @Column(name = "acquisition_job_id")
    private Long acquisitionJobId;

    @Column(name = "requested_by_user_id", nullable = false)
    private Long requestedByUserId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public enum Status {
        ACTIVE,
        GRABBED,
        FULFILLED,
        PAUSED
    }
}
