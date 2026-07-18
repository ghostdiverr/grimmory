package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.booklore.model.dto.acquisition.AcquisitionJobDto;
import org.booklore.model.dto.acquisition.GrabRequest;
import org.booklore.model.dto.acquisition.ProwlarrReleaseDto;
import org.booklore.model.enums.AcquisitionCategory;
import org.booklore.model.enums.AuditAction;
import org.booklore.service.acquisition.AcquisitionService;
import org.booklore.service.acquisition.ProwlarrClient;
import org.booklore.service.acquisition.ProwlarrConnectionTestResult;
import org.booklore.service.audit.AuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Acquisition", description = "Endpoints for searching and grabbing releases via Prowlarr")
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/acquisition")
public class AcquisitionController {

    private final ProwlarrClient prowlarrClient;
    private final AcquisitionService acquisitionService;
    private final AuditService auditService;

    @Operation(summary = "Search Prowlarr indexers", description = "Search configured Prowlarr indexers for releases matching the query.")
    @ApiResponse(responseCode = "200", description = "Search results returned successfully")
    @GetMapping("/search")
    public List<ProwlarrReleaseDto> search(@Parameter(description = "Search query") @RequestParam String query,
                                            @Parameter(description = "Category to search") @RequestParam AcquisitionCategory category) {
        return prowlarrClient.search(query, category);
    }

    @Operation(summary = "Test Prowlarr connection", description = "Verify connectivity to the configured Prowlarr instance.")
    @ApiResponse(responseCode = "200", description = "Connection test result returned")
    @PreAuthorize("@securityUtil.isAdmin() or @securityUtil.canManageMetadataConfig()")
    @PostMapping("/test-connection")
    public ProwlarrConnectionTestResult testConnection() {
        ProwlarrConnectionTestResult result = prowlarrClient.testConnection();
        auditService.log(AuditAction.PROWLARR_CONNECTION_TEST, "Prowlarr connection test: " + (result.success() ? "passed" : "failed"));
        return result;
    }

    @Operation(summary = "List acquisition jobs", description = "List all tracked acquisition jobs (grabbed releases and their status).")
    @ApiResponse(responseCode = "200", description = "Acquisition jobs returned successfully")
    @GetMapping("/jobs")
    public List<AcquisitionJobDto> listJobs() {
        return acquisitionService.listJobs();
    }

    @Operation(summary = "Grab a release", description = "Tell Prowlarr to grab the selected release and start tracking it as an acquisition job.")
    @ApiResponse(responseCode = "200", description = "Acquisition job created successfully")
    @PostMapping("/grab")
    public AcquisitionJobDto grab(@RequestBody GrabRequest request) {
        return acquisitionService.grab(request);
    }

    @Operation(summary = "Cancel an acquisition job", description = "Best-effort cancellation: marks the job as failed locally. Prowlarr has no cancel-grab API, so the underlying download (if any) is not stopped.")
    @ApiResponse(responseCode = "200", description = "Acquisition job cancelled successfully")
    @DeleteMapping("/jobs/{id}")
    public AcquisitionJobDto cancelJob(@Parameter(description = "Acquisition job ID") @PathVariable Long id) {
        return acquisitionService.cancelJob(id);
    }
}
