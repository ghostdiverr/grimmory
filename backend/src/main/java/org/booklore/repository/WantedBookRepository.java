package org.booklore.repository;

import org.booklore.model.entity.WantedBookEntity;
import org.booklore.model.entity.WantedBookEntity.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WantedBookRepository extends JpaRepository<WantedBookEntity, Long> {

    List<WantedBookEntity> findAllByStatus(Status status);

    List<WantedBookEntity> findAllByOrderByCreatedAtDesc();
}
