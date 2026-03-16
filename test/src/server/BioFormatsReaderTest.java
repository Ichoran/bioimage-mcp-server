package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.ImageMetadata.DetailLevel;
import lab.kerrr.mcpbio.bioimageserver.PixelSize.LengthUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link BioFormatsReader} and {@link BioFormatsWriter}.
 *
 * <p>These tests create synthetic OME-TIFF files with known properties
 * using {@link BioFormatsWriter}, then read them back with
 * {@link BioFormatsReader} to verify correctness.
 */
class BioFormatsReaderTest {

    @TempDir
    Path tempDir;

    private BioFormatsReader reader;

    @BeforeEach
    void setUp() {
        reader = new BioFormatsReader();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (reader != null) reader.close();
    }

    // ---- Helpers ----

    /**
     * Build minimal OME-XML for a single-series image.
     */
    private static String omeXml(int sizeX, int sizeY, int sizeZ,
                                  int sizeC, int sizeT, String pixelType) {
        return omeXml(sizeX, sizeY, sizeZ, sizeC, sizeT, pixelType, null);
    }

    private static String omeXml(int sizeX, int sizeY, int sizeZ,
                                  int sizeC, int sizeT, String pixelType,
                                  String extraElements) {
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<OME xmlns=\"http://www.openmicroscopy.org/Schemas/OME/2016-06\">");
        sb.append("<Image ID=\"Image:0\" Name=\"Test Image\">");
        sb.append("<Pixels ID=\"Pixels:0\" DimensionOrder=\"XYCZT\"");
        sb.append(" SizeX=\"").append(sizeX).append("\"");
        sb.append(" SizeY=\"").append(sizeY).append("\"");
        sb.append(" SizeZ=\"").append(sizeZ).append("\"");
        sb.append(" SizeC=\"").append(sizeC).append("\"");
        sb.append(" SizeT=\"").append(sizeT).append("\"");
        sb.append(" Type=\"").append(pixelType).append("\"");
        sb.append(" BigEndian=\"false\"");
        sb.append(">");

        // Channel elements
        for (int c = 0; c < sizeC; c++) {
            sb.append("<Channel ID=\"Channel:0:").append(c)
              .append("\" Name=\"Ch").append(c)
              .append("\" SamplesPerPixel=\"1\"/>");
        }

