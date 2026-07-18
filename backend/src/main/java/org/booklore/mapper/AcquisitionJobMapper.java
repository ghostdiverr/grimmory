package org.booklore.mapper;

import org.booklore.model.dto.acquisition.AcquisitionJobDto;
import org.booklore.model.entity.AcquisitionJobEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AcquisitionJobMapper {

    AcquisitionJobDto toDto(AcquisitionJobEntity entity);
}
