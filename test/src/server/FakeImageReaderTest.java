package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.FakeImageReader.FakeSeries;
import lab.kerrr.mcpbio.bioimageserver.ImageMetadata.DetailLevel;
import lab.kerrr.mcpbio.bioimageserver.PixelSize.LengthUnit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class FakeImageReaderTest {

    private static final Path DUMMY = Path.of("/fake/image.tif");

    // ---- Lifecycle ----

    @Test
    void openAndClose() throws IOException {
        var reader = oneSeriesReader();
        assertFalse(reader.isOpen());

        reader.open(DUMMY);
        assertTrue(reader.isOpen());
        assertEquals(DUMMY, reader.openedPath());

        reader.close();
        assertFalse(reader.isOpen());
    }

    @Test
    void closeIsIdempotent() throws IOException {
        var reader = oneSeriesReader();
        reader.close();  // never opened — should not throw
        reader.open(DUMMY);
        reader.close();
        reader.close();  // double close — should not throw
    }

    @Test
    void methodsThrowWhenNotOpen() {
        var reader = oneSeriesReader();
        assertThrows(IllegalStateException.class, reader::getSeriesCount);
        assertThrows(IllegalStateException.class,
                () -> reader.getMetadata(0, DetailLevel.SUMMARY));
        assertThrows(IllegalStateException.class,
                () -> reader.isLittleEndian(0));
        assertThrows(IllegalStateException.class,
                () -> reader.readPlane(0, 0, 0, 0));
    }

    // ---- Series count and bounds ----

    @Test
    void singleSeriesCount() throws IOException {
        try (var reader = oneSeriesReader()) {
            reader.open(DUMMY);
            assertEquals(1, reader.getSeriesCount());
        }
    }

    @Test
    void multipleSeriesCount() throws IOException {
        try (var reader = FakeImageReader.builder()
                .addSeries(FakeSeries.simple(64, 64, 1, 1, 1, PixelType.UINT8))
                .addSeries(FakeSeries.simple(128, 128, 10, 3, 1, PixelType.UINT16))
                .addSeries(FakeSeries.simple(256, 256, 1, 1, 5, PixelType.FLOAT))
                .build()) {
            reader.open(DUMMY);
            assertEquals(3, reader.getSeriesCount());
        }
    }

    @Test
    void seriesOutOfRangeThrows() throws IOException {
        try (var reader = oneSeriesReader()) {
            reader.open(DUMMY);
            assertThrows(IllegalArgumentException.class,
                    () -> reader.getMetadata(-1, DetailLevel.SUMMARY));
            assertThrows(IllegalArgumentException.class,
                    () -> reader.getMetadata(1, DetailLevel.SUMMARY));
        }
    }

    // ---- Metadata: detail levels ----

    @Test
    void summaryHasDimensionsAndPixelType() throws IOException {
        try (var reader = richReader()) {
            reader.open(DUMMY);
            var meta = reader.getMetadata(0, DetailLevel.SUMMARY);
            var si = meta.detailedSeries();

            assertEquals(512, si.sizeX());
            assertEquals(512, si.sizeY());
            assertEquals(10, si.sizeZ());
            assertEquals(3, si.sizeC());
            assertEquals(1, si.sizeT());
            assertEquals(PixelType.UINT16, si.pixelType());

            // Summary omits instrument and acquisition date
            assertNull(si.instrument());
            assertNull(si.acquisitionDate());
            // Summary omits extra metadata
            assertTrue(si.extraMetadata().isEmpty());
        }
    }

    @Test
    void summaryHasPhysicalSizes() throws IOException {
        try (var reader = richReader()) {
            reader.open(DUMMY);
            var si = reader.getMetadata(0, DetailLevel.SUMMARY).detailedSeries();
            assertNotNull(si.physicalSizeX());
            assertEquals(LengthUnit.MICROMETER, si.physicalSizeX().unit());
        }
    }

    @Test
    void standardIncludesInstrumentAndChannelWavelengths() throws IOException {
        try (var reader = richReader()) {
            reader.open(DUMMY);
            var si = reader.getMetadata(0, DetailLevel.STANDARD).detailedSeries();

            assertNotNull(si.instrument());
            assertEquals("Plan-Apo 63x", si.instrument().objectiveModel());
            assertEquals("2024-01-15T10:30:00Z", si.acquisitionDate());

            // Channels should have wavelengths at standard level
            assertEquals(3, si.channels().size());
            assertTrue(si.channels().get(0).excitationWavelength().isPresent());

            // But extra metadata is still empty
            assertTrue(si.extraMetadata().isEmpty());
        }
    }

    @Test
    void fullIncludesExtraMetadata() throws IOException {
        try (var reader = richReader()) {
            reader.open(DUMMY);
            var si = reader.getMetadata(0, DetailLevel.FULL).detailedSeries();

            assertNotNull(si.instrument());
            assertFalse(si.extraMetadata().isEmpty());
            assertEquals("100", si.extraMetadata().get("LaserPower"));
        }
    }

    @Test
    void omittedMetadataBytesDecreasesWithDetail() throws IOException {
        try (var reader = richReader()) {
            reader.open(DUMMY);
            long omittedSummary = reader.getMetadata(0, DetailLevel.SUMMARY).omittedMetadataBytes();
            long omittedStandard = reader.getMetadata(0, DetailLevel.STANDARD).omittedMetadataBytes();
            long omittedFull = reader.getMetadata(0, DetailLevel.FULL).omittedMetadataBytes();

            assertTrue(omittedSummary > omittedStandard,
                    "summary should omit more than standard");
            assertTrue(omittedStandard > omittedFull,
                    "standard should omit more than full");
            assertEquals(0, omittedFull);
        }
    }

    // ---- Metadata: allSeries listing ----

    @Test
    void allSeriesListsEverySeries() throws IOException {
        try (var reader = FakeImageReader.builder()
                .addSeries(new FakeSeries("Overview", 1024, 1024, 1, 1, 1,
                        PixelType.UINT8, "XYCZT", null, null, null,
                        List.of(), null, null, Map.of()))
                .addSeries(new FakeSeries("Detail", 512, 512, 20, 3, 1,
                        PixelType.UINT16, "XYCZT", null, null, null,
                        List.of(), null, null, Map.of()))
                .build()) {
            reader.open(DUMMY);
            var meta = reader.getMetadata(1, DetailLevel.SUMMARY);

            assertEquals(2, meta.allSeries().size());
            assertEquals("Overview", meta.allSeries().get(0).name());
            assertEquals(1024, meta.allSeries().get(0).sizeX());
            assertEquals("Detail", meta.allSeries().get(1).name());
            assertEquals(20, meta.allSeries().get(1).sizeZ());

            // Detailed series is the one we asked for
            assertEquals(1, meta.detailedSeriesIndex());
            assertEquals("Detail", meta.detailedSeries().name());
        }
    }

    // ---- Pixel data: basic shape ----

    @Test
    void readPlaneReturnsCorrectSizeUint8() throws IOException {
        try (var reader = oneSeriesReader()) {
            reader.open(DUMMY);
            byte[] plane = reader.readPlane(0, 0, 0, 0);
            // 32 * 32 * 1 byte
            assertEquals(32 * 32, plane.length);
        }
    }

    @Test
    void readPlaneReturnsCorrectSizeUint16() throws IOException {
        try (var reader = FakeImageReader.builder()
                .addSeries(FakeSeries.simple(64, 48, 1, 1, 1, PixelType.UINT16))
                .build()) {
            reader.open(DUMMY);
            byte[] plane = reader.readPlane(0, 0, 0, 0);
            assertEquals(64 * 48 * 2, plane.length);
        }
    }

    @Test
    void readPlaneReturnsCorrectSizeFloat() throws IOException {
        try (var reader = FakeImageReader.builder()
                .addSeries(FakeSeries.simple(16, 16, 1, 1, 1, PixelType.FLOAT))
                .build()) {
            reader.open(DUMMY);
            byte[] plane = reader.readPlane(0, 0, 0, 0);
            assertEquals(16 * 16 * 4, plane.length);
        }
    }

    // ---- Pixel data: values match the synthetic formula ----

    @Test
    void uint8ValuesMatchFormula() throws IOException {
        try (var reader = oneSeriesReader()) {
            reader.open(DUMMY);
            byte[] plane = reader.readPlane(0, 0, 0, 0);
            int sizeX = 32;
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    long expected = FakeImageReader.syntheticValue(x, y, sizeX, 0, 0, 0) & 0xFF;
                    int actual = Byte.toUnsignedInt(plane[y * sizeX + x]);
                    assertEquals(expected, actual,
                            "mismatch at (" + x + ", " + y + ")");
                }
            }
        }
    }

    @Test
    void uint16ValuesMatchFormula() throws IOException {
        int sizeX = 20, sizeY = 10;
        try (var reader = FakeImageReader.builder()
                .addSeries(FakeSeries.simple(sizeX, sizeY, 1, 2, 1, PixelType.UINT16))
                .build()) {
            reader.open(DUMMY);

            // Channel 1, so c * 7 = 7 is added to the formula
            byte[] plane = reader.readPlane(0, 1, 0, 0);
            var buf = ByteBuffer.wrap(plane).order(ByteOrder.LITTLE_ENDIAN);

            for (int y = 0; y < sizeY; y++) {
                for (int x = 0; x < sizeX; x++) {
                    long expected = FakeImageReader.syntheticValue(x, y, sizeX, 1, 0, 0) & 0xFFFF;
                    int actual = Short.toUnsignedInt(buf.getShort());
                    assertEquals(expected, actual,
                            "mismatch at (" + x + ", " + y + ") channel 1");
                }
            }
        }
    }

    @Test
    void floatValuesMatchFormula() throws IOException {
        int sizeX = 8, sizeY = 8;
        try (var reader = FakeImageReader.builder()
                .addSeries(FakeSeries.simple(sizeX, sizeY, 3, 1, 1, PixelType.FLOAT))
                .build()) {
            reader.open(DUMMY);

            // z=2, so z * 13 = 26 is added
            byte[] plane = reader.readPlane(0, 0, 2, 0);
            var buf = ByteBuffer.wrap(plane).order(ByteOrder.LITTLE_ENDIAN);

            for (int y = 0; y < sizeY; y++) {
                for (int x = 0; x < sizeX; x++) {
                    float expected = (float) FakeImageReader.syntheticValue(x, y, sizeX, 0, 2, 0);
                    float actual = buf.getFloat();
                    assertEquals(expected, actual,
                            "mismatch at (" + x + ", " + y + ") z=2");
                }
            }
        }
    }

    @Test
    void differentPlanesProduceDifferentData() throws IOException {
        try (var reader = FakeImageReader.builder()
                .addSeries(FakeSeries.simple(16, 16, 2, 2, 2, PixelType.UINT8))
                .build()) {
            reader.open(DUMMY);
            byte[] p000 = reader.readPlane(0, 0, 0, 0);
            byte[] p100 = reader.readPlane(0, 1, 0, 0);
            byte[] p010 = reader.readPlane(0, 0, 1, 0);
            byte[] p001 = reader.readPlane(0, 0, 0, 1);

            assertFalse(java.util.Arrays.equals(p000, p100));
            assertFalse(java.util.Arrays.equals(p000, p010));
            assertFalse(java.util.Arrays.equals(p000, p001));
        }
    }

    // ---- Byte order ----

    @Test
    void bigEndianProducesCorrectByteOrder() throws IOException {
        int sizeX = 4, sizeY = 4;
        try (var reader = FakeImageReader.builder()
                .littleEndian(false)
                .addSeries(FakeSeries.simple(sizeX, sizeY, 1, 1, 1, PixelType.UINT16))
                .build()) {
            reader.open(DUMMY);
            assertFalse(reader.isLittleEndian(0));

            byte[] plane = reader.readPlane(0, 0, 0, 0);
            var buf = ByteBuffer.wrap(plane).order(ByteOrder.BIG_ENDIAN);

            long expected = FakeImageReader.syntheticValue(0, 0, sizeX, 0, 0, 0) & 0xFFFF;
            assertEquals(expected, Short.toUnsignedInt(buf.getShort()));
        }
    }

    // ---- Coordinate validation ----

    @Test
    void readPlaneRejectsOutOfRangeCoordinates() throws IOException {
        try (var reader = FakeImageReader.builder()
                .addSeries(FakeSeries.simple(16, 16, 3, 2, 4, PixelType.UINT8))
                .build()) {
            reader.open(DUMMY);

            assertThrows(IllegalArgumentException.class,
                    () -> reader.readPlane(0, -1, 0, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> reader.readPlane(0, 2, 0, 0));   // sizeC = 2
            assertThrows(IllegalArgumentException.class,
                    () -> reader.readPlane(0, 0, -1, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> reader.readPlane(0, 0, 3, 0));   // sizeZ = 3
            assertThrows(IllegalArgumentException.class,
                    () -> reader.readPlane(0, 0, 0, -1));
            assertThrows(IllegalArgumentException.class,
                    () -> reader.readPlane(0, 0, 0, 4));   // sizeT = 4
        }
    }

    // ---- Builder validation ----

    @Test
    void builderRequiresAtLeastOneSeries() {
        assertThrows(IllegalStateException.class,
                () -> FakeImageReader.builder().build());
    }

    // ---- Helpers ----

    /** 32x32 single-plane UINT8 reader. */
    private FakeImageReader oneSeriesReader() {
        return FakeImageReader.builder()
                .addSeries(FakeSeries.simple(32, 32, 1, 1, 1, PixelType.UINT8))
                .build();
    }

    /** Reader with rich metadata for testing detail levels. */
    private FakeImageReader richReader() {
        var channels = List.of(
                ChannelInfo.of(0, "DAPI", "DAPI", 405.0, 461.0, 0xFF0000FF),
                ChannelInfo.of(1, "GFP", "EGFP", 488.0, 509.0, 0xFF00FF00),
                ChannelInfo.of(2, "mCherry", "mCherry", 561.0, 610.0, 0xFFFF0000));

        var instrument = new InstrumentInfo(
                "Plan-Apo 63x", "Zeiss", 63.0, null, 1.4, "Oil", "PlanApo");

        var extra = Map.of(
                "LaserPower", "100",
                "PinholeSize", "1.0 AU",
                "ScanSpeed", "7");

        var series = new FakeSeries(
                "Confocal Stack", 512, 512, 10, 3, 1,
                PixelType.UINT16, "XYCZT",
                PixelSize.of(0.103, LengthUnit.MICROMETER),
                PixelSize.of(0.103, LengthUnit.MICROMETER),
                PixelSize.of(0.5, LengthUnit.MICROMETER),
                channels, instrument, "2024-01-15T10:30:00Z", extra);

        return FakeImageReader.builder()
                .formatName("Zeiss CZI")
                .addSeries(series)
                .build();
    }
}