        // TiffData elements
        int planeIndex = 0;
        for (int t = 0; t < sizeT; t++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int c = 0; c < sizeC; c++) {
                    sb.append("<TiffData FirstC=\"").append(c)
                      .append("\" FirstZ=\"").append(z)
                      .append("\" FirstT=\"").append(t)
                      .append("\" PlaneCount=\"1\" IFD=\"")
                      .append(planeIndex++).append("\"/>");
                }
            }
        }

        sb.append("</Pixels>");
        if (extraElements != null) {
            sb.append(extraElements);
        }
        sb.append("</Image>");
        sb.append("</OME>");
        return sb.toString();
    }

    /** Generate a plane of uint8 data with a predictable pattern. */
    private static byte[] uint8Plane(int sizeX, int sizeY, int c, int z, int t) {
        var data = new byte[sizeX * sizeY];
        for (int y = 0; y < sizeY; y++) {
            for (int x = 0; x < sizeX; x++) {
                data[y * sizeX + x] = (byte) ((y * sizeX + x + c * 7 + z * 13 + t * 31) & 0xFF);
            }
        }
        return data;
    }

    /** Generate a plane of uint16 data with a predictable pattern. */
    private static byte[] uint16Plane(int sizeX, int sizeY, int c, int z, int t) {
        var buf = ByteBuffer.allocate(sizeX * sizeY * 2);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < sizeY; y++) {
            for (int x = 0; x < sizeX; x++) {
                int val = (y * sizeX + x + c * 7 + z * 13 + t * 31) & 0xFFFF;
                buf.putShort((short) val);
            }
        }
        return buf.array();
    }

    /** Write a simple single-series OME-TIFF and return the path. */
    private Path writeSingleSeriesUint8(int sizeX, int sizeY, int sizeZ,
                                         int sizeC, int sizeT) throws IOException {
        Path file = tempDir.resolve("test.ome.tif");
        String xml = omeXml(sizeX, sizeY, sizeZ, sizeC, sizeT, "uint8");

        try (var writer = new BioFormatsWriter()) {
            writer.open(file, xml, "Uncompressed");
            writer.setSeries(0);
            int planeIndex = 0;
            for (int t = 0; t < sizeT; t++) {
                for (int z = 0; z < sizeZ; z++) {
                    for (int c = 0; c < sizeC; c++) {
                        writer.writePlane(planeIndex++,
                                uint8Plane(sizeX, sizeY, c, z, t));
                    }
                }
            }
        }
        return file;
    }

    // ---- Tests: basic lifecycle ----

    @Test
    void openAndCloseSimpleFile() throws IOException {
        Path file = writeSingleSeriesUint8(32, 24, 1, 1, 1);
        reader.open(file);
        assertEquals(1, reader.getSeriesCount());
        reader.close();
    }

    @Test
    void doubleCloseIsNoOp() throws IOException {
        Path file = writeSingleSeriesUint8(32, 24, 1, 1, 1);
        reader.open(file);
        reader.close();
        reader.close(); // should not throw
    }

    @Test
    void openNonExistentFileThrows() {
        assertThrows(IOException.class, () ->
                reader.open(tempDir.resolve("nonexistent.tif")));
    }

    // ---- Tests: metadata ----

    @Test
    void metadataDimensions() throws IOException {
        Path file = writeSingleSeriesUint8(64, 48, 5, 3, 2);
        reader.open(file);

        var metadata = reader.getMetadata(0, DetailLevel.SUMMARY);
        var series = metadata.detailedSeries();
        assertEquals(64, series.sizeX());
        assertEquals(48, series.sizeY());
        assertEquals(5, series.sizeZ());
        assertEquals(3, series.sizeC());
        assertEquals(2, series.sizeT());
        assertEquals(PixelType.UINT8, series.pixelType());
    }

    @Test
    void metadataFormatName() throws IOException {
        Path file = writeSingleSeriesUint8(32, 24, 1, 1, 1);
        reader.open(file);
        var metadata = reader.getMetadata(0, DetailLevel.SUMMARY);
        assertNotNull(metadata.formatName());
        assertFalse(metadata.formatName().isBlank());
    }

    @Test
    void metadataSeriesName() throws IOException {
        Path file = writeSingleSeriesUint8(32, 24, 1, 1, 1);
        reader.open(file);
        var metadata = reader.getMetadata(0, DetailLevel.SUMMARY);
        assertEquals("Test Image", metadata.detailedSeries().name());
    }

    @Test
    void metadataChannelNames() throws IOException {
        Path file = writeSingleSeriesUint8(32, 24, 1, 3, 1);
        reader.open(file);
        var metadata = reader.getMetadata(0, DetailLevel.SUMMARY);
        var channels = metadata.detailedSeries().channels();
        assertEquals(3, channels.size());
        assertEquals("Ch0", channels.get(0).name());
        assertEquals("Ch1", channels.get(1).name());
        assertEquals("Ch2", channels.get(2).name());
    }

    @Test
    void metadataAllSeriesSummaries() throws IOException {
        // Write a two-series file
        Path file = tempDir.resolve("multi.ome.tif");
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<OME xmlns=\"http://www.openmicroscopy.org/Schemas/OME/2016-06\">");

        // Series 0: 32x24, 1 channel
        sb.append("<Image ID=\"Image:0\" Name=\"Small\">");
        sb.append("<Pixels ID=\"Pixels:0\" DimensionOrder=\"XYCZT\"");
        sb.append(" SizeX=\"32\" SizeY=\"24\" SizeZ=\"1\" SizeC=\"1\" SizeT=\"1\"");
        sb.append(" Type=\"uint8\" BigEndian=\"false\">");
        sb.append("<Channel ID=\"Channel:0:0\" Name=\"Ch0\" SamplesPerPixel=\"1\"/>");
        sb.append("<TiffData FirstC=\"0\" FirstZ=\"0\" FirstT=\"0\" PlaneCount=\"1\" IFD=\"0\"/>");
        sb.append("</Pixels></Image>");

        // Series 1: 16x12, 2 channels
        sb.append("<Image ID=\"Image:1\" Name=\"Tiny\">");
        sb.append("<Pixels ID=\"Pixels:1\" DimensionOrder=\"XYCZT\"");
        sb.append(" SizeX=\"16\" SizeY=\"12\" SizeZ=\"1\" SizeC=\"2\" SizeT=\"1\"");
        sb.append(" Type=\"uint8\" BigEndian=\"false\">");
        sb.append("<Channel ID=\"Channel:1:0\" Name=\"A\" SamplesPerPixel=\"1\"/>");
        sb.append("<Channel ID=\"Channel:1:1\" Name=\"B\" SamplesPerPixel=\"1\"/>");
        sb.append("<TiffData FirstC=\"0\" FirstZ=\"0\" FirstT=\"0\" PlaneCount=\"1\" IFD=\"1\"/>");
        sb.append("<TiffData FirstC=\"1\" FirstZ=\"0\" FirstT=\"0\" PlaneCount=\"1\" IFD=\"2\"/>");
        sb.append("</Pixels></Image>");

        sb.append("</OME>");

        try (var writer = new BioFormatsWriter()) {
            writer.open(file, sb.toString(), "Uncompressed");
            writer.setSeries(0);
            writer.writePlane(0, uint8Plane(32, 24, 0, 0, 0));
            writer.setSeries(1);
            writer.writePlane(0, uint8Plane(16, 12, 0, 0, 0));
            writer.writePlane(1, uint8Plane(16, 12, 1, 0, 0));
        }

        reader.open(file);
        assertEquals(2, reader.getSeriesCount());

        var meta0 = reader.getMetadata(0, DetailLevel.SUMMARY);
        assertEquals(2, meta0.allSeries().size());
        assertEquals("Small", meta0.allSeries().get(0).name());
        assertEquals(32, meta0.allSeries().get(0).sizeX());
        assertEquals("Tiny", meta0.allSeries().get(1).name());
        assertEquals(16, meta0.allSeries().get(1).sizeX());
        assertEquals(2, meta0.allSeries().get(1).sizeC());
    }

    @Test
    void metadataDetailLevelSummary() throws IOException {
        Path file = writeSingleSeriesUint8(32, 24, 1, 1, 1);
        reader.open(file);

        var metadata = reader.getMetadata(0, DetailLevel.SUMMARY);
        assertEquals(DetailLevel.SUMMARY, metadata.detailLevel());
        // Summary: no instrument, no acquisition date
        assertNull(metadata.detailedSeries().instrument());
        assertNull(metadata.detailedSeries().acquisitionDate());
        assertTrue(metadata.detailedSeries().extraMetadata().isEmpty());
    }

    @Test
    void metadataDetailLevelStandard() throws IOException {
        Path file = writeSingleSeriesUint8(32, 24, 1, 1, 1);
        reader.open(file);

        var metadata = reader.getMetadata(0, DetailLevel.STANDARD);
        assertEquals(DetailLevel.STANDARD, metadata.detailLevel());
        // Standard has empty extra metadata
        assertTrue(metadata.detailedSeries().extraMetadata().isEmpty());
    }

    @Test
    void metadataDetailLevelFull() throws IOException {
        Path file = writeSingleSeriesUint8(32, 24, 1, 1, 1);
        reader.open(file);

        var metadata = reader.getMetadata(0, DetailLevel.FULL);
        assertEquals(DetailLevel.FULL, metadata.detailLevel());
        assertEquals(0, metadata.omittedMetadataBytes());
    }

    @Test
    void metadataDimensionOrder() throws IOException {
        Path file = writeSingleSeriesUint8(32, 24, 1, 1, 1);
        reader.open(file);
        var metadata = reader.getMetadata(0, DetailLevel.SUMMARY);
        assertNotNull(metadata.detailedSeries().dimensionOrder());
        assertEquals(5, metadata.detailedSeries().dimensionOrder().length());
    }

    @Test
    void metadataSeriesIndexOutOfRange() throws IOException {
        Path file = writeSingleSeriesUint8(32, 24, 1, 1, 1);
        reader.open(file);
        assertThrows(IllegalArgumentException.class, () ->
                reader.getMetadata(1, DetailLevel.SUMMARY));
        assertThrows(IllegalArgumentException.class, () ->
                reader.getMetadata(-1, DetailLevel.SUMMARY));
    }

    // ---- Tests: pixel data ----

    @Test
    void readPlaneUint8() throws IOException {
        int sizeX = 32, sizeY = 24;
        Path file = writeSingleSeriesUint8(sizeX, sizeY, 1, 1, 1);
        reader.open(file);

        byte[] expected = uint8Plane(sizeX, sizeY, 0, 0, 0);
        byte[] actual = reader.readPlane(0, 0, 0, 0);
        assertArrayEquals(expected, actual);
    }

    @Test
    void readPlaneMultiChannel() throws IOException {
        int sizeX = 16, sizeY = 12;
        Path file = writeSingleSeriesUint8(sizeX, sizeY, 1, 3, 1);
        reader.open(file);

        for (int c = 0; c < 3; c++) {
            byte[] expected = uint8Plane(sizeX, sizeY, c, 0, 0);
            byte[] actual = reader.readPlane(0, c, 0, 0);
            assertArrayEquals(expected, actual,
                    "Mismatch for channel " + c);
        }
    }

    @Test
    void readPlaneMultiZAndT() throws IOException {
        int sizeX = 8, sizeY = 8;
        Path file = writeSingleSeriesUint8(sizeX, sizeY, 3, 2, 2);
        reader.open(file);

        // Spot-check a few planes
        assertArrayEquals(uint8Plane(sizeX, sizeY, 0, 0, 0),
                reader.readPlane(0, 0, 0, 0));
        assertArrayEquals(uint8Plane(sizeX, sizeY, 1, 2, 1),
                reader.readPlane(0, 1, 2, 1));
        assertArrayEquals(uint8Plane(sizeX, sizeY, 0, 1, 0),
                reader.readPlane(0, 0, 1, 0));
    }

    @Test
    void readPlaneUint16() throws IOException {
        int sizeX = 16, sizeY = 12;
        Path file = tempDir.resolve("uint16.ome.tif");
        String xml = omeXml(sizeX, sizeY, 1, 1, 1, "uint16");

        try (var writer = new BioFormatsWriter()) {
            writer.open(file, xml, "Uncompressed");
            writer.setSeries(0);
            writer.writePlane(0, uint16Plane(sizeX, sizeY, 0, 0, 0));
        }

        reader.open(file);
        assertEquals(PixelType.UINT16, reader.getMetadata(0, DetailLevel.SUMMARY)
                .detailedSeries().pixelType());

        byte[] expected = uint16Plane(sizeX, sizeY, 0, 0, 0);
        byte[] actual = reader.readPlane(0, 0, 0, 0);
        assertArrayEquals(expected, actual);
    }

    @Test
    void readPlaneOutOfRange() throws IOException {
        Path file = writeSingleSeriesUint8(32, 24, 2, 3, 4);
        reader.open(file);
        assertThrows(IllegalArgumentException.class, () ->
                reader.readPlane(0, 3, 0, 0));
        assertThrows(IllegalArgumentException.class, () ->
                reader.readPlane(0, 0, 2, 0));
        assertThrows(IllegalArgumentException.class, () ->
                reader.readPlane(0, 0, 0, 4));
        assertThrows(IllegalArgumentException.class, () ->
                reader.readPlane(0, -1, 0, 0));
    }

    // ---- Tests: byte order ----

    @Test
    void isLittleEndian() throws IOException {
        Path file = writeSingleSeriesUint8(32, 24, 1, 1, 1);
        reader.open(file);
        // Our test OME-XML specifies BigEndian="false"
        assertTrue(reader.isLittleEndian(0));
    }

    // ---- Tests: OME-XML ----

    @Test
    void getOMEXMLReturnsNonNull() throws IOException {
        Path file = writeSingleSeriesUint8(32, 24, 1, 1, 1);
        reader.open(file);
        String xml = reader.getOMEXML();
        assertNotNull(xml);
        assertTrue(xml.contains("<OME"));
        assertTrue(xml.contains("Pixels"));
    }

    @Test
    void getOriginalMetadataCountNonNegative() throws IOException {
        Path file = writeSingleSeriesUint8(32, 24, 1, 1, 1);
        reader.open(file);
        assertTrue(reader.getOriginalMetadataCount() >= 0);
    }

    // ---- Tests: physical pixel sizes ----

    @Test
    void physicalPixelSizesRoundTrip() throws IOException {
        Path file = tempDir.resolve("physsize.ome.tif");
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<OME xmlns=\"http://www.openmicroscopy.org/Schemas/OME/2016-06\">");
        sb.append("<Image ID=\"Image:0\" Name=\"Test\">");
        sb.append("<Pixels ID=\"Pixels:0\" DimensionOrder=\"XYCZT\"");
        sb.append(" SizeX=\"8\" SizeY=\"8\" SizeZ=\"1\" SizeC=\"1\" SizeT=\"1\"");
        sb.append(" Type=\"uint8\" BigEndian=\"false\"");
        sb.append(" PhysicalSizeX=\"0.325\" PhysicalSizeXUnit=\"µm\"");
        sb.append(" PhysicalSizeY=\"0.325\" PhysicalSizeYUnit=\"µm\"");
        sb.append(">");
        sb.append("<Channel ID=\"Channel:0:0\" SamplesPerPixel=\"1\"/>");
        sb.append("<TiffData FirstC=\"0\" FirstZ=\"0\" FirstT=\"0\" PlaneCount=\"1\" IFD=\"0\"/>");
        sb.append("</Pixels></Image></OME>");

        try (var writer = new BioFormatsWriter()) {
            writer.open(file, sb.toString(), "Uncompressed");
            writer.setSeries(0);
            writer.writePlane(0, new byte[64]);
        }

        reader.open(file);
        var series = reader.getMetadata(0, DetailLevel.SUMMARY).detailedSeries();

        assertNotNull(series.physicalSizeX());
        assertNotNull(series.physicalSizeY());
        assertEquals(LengthUnit.MICROMETER, series.physicalSizeX().unit());
        assertEquals(0.325, series.physicalSizeX().doubleValue(), 1e-6);
        assertEquals(0.325, series.physicalSizeY().doubleValue(), 1e-6);
    }

    // ---- Tests: compressed writing ----

    @Test
    void lzwCompressedRoundTrip() throws IOException {
        int sizeX = 32, sizeY = 24;
        Path file = tempDir.resolve("lzw.ome.tif");
        String xml = omeXml(sizeX, sizeY, 1, 1, 1, "uint8");

        try (var writer = new BioFormatsWriter()) {
            writer.open(file, xml, "LZW");
            writer.setSeries(0);
            writer.writePlane(0, uint8Plane(sizeX, sizeY, 0, 0, 0));
        }

        reader.open(file);
        byte[] actual = reader.readPlane(0, 0, 0, 0);
        assertArrayEquals(uint8Plane(sizeX, sizeY, 0, 0, 0), actual);
    }

    // ---- Tests: state checks ----

    @Test
    void getSeriesCountBeforeOpenThrows() {
        assertThrows(IllegalStateException.class, () -> reader.getSeriesCount());
    }

    @Test
    void getMetadataBeforeOpenThrows() {
        assertThrows(IllegalStateException.class, () ->
                reader.getMetadata(0, DetailLevel.SUMMARY));
    }

    @Test
    void readPlaneBeforeOpenThrows() {
        assertThrows(IllegalStateException.class, () ->
                reader.readPlane(0, 0, 0, 0));
    }

    @Test
    void isLittleEndianBeforeOpenThrows() {
        assertThrows(IllegalStateException.class, () ->
                reader.isLittleEndian(0));
    }

    @Test
    void getOMEXMLBeforeOpenThrows() {
        assertThrows(IllegalStateException.class, () ->
                reader.getOMEXML());
    }
}
