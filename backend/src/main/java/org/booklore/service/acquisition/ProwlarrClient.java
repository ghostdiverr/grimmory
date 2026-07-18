package org.booklore.service.acquisition;

import org.booklore.model.dto.acquisition.ProwlarrReleaseDto;
import org.booklore.model.enums.AcquisitionCategory;

import java.util.List;

public interface ProwlarrClient {
    List<ProwlarrReleaseDto> search(String query, AcquisitionCategory category);

    void grab(ProwlarrReleaseDto release);

    ProwlarrConnectionTestResult testConnection();
}
