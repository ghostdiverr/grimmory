package org.booklore.mapper;

import org.booklore.model.dto.acquisition.WantedBookDto;
import org.booklore.model.entity.WantedBookEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface WantedBookMapper {

    WantedBookDto toDto(WantedBookEntity entity);
}
