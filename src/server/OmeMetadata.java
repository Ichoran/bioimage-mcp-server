package lab.kerrr.mcpbio.bioimageserver;

/**
 * A format-tagged metadata document for a file: a portable, whole-file
 * extended-metadata block carried as a {@code (format, content)} pair so the
 * representation can evolve without changing any wire protocol.
 *
 * <p>{@link #format} identifies how to interpret {@link #content}:
 * <ul>
 *   <li>{@link #FORMAT_OME_XML} — {@code content} is an OME-XML document.
 *       Bio-Formats synthesizes this from its OME metadata model for
 *       essentially every file it reads, so it is the universally-available
 *       form.</li>
 *   <li>{@link #FORMAT_OME_NGFF} — {@code content} is OME-NGFF (OME-Zarr)
 *       metadata JSON.  Reserved for a reader that can supply a native NGFF
 *       block; Bio-Formats core does not expose one (it normalizes through the
 *       OME model), so today this is emitted only if a reader overrides
 *       {@link ImageReader#getMetadataBlock()} to provide it.</li>
 * </ul>
 *
 * @param format  the content's format identifier ({@code "ome_xml"} / {@code "ome_ngff"})
 * @param content the metadata document (XML or JSON) as a string
 */
public record OmeMetadata(String format, String content) {

    public static final String FORMAT_OME_XML = "ome_xml";
    public static final String FORMAT_OME_NGFF = "ome_ngff";
}
