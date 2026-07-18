package org.booklore.repository;

import org.booklore.model.entity.AcquisitionJobEntity;
import org.booklore.model.entity.AcquisitionJobEntity.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AcquisitionJobRepository extends JpaRepository<AcquisitionJobEntity, Long> {

    List<AcquisitionJobEntity> findAllByStatusIn(List<Status> statuses);

    Page<AcquisitionJobEntity> findAllByRequestedByUserId(Long userId, Pageable pageable);

    List<AcquisitionJobEntity> findAllByOrderByCreatedAtDesc();
}
