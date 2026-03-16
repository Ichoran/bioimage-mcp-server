package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.ImageMetadata.DetailLevel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for {@link BioFormatsReader} with proprietary
 * microscopy file formats.
 *
 * <p>These tests download real microscopy files from the OME sample
 * data repository.  They are skipped (not failed) if the files cannot
 * be downloaded (no network, server unavailable, etc.).
 *
 * <p>Downloaded files are cached in {@code test/fixtures/} (gitignored)
 * and reused across runs.
 */
class BioFormatsProprietaryTest {

    private static Path cziFile;
    private BioFormatsReader reader;

    @BeforeAll
    static void downloadFixtures() {
        cziFile = TestFixtures.resolve(TestFixtures.CZI_IDR0011);
    }

    @BeforeEach
    void setUp() {
        reader = new BioFormatsReader();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (reader != null) reader.close();
    }

    // ---- CZI tests ----

    @Test
    void cziOpensSuccessfully() throws IOException {
        assumeTrue(cziFile != null, "CZI test fixture not available");
        reader.open(cziFile);
        assertTrue(reader.getSeriesCount() >= 1);
    }

    @Test
    void cziFormatNameMentionsZeiss() throws IOException {
        assumeTrue(cziFile != null, "CZI test fixture not available");
        reader.open(cziFile);
        var metadata = reader.getMetadata(0, DetailLevel.SUMMARY);
        var formatName = metadata.formatName().toLowerCase();
        assertTrue(formatName.contains("zeiss") || formatName.contains("czi"),
                "Expected format name to mention Zeiss or CZI, got: "
                        + metadata.formatName());
    }

    @Test
    void cziHasPositiveDimensions() throws IOException {
        assumeTrue(cziFile != null, "CZI test fixture not available");
        reader.open(cziFile);
        var series = reader.getMetadata(0, DetailLevel.SUMMARY).detailedSeries();
        assertTrue(series.sizeX() > 0);
        assertTrue(series.sizeY() > 0);
        assertTrue(series.sizeZ() >= 1);
        assertTrue(series.sizeC() >= 1);
        assertTrue(series.sizeT() >= 1);
        assertNotNull(series.pixelType());
    }

    @Test
    void cziPixelDataReadable() throws IOException {
        assumeTrue(cziFile != null, "CZI test fixture not available");
        reader.open(cziFile);
        var series = reader.getMetadata(0, DetailLevel.SUMMARY).detailedSeries();

        byte[] plane = reader.readPlane(0, 0, 0, 0);
        assertNotNull(plane);

        long expectedBytes = (long) series.sizeX() * series.sizeY()
                * series.pixelType().bytesPerPixel();
        assertEquals(expectedBytes, plane.length,
                "Plane byte count should match sizeX * sizeY * bytesPerPixel");
    }

    @Test
    void cziStandardMetadata() throws IOException {
        assumeTrue(cziFile != null, "CZI test fixture not available");
        reader.open(cziFile);
        var metadata = reader.getMetadata(0, DetailLevel.STANDARD);
        var series = metadata.detailedSeries();

        // Should have channel info
        assertFalse(series.channels().isEmpty(),
                "Expected at least one channel");

        // Dimension order should be a valid 5-char string
        assertNotNull(series.dimensionOrder());
        assertEquals(5, series.dimensionOrder().length());
    }

    @Test
    void cziFullMetadataHasExtraEntries() throws IOException {
        assumeTrue(cziFile != null, "CZI test fixture not available");
        reader.open(cziFile);

        var standard = reader.getMetadata(0, DetailLevel.STANDARD);
        var full = reader.getMetadata(0, DetailLevel.FULL);

        // Full should have at least as much metadata as standard
        assertTrue(full.detailedSeries().extraMetadata().size()
                        >= standard.detailedSeries().extraMetadata().size(),
                "Full detail should have >= extra metadata entries vs standard");

        // Standard should report omitted bytes
        assertTrue(standard.omittedMetadataBytes() >= 0);
    }

    @Test
    void cziOmeXmlAvailable() throws IOException {
        assumeTrue(cziFile != null, "CZI test fixture not available");
        reader.open(cziFile);
        String xml = reader.getOMEXML();
        assertNotNull(xml, "OME-XML should be available for CZI files");
        assertTrue(xml.contains("<OME") || xml.contains("<ome:OME"),
                "OME-XML should contain OME root element");
    }

    @Test
    void cziOriginalMetadataPresent() throws IOException {
        assumeTrue(cziFile != null, "CZI test fixture not available");
        reader.open(cziFile);
        // CZI files typically have extensive proprietary metadata
        assertTrue(reader.getOriginalMetadataCount() > 0,
                "CZI files should have original metadata entries");
    }

    @Test
    void cziAllSeriesSummaries() throws IOException {
        assumeTrue(cziFile != null, "CZI test fixture not available");
        reader.open(cziFile);

        int seriesCount = reader.getSeriesCount();
        var metadata = reader.getMetadata(0, DetailLevel.SUMMARY);
        assertEquals(seriesCount, metadata.allSeries().size(),
                "allSeries should list every series in the file");

        // Each summary should have positive dimensions
        for (var summary : metadata.allSeries()) {
            assertTrue(summary.sizeX() > 0);
            assertTrue(summary.sizeY() > 0);
        }
    }

    @Test
    void cziByteOrderConsistent() throws IOException {
        assumeTrue(cziFile != null, "CZI test fixture not available");
        reader.open(cziFile);
        // Just verify it doesn't throw — byte order is format-dependent
        boolean le = reader.isLittleEndian(0);
        // CZI is typically little-endian (x86 origin)
        assertTrue(le, "CZI files are typically little-endian");
    }
}
