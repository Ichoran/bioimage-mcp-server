package lab.kerrr.mcpbio.bioimageserver;

import lab.kerrr.mcpbio.bioimageserver.OmeXmlSurgery.SubsetSpec;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OmeXmlSurgeryTest {

    // ================================================================
    // Subsetting
    // ================================================================

    @Test
    void subsetKeepsAllWhenNoSubsetting() {
        String xml = sampleXml(3, 5, 2, 2);
        var spec = SubsetSpec.all(5, 3, 2);
        var result = OmeXmlSurgery.subset(xml, spec);

        assertEquals(1, result.seriesKept());
        assertEquals(3, result.channelsKept());
        assertEquals(5, result.zSlicesKept());
        assertEquals(2, result.timepointsKept());
        assertEquals(30, result.planesPerSeries());
    }

    @Test
    void subsetReducesChannels() {
        String xml = sampleXml(3, 2, 1, 0);
        var spec = new SubsetSpec(null, new int[] { 0, 2 },
                0, 1, 0, 0);
        var result = OmeXmlSurgery.subset(xml, spec);

        assertEquals(2, result.channelsKept());
        assertEquals(4, result.planesPerSeries()); // 2 ch × 2 Z × 1 T
        // Output XML should have SizeC=2
        assertTrue(result.xml().contains("SizeC=\"2\""));
    }

    @Test
    void subsetReducesZ() {
        String xml = sampleXml(1, 10, 1, 0);
        var spec = new SubsetSpec(null, null, 3, 6, 0, 0);
        var result = OmeXmlSurgery.subset(xml, spec);

        assertEquals(1, result.channelsKept());
        assertEquals(4, result.zSlicesKept());
        assertTrue(result.xml().contains("SizeZ=\"4\""));
    }

    @Test
    void subsetReducesTimepoints() {
        String xml = sampleXml(1, 1, 5, 0);
        var spec = new SubsetSpec(null, null, 0, 0, 1, 3);
        var result = OmeXmlSurgery.subset(xml, spec);

        assertEquals(3, result.timepointsKept());
        assertTrue(result.xml().contains("SizeT=\"3\""));
    }

    @Test
    void subsetSelectsSeriesWhenSpecified() {
        // Multi-series XML
        String xml = buildXml(FakeImageReader.builder()
                .addSeries(FakeImageReader.FakeSeries.simple(
                        16, 16, 1, 1, 1, PixelType.UINT8))
                .addSeries(FakeImageReader.FakeSeries.simple(
                        32, 32, 2, 2, 1, PixelType.UINT16))
                .build());

        // Select only series 1
        var spec = new SubsetSpec(1, null, 0, 1, 0, 0);
        var result = OmeXmlSurgery.subset(xml, spec);

        assertEquals(1, result.seriesKept());
        assertEquals(2, result.channelsKept());
        // Should contain SizeX=32 from series 1, not SizeX=16 from series 0
        assertTrue(result.xml().contains("SizeX=\"32\""));
        assertFalse(result.xml().contains("SizeX=\"16\""));
    }

    @Test
    void subsetPreservesOriginalMetadata() {
        String xml = sampleXmlWithOriginalMetadata(1, 1, 1, 3);
        var spec = SubsetSpec.all(1, 1, 1);
        var result = OmeXmlSurgery.subset(xml, spec);

        assertEquals(3, result.originalMetadataKept());
    }

    // ================================================================
    // Strip original metadata
    // ================================================================

    @Test
    void stripOriginalMetadataRemovesAnnotations() {
        String xml = sampleXmlWithOriginalMetadata(1, 1, 1, 5);
        assertEquals(5, OmeXmlSurgery.countOriginalMetadata(xml));

        String stripped = OmeXmlSurgery.stripOriginalMetadata(xml);
        assertEquals(0, OmeXmlSurgery.countOriginalMetadata(stripped));
        // Core structure should still be there
        assertTrue(stripped.contains("Pixels"));
        assertTrue(stripped.contains("SizeX"));
    }

    @Test
    void stripOriginalMetadataNoOpWhenNone() {
        String xml = sampleXml(1, 1, 1, 0);
        assertEquals(0, OmeXmlSurgery.countOriginalMetadata(xml));
        String stripped = OmeXmlSurgery.stripOriginalMetadata(xml);
        // Should not throw, should still be valid
        assertTrue(stripped.contains("Pixels"));
    }

    // ================================================================
    // Strip to minimal
    // ================================================================

    @Test
    void stripToMinimalKeepsEssentials() {
        String xml = sampleXmlWithOriginalMetadata(2, 3, 1, 5);
        String minimal = OmeXmlSurgery.stripToMinimal(xml);

        // Should keep Pixels, Channel, TiffData
        assertTrue(minimal.contains("Pixels"));
        assertTrue(minimal.contains("Channel"));
        assertTrue(minimal.contains("TiffData"));
        // Should not have OriginalMetadata
        assertEquals(0, OmeXmlSurgery.countOriginalMetadata(minimal));
    }

    // ================================================================
    // Count original metadata
    // ================================================================

    @Test
    void countOriginalMetadataReturnsCorrectCount() {
        assertEquals(0, OmeXmlSurgery.countOriginalMetadata(
                sampleXml(1, 1, 1, 0)));
        assertEquals(3, OmeXmlSurgery.countOriginalMetadata(
                sampleXmlWithOriginalMetadata(1, 1, 1, 3)));
    }

    // ================================================================
    // TiffData and Plane rebuilding
    // ================================================================

    @Test
    void subsetRebuildsTiffDataWithCorrectIndices() {
        String xml = sampleXml(2, 3, 2, 0);
        // Keep channel 1 only, Z [1,2], T [0,0]
        var spec = new SubsetSpec(null, new int[] { 1 }, 1, 2, 0, 0);
        var result = OmeXmlSurgery.subset(xml, spec);

        // Should have 2 planes (1 ch × 2 Z × 1 T)
        assertEquals(2, result.planesPerSeries());
        // IFDs should be 0 and 1
        assertTrue(result.xml().contains("IFD=\"0\""));
        assertTrue(result.xml().contains("IFD=\"1\""));
    }

    // ================================================================
    // Helpers
    // ================================================================

    /**
     * Generate sample OME-XML via FakeImageReader.
     */
    private String sampleXml(int sizeC, int sizeZ, int sizeT,
                              int originalMetadataCount) {
        if (originalMetadataCount > 0) {
            return sampleXmlWithOriginalMetadata(
                    sizeC, sizeZ, sizeT, originalMetadataCount);
        }
        return buildXml(FakeImageReader.builder()
                .addSeries(FakeImageReader.FakeSeries.simple(
                        16, 16, sizeZ, sizeC, sizeT, PixelType.UINT8))
                .build());
    }

    private String sampleXmlWithOriginalMetadata(
            int sizeC, int sizeZ, int sizeT, int metaCount) {
        var meta = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < metaCount; i++) {
            meta.put("Key" + i, "Value" + i);
        }
        return buildXml(FakeImageReader.builder()
                .addSeries(FakeImageReader.FakeSeries.simple(
                        16, 16, sizeZ, sizeC, sizeT, PixelType.UINT8))
                .originalMetadata(meta)
                .build());
    }

    private static String buildXml(FakeImageReader reader) {
        try {
            reader.open(java.nio.file.Path.of("/test"));
            String xml = reader.getOMEXML();
            reader.close();
            return xml;
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
