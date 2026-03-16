package lab.kerrr.mcpbio.bioimageserver;

import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.FormatException;
import loci.formats.out.OMETiffWriter;
import loci.formats.services.OMEXMLService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link ImageWriter} implementation backed by Bio-Formats'
 * {@link OMETiffWriter}.
 *
 * <p>All Bio-Formats writer API usage is confined to this class.
 */
public final class BioFormatsWriter implements ImageWriter {

    private OMETiffWriter writer;
    private Path outputPath;

    @Override
    public void open(Path path, String omeXml, String compression) throws IOException {
        try {
            var factory = new ServiceFactory();
            var xmlService = factory.getInstance(OMEXMLService.class);
            var metadata = xmlService.createOMEXMLMetadata(omeXml);
            metadata.resolveReferences();

            writer = new OMETiffWriter();
            writer.setMetadataRetrieve(metadata);
            writer.setCompression(compression);

            // Use BigTIFF if the data is likely to exceed 4 GB
            // (conservative threshold to avoid truncation)
            writer.setBigTiff(true);

            writer.setId(path.toAbsolutePath().toString());
            outputPath = path;
        } catch (DependencyException | ServiceException e) {
            throw new IOException("Failed to initialize OME-XML metadata service", e);
        } catch (FormatException e) {
            throw new IOException("Failed to open output file: " + path, e);
        }
    }

    @Override
    public void setSeries(int series) throws IOException {
        checkOpen();
        try {
            writer.setSeries(series);
        } catch (FormatException e) {
            throw new IOException("Failed to set series " + series, e);
        }
    }

    @Override
    public void writePlane(int planeIndex, byte[] data) throws IOException {
        checkOpen();
        try {
            writer.saveBytes(planeIndex, data);
        } catch (FormatException e) {
            throw new IOException(
                    "Failed to write plane " + planeIndex, e);
        }
    }

    @Override
    public long getBytesWritten() {
        if (outputPath == null) return 0;
        try {
            return Files.size(outputPath);
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    private void checkOpen() {
        if (writer == null) {
            throw new IllegalStateException("writer is not open");
        }
    }
}
