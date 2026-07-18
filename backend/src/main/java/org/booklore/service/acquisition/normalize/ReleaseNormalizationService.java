package org.booklore.service.acquisition.normalize;

import org.booklore.model.enums.AcquisitionCategory;
import org.booklore.model.enums.BookFileExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Normalizes a completed Prowlarr download (a single recognized file, a zip archive, or an
 * audiobook folder) into file(s) ready to be handed off to the existing BookDrop ingestion
 * pipeline unchanged.
 *
 * <p><b>Extraction-target design note:</b> when a zip archive must be unwrapped, the single
 * recognized entry is extracted into a fresh temporary directory created by this service
 * (via {@link Files#createTempDirectory}) rather than into a caller-supplied destination.
 * This keeps the service free of any dependency on {@code AppProperties}/the bookdrop
 * folder location, and easy to unit test purely against {@code @TempDir} fixtures. The
 * caller (the completed-download watcher, WP4) is responsible for moving/copying the
 * returned {@link NormalizationResult.Ready#bookdropReadyFiles()} into the real bookdrop
 * folder and cleaning up the temp directory afterwards.
 *
 * <p>Not implemented in v1 (phase-2 follow-ups): rar/7z extraction, nested/recursive
 * archives, multi-disc CBZ/CBR bundling, and chapter-file (mp3/m4a) audiobook merging.
 */
@Slf4j
@Service
public class ReleaseNormalizationService {

    private static final List<String> AUDIOBOOK_IGNORED_EXTENSIONS = List.of(
            ".nfo", ".jpg", ".jpeg", ".png", ".cue", ".txt", ".md5", ".sfv", ".url", ".db");

    public NormalizationResult normalize(Path downloadedPathOrFolder, AcquisitionCategory category) {
        try {
            if (!Files.exists(downloadedPathOrFolder)) {
                return new NormalizationResult.Rejected("Path does not exist: " + downloadedPathOrFolder);
            }

            if (Files.isRegularFile(downloadedPathOrFolder)) {
                return normalizeFile(downloadedPathOrFolder);
            }

            if (Files.isDirectory(downloadedPathOrFolder)) {
                return normalizeFolder(downloadedPathOrFolder, category);
            }

            return new NormalizationResult.Rejected("Unsupported path type: " + downloadedPathOrFolder);
        } catch (IOException e) {
            log.warn("Failed to normalize release at {}", downloadedPathOrFolder, e);
            return new NormalizationResult.Rejected("Failed to read release: " + e.getMessage());
        }
    }

    private NormalizationResult normalizeFile(Path file) {
        String fileName = file.getFileName().toString();

        if (BookFileExtension.fromFileName(fileName).isPresent()) {
            return new NormalizationResult.Ready(List.of(file));
        }

        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".zip")) {
            return normalizeZip(file);
        }

        if (lowerName.endsWith(".rar") || lowerName.endsWith(".7z")) {
            return new NormalizationResult.Rejected(
                    "Archive type is not supported yet: " + fileName
                            + " (only single recognized files or .zip archives can be imported automatically)");
        }

        return new NormalizationResult.Rejected("File is not a recognized book/audiobook format: " + fileName);
    }

    private NormalizationResult normalizeZip(Path zipFile) {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            List<ZipEntry> candidates = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String entryFileName = Path.of(entry.getName()).getFileName().toString();
                if (BookFileExtension.fromFileName(entryFileName).isPresent()) {
                    candidates.add(entry);
                }
            }

            if (candidates.size() != 1) {
                return new NormalizationResult.Rejected(
                        "Archive contains " + candidates.size() + " candidate book files, expected exactly 1");
            }

            ZipEntry target = candidates.get(0);
            String extractedFileName = Path.of(target.getName()).getFileName().toString();
            Path extractionDir = Files.createTempDirectory("grimmory-acquisition-unzip-");
            Path extractedFile = extractionDir.resolve(extractedFileName);

            try (InputStream in = zip.getInputStream(target);
                 OutputStream out = Files.newOutputStream(extractedFile)) {
                in.transferTo(out);
            }

            return new NormalizationResult.Ready(List.of(extractedFile));
        } catch (IOException e) {
            log.warn("Failed to open/extract zip archive {}", zipFile, e);
            return new NormalizationResult.Rejected("Archive is corrupt or unreadable: " + e.getMessage());
        }
    }

    private NormalizationResult normalizeFolder(Path folder, AcquisitionCategory category) throws IOException {
        if (category != AcquisitionCategory.AUDIOBOOK) {
            return new NormalizationResult.Rejected("Folder releases are only supported for audiobooks in v1: " + folder);
        }

        List<Path> relevantFiles;
        try (var stream = Files.list(folder)) {
            relevantFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isIgnoredAudiobookExtra(p))
                    .toList();
        }

        List<Path> m4bFiles = relevantFiles.stream()
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".m4b"))
                .toList();

        if (m4bFiles.size() == 1) {
            return new NormalizationResult.Ready(List.of(m4bFiles.get(0)));
        }

        if (m4bFiles.size() > 1) {
            return new NormalizationResult.Rejected(
                    "Folder contains " + m4bFiles.size() + " .m4b files, expected exactly 1");
        }

        boolean hasChapterFiles = relevantFiles.stream().anyMatch(p -> {
            String lower = p.getFileName().toString().toLowerCase();
            return lower.endsWith(".mp3") || lower.endsWith(".m4a");
        });

        if (hasChapterFiles) {
            return new NormalizationResult.Rejected(
                    "Multi-file audiobook releases are not supported yet — only single .m4b files or folders "
                            + "containing exactly one .m4b are imported automatically; import chapter files manually via upload.");
        }

        return new NormalizationResult.Rejected("Folder contains no recognized audiobook files: " + folder);
    }

    private boolean isIgnoredAudiobookExtra(Path path) {
        String lower = path.getFileName().toString().toLowerCase();
        return AUDIOBOOK_IGNORED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
