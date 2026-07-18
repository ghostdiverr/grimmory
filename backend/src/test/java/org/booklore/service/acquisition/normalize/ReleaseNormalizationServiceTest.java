package org.booklore.service.acquisition.normalize;

import org.booklore.model.enums.AcquisitionCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseNormalizationServiceTest {

    @TempDir
    Path tempDir;

    private ReleaseNormalizationService service;

    @BeforeEach
    void setUp() {
        service = new ReleaseNormalizationService();
    }

    @Test
    void singleEpubFile_isReady() throws IOException {
        Path epub = tempDir.resolve("book.epub");
        Files.writeString(epub, "epub-content");

        NormalizationResult result = service.normalize(epub, AcquisitionCategory.BOOK);

        NormalizationResult.Ready ready = assertInstanceOf(NormalizationResult.Ready.class, result);
        assertEquals(1, ready.bookdropReadyFiles().size());
        assertEquals(epub, ready.bookdropReadyFiles().get(0));
    }

    @Test
    void singleCbzFile_isReady() throws IOException {
        Path cbz = tempDir.resolve("comic.cbz");
        Files.writeString(cbz, "cbz-content");

        NormalizationResult result = service.normalize(cbz, AcquisitionCategory.BOOK);

        assertInstanceOf(NormalizationResult.Ready.class, result);
    }

    @Test
    void zipWithExactlyOneEpub_extractsAndIsReady() throws IOException {
        Path zip = tempDir.resolve("release.zip");
        String content = "hello epub bytes";
        writeZip(zip, entry("book.epub", content));

        NormalizationResult result = service.normalize(zip, AcquisitionCategory.BOOK);

        NormalizationResult.Ready ready = assertInstanceOf(NormalizationResult.Ready.class, result);
        assertEquals(1, ready.bookdropReadyFiles().size());
        Path extracted = ready.bookdropReadyFiles().get(0);
        assertTrue(Files.exists(extracted), "extracted file should exist at the returned path");
        assertEquals("book.epub", extracted.getFileName().toString());
        assertEquals(content, Files.readString(extracted));
    }

    @Test
    void zipWithEpubAndTxt_onlyEpubCounts_isReady() throws IOException {
        Path zip = tempDir.resolve("release.zip");
        writeZip(zip, entry("book.epub", "epub-content"), entry("readme.txt", "not a book"));

        NormalizationResult result = service.normalize(zip, AcquisitionCategory.BOOK);

        NormalizationResult.Ready ready = assertInstanceOf(NormalizationResult.Ready.class, result);
        assertEquals("book.epub", ready.bookdropReadyFiles().get(0).getFileName().toString());
    }

    @Test
    void zipWithTwoEpubs_isRejectedWithCount() throws IOException {
        Path zip = tempDir.resolve("release.zip");
        writeZip(zip, entry("book1.epub", "a"), entry("book2.epub", "b"));

        NormalizationResult result = service.normalize(zip, AcquisitionCategory.BOOK);

        NormalizationResult.Rejected rejected = assertInstanceOf(NormalizationResult.Rejected.class, result);
        assertTrue(rejected.reason().contains("2"), "reason should mention the count found: " + rejected.reason());
    }

    @Test
    void zipWithZeroRecognizedFiles_isRejected() throws IOException {
        Path zip = tempDir.resolve("release.zip");
        writeZip(zip, entry("readme.txt", "no books here"), entry("cover.jpg", "img"));

        NormalizationResult result = service.normalize(zip, AcquisitionCategory.BOOK);

        NormalizationResult.Rejected rejected = assertInstanceOf(NormalizationResult.Rejected.class, result);
        assertTrue(rejected.reason().contains("0"), "reason should mention zero found: " + rejected.reason());
    }

    @Test
    void corruptZip_isRejectedNotThrown() throws IOException {
        Path zip = tempDir.resolve("corrupt.zip");
        try (OutputStream out = Files.newOutputStream(zip, StandardOpenOption.CREATE)) {
            out.write("this is definitely not a zip file".getBytes());
        }

        NormalizationResult result = service.normalize(zip, AcquisitionCategory.BOOK);

        assertInstanceOf(NormalizationResult.Rejected.class, result);
    }

    @Test
    void audiobookFolder_singleM4bPlusExtras_isReady() throws IOException {
        Path folder = Files.createDirectory(tempDir.resolve("audiobook-release"));
        Files.writeString(folder.resolve("book.m4b"), "m4b-bytes");
        Files.writeString(folder.resolve("cover.jpg"), "img-bytes");
        Files.writeString(folder.resolve("reader.nfo"), "info");

        NormalizationResult result = service.normalize(folder, AcquisitionCategory.AUDIOBOOK);

        NormalizationResult.Ready ready = assertInstanceOf(NormalizationResult.Ready.class, result);
        assertEquals(1, ready.bookdropReadyFiles().size());
        assertEquals("book.m4b", ready.bookdropReadyFiles().get(0).getFileName().toString());
    }

    @Test
    void audiobookFolder_onlyMp3Chapters_isRejectedWithDocumentedMessage() throws IOException {
        Path folder = Files.createDirectory(tempDir.resolve("chapters-release"));
        Files.writeString(folder.resolve("chapter1.mp3"), "a");
        Files.writeString(folder.resolve("chapter2.mp3"), "b");

        NormalizationResult result = service.normalize(folder, AcquisitionCategory.AUDIOBOOK);

        NormalizationResult.Rejected rejected = assertInstanceOf(NormalizationResult.Rejected.class, result);
        assertTrue(rejected.reason().contains("Multi-file audiobook releases are not supported yet"),
                "reason should be the documented message: " + rejected.reason());
    }

    @Test
    void rarFile_isRejectedAsUnsupportedArchiveType() throws IOException {
        Path rar = tempDir.resolve("release.rar");
        try (OutputStream out = Files.newOutputStream(rar, StandardOpenOption.CREATE)) {
            // Rar4 magic bytes; content beyond this doesn't matter for the "unsupported type" branch.
            out.write(new byte[]{0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00});
        }

        NormalizationResult result = service.normalize(rar, AcquisitionCategory.BOOK);

        NormalizationResult.Rejected rejected = assertInstanceOf(NormalizationResult.Rejected.class, result);
        assertTrue(rejected.reason().toLowerCase().contains("not supported"),
                "reason should indicate unsupported archive type: " + rejected.reason());
    }

    private record ZipEntrySpec(String name, String content) {
    }

    private static ZipEntrySpec entry(String name, String content) {
        return new ZipEntrySpec(name, content);
    }

    private static void writeZip(Path zipPath, ZipEntrySpec... entries) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (ZipEntrySpec spec : entries) {
                zos.putNextEntry(new ZipEntry(spec.name()));
                zos.write(spec.content().getBytes());
                zos.closeEntry();
            }
        }
    }
}
