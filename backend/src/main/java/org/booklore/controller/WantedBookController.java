package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.booklore.model.dto.acquisition.CreateWantedBookRequest;
import org.booklore.model.dto.acquisition.WantedBookDto;
import org.booklore.service.acquisition.WantedBookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Wanted List", description = "Endpoints for managing the wanted list — books to auto-acquire via Prowlarr when a matching release appears")
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/wanted")
public class WantedBookController {

    private final WantedBookService wantedBookService;

    @Operation(summary = "List wanted list entries", description = "List all wanted list entries for the current user.")
    @ApiResponse(responseCode = "200", description = "Wanted list entries returned successfully")
    @GetMapping
    public List<WantedBookDto> list() {
        return wantedBookService.list();
    }

    @Operation(summary = "Add a wanted list entry", description = "Add a free-form book to the wanted list to be automatically searched for and grabbed.")
    @ApiResponse(responseCode = "200", description = "Wanted list entry created successfully")
    @PostMapping
    public WantedBookDto create(@RequestBody CreateWantedBookRequest request) {
        return wantedBookService.create(request);
    }

    @Operation(summary = "Pause a wanted list entry", description = "Pause a wanted list entry so it is skipped by the periodic scan.")
    @ApiResponse(responseCode = "200", description = "Wanted list entry paused successfully")
    @PatchMapping("/{id}/pause")
    public WantedBookDto pause(@Parameter(description = "Wanted list entry ID") @PathVariable Long id) {
        return wantedBookService.pause(id);
    }

    @Operation(summary = "Resume a wanted list entry", description = "Resume a paused wanted list entry so it is picked up by the next scan.")
    @ApiResponse(responseCode = "200", description = "Wanted list entry resumed successfully")
    @PatchMapping("/{id}/resume")
    public WantedBookDto resume(@Parameter(description = "Wanted list entry ID") @PathVariable Long id) {
        return wantedBookService.resume(id);
    }

    @Operation(summary = "Delete a wanted list entry", description = "Remove a wanted list entry.")
    @ApiResponse(responseCode = "204", description = "Wanted list entry deleted successfully")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description = "Wanted list entry ID") @PathVariable Long id) {
        wantedBookService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
