package lab.kerrr.mcpbio.bioimageserver;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Surgical modifications to OME-XML documents for export subsetting
 * and metadata control.
 *
 * <p>All operations parse the XML string into a DOM, modify it, and
 * serialize back to a string.  The goal is to touch as few elements
 * as possible so that metadata we don't understand passes through
 * unchanged.
 */
public final class OmeXmlSurgery {

    private OmeXmlSurgery() {}

    static final String OME_NS = "http://www.openmicroscopy.org/Schemas/OME/2016-06";
    static final String ORIGINAL_METADATA_NS = "openmicroscopy.org/OriginalMetadata";

    /**
     * Specification for subsetting an OME-XML document.
     *
     * @param seriesIndex  which series to export (null = all)
     * @param channels     channel indices to keep (null = all)
     * @param zStart       first Z-slice to keep (inclusive)
     * @param zEnd         last Z-slice to keep (inclusive)
     * @param tStart       first timepoint to keep (inclusive)
     * @param tEnd         last timepoint to keep (inclusive)
     */
    public record SubsetSpec(
            Integer seriesIndex,
            int[] channels,
            int zStart, int zEnd,
            int tStart, int tEnd) {

        /** Keep everything — no subsetting. */
        public static SubsetSpec all(int sizeZ, int sizeC, int sizeT) {
            return new SubsetSpec(null, null, 0, sizeZ - 1, 0, sizeT - 1);
        }
    }

    /**
     * Result of an XML surgery operation.
     *
     * @param xml                the modified XML string
     * @param seriesKept         number of Image elements retained
     * @param channelsKept       channels per series in the output
     * @param zSlicesKept        Z-slices per series in the output
     * @param timepointsKept     timepoints per series in the output
     * @param planesPerSeries    total planes per series in the output
     * @param originalMetadataKept  count of OriginalMetadataAnnotation
     *                              entries remaining in the output XML
     */
    public record SurgeryResult(
            String xml,
            int seriesKept,
            int channelsKept,
            int zSlicesKept,
            int timepointsKept,
            int planesPerSeries,
            int originalMetadataKept) {}

    /**
     * Subset an OME-XML document: keep only the specified series,
     * channels, Z-slices, and timepoints.  Updates Pixels dimension
     * attributes, removes unwanted Channel and Plane elements, and
     * rebuilds TiffData elements for the subset.
     *
     * @param xml  the source OME-XML string
     * @param spec what to keep
     * @return the modified XML and a summary of what was kept
     */
    public static SurgeryResult subset(String xml, SubsetSpec spec) {
        var doc = parse(xml);
        var root = doc.getDocumentElement();

        // Collect Image elements
        var images = getChildElements(root, OME_NS, "Image");

        // If subsetting to a single series, remove other Image elements
        if (spec.seriesIndex() != null) {
            for (int i = images.size() - 1; i >= 0; i--) {
                if (i != spec.seriesIndex()) {
                    root.removeChild(images.get(i));
                }
            }
            images = getChildElements(root, OME_NS, "Image");
        }

        int channelsKept = 0;
        int zKept = spec.zEnd() - spec.zStart() + 1;
        int tKept = spec.tEnd() - spec.tStart() + 1;

        // Process each remaining Image element
        for (var image : images) {
            var pixels = getFirstChild(image, OME_NS, "Pixels");
            if (pixels == null) continue;

            // Determine channel set
            int origSizeC = Integer.parseInt(pixels.getAttribute("SizeC"));
            Set<Integer> keepChannels;
            if (spec.channels() != null) {
                keepChannels = IntStream.of(spec.channels())
                        .boxed().collect(Collectors.toSet());
            } else {
                keepChannels = IntStream.range(0, origSizeC)
                        .boxed().collect(Collectors.toSet());
            }
            channelsKept = keepChannels.size();

            // Update dimension attributes
            pixels.setAttribute("SizeC", String.valueOf(channelsKept));
            pixels.setAttribute("SizeZ", String.valueOf(zKept));
            pixels.setAttribute("SizeT", String.valueOf(tKept));

            // Remove unwanted Channel elements (by index)
            var channelElems = getChildElements(pixels, OME_NS, "Channel");
            for (int i = channelElems.size() - 1; i >= 0; i--) {
                if (!keepChannels.contains(i)) {
                    pixels.removeChild(channelElems.get(i));
                }
            }
            // Re-index remaining Channel IDs
            var remainingChannels = getChildElements(pixels, OME_NS, "Channel");
            for (int i = 0; i < remainingChannels.size(); i++) {
                var ch = remainingChannels.get(i);
                // Update the ID to reflect new index
                String oldId = ch.getAttribute("ID");
                if (oldId != null && oldId.contains(":")) {
                    String prefix = oldId.substring(0, oldId.lastIndexOf(':'));
                    ch.setAttribute("ID", prefix + ":" + i);
                }
            }

            // Remove all existing TiffData and Plane elements
            removeChildren(pixels, OME_NS, "TiffData");
            removeChildren(pixels, OME_NS, "Plane");

            // Rebuild TiffData and Plane elements for the subset
            // Plane order: C varies fastest (XYCZT convention), then Z, then T
            int planeIdx = 0;
            int[] sortedChannels = keepChannels.stream()
                    .mapToInt(Integer::intValue).sorted().toArray();
            for (int t = spec.tStart(); t <= spec.tEnd(); t++) {
                for (int z = spec.zStart(); z <= spec.zEnd(); z++) {
                    for (int newC = 0; newC < sortedChannels.length; newC++) {
                        var td = doc.createElementNS(OME_NS, "TiffData");
                        td.setAttribute("FirstC", String.valueOf(newC));
                        td.setAttribute("FirstZ", String.valueOf(z - spec.zStart()));
                        td.setAttribute("FirstT", String.valueOf(t - spec.tStart()));
                        td.setAttribute("PlaneCount", "1");
                        td.setAttribute("IFD", String.valueOf(planeIdx));
                        pixels.appendChild(td);

                        var plane = doc.createElementNS(OME_NS, "Plane");
                        plane.setAttribute("TheC", String.valueOf(newC));
                        plane.setAttribute("TheZ", String.valueOf(z - spec.zStart()));
                        plane.setAttribute("TheT", String.valueOf(t - spec.tStart()));
                        pixels.appendChild(plane);

                        planeIdx++;
                    }
                }
            }
        }

        int planesPerSeries = channelsKept * zKept * tKept;
        int origMetaCount = countOriginalMetadata(doc);

        return new SurgeryResult(
                serialize(doc), images.size(), channelsKept,
                zKept, tKept, planesPerSeries, origMetaCount);
    }

    /**
     * Strip all OriginalMetadataAnnotation entries from the OME-XML.
     * Keeps all structured OME schema elements intact.
     *
     * @return the modified XML string
     */
    public static String stripOriginalMetadata(String xml) {
        var doc = parse(xml);
        var root = doc.getDocumentElement();

        var structuredAnnotations = getFirstChild(
                root, OME_NS, "StructuredAnnotations");
        if (structuredAnnotations == null) return xml;

        var xmlAnnotations = getChildElements(
                structuredAnnotations, OME_NS, "XMLAnnotation");
        for (var ann : xmlAnnotations) {
            String ns = ann.getAttribute("Namespace");
            if (ORIGINAL_METADATA_NS.equals(ns)) {
                structuredAnnotations.removeChild(ann);
            }
        }

        // If StructuredAnnotations is now empty, remove it entirely
        if (!structuredAnnotations.hasChildNodes()
                || getChildElements(structuredAnnotations, null, null).isEmpty()) {
            root.removeChild(structuredAnnotations);
        }

        return serialize(doc);
    }

    /**
     * Strip the XML to minimal metadata: only Pixels with dimension
     * attributes, Channel elements, and TiffData.  Removes everything
     * else (StructuredAnnotations, Instrument references, etc.).
     *
     * @return the modified XML string
     */
    public static String stripToMinimal(String xml) {
        var doc = parse(xml);
        var root = doc.getDocumentElement();

        // Remove StructuredAnnotations entirely
        var sa = getFirstChild(root, OME_NS, "StructuredAnnotations");
        if (sa != null) root.removeChild(sa);

        // In each Image, strip everything except Pixels
        // In Pixels, keep only Channel, TiffData, Plane
        var images = getChildElements(root, OME_NS, "Image");
        for (var image : images) {
            // Remove non-Pixels children of Image
            // (InstrumentRef, ExperimenterRef, etc.)
            var imageChildren = getChildElements(image, null, null);
            for (var child : imageChildren) {
                if (!"Pixels".equals(child.getLocalName())) {
                    image.removeChild(child);
                }
            }

            var pixels = getFirstChild(image, OME_NS, "Pixels");
            if (pixels == null) continue;

            // In Pixels, keep only Channel, TiffData, Plane
            Set<String> keepElements = Set.of(
                    "Channel", "TiffData", "Plane");
            var pixelsChildren = getChildElements(pixels, null, null);
            for (var child : pixelsChildren) {
                if (!keepElements.contains(child.getLocalName())) {
                    pixels.removeChild(child);
                }
            }
        }

        return serialize(doc);
    }

    /**
     * Count the number of OriginalMetadataAnnotation entries in the
     * OME-XML.
     */
    public static int countOriginalMetadata(String xml) {
        return countOriginalMetadata(parse(xml));
    }

    // ================================================================
    // DOM helpers
    // ================================================================

    private static int countOriginalMetadata(Document doc) {
        var root = doc.getDocumentElement();
        var sa = getFirstChild(root, OME_NS, "StructuredAnnotations");
        if (sa == null) return 0;

        int count = 0;
        var annotations = getChildElements(sa, OME_NS, "XMLAnnotation");
        for (var ann : annotations) {
            String ns = ann.getAttribute("Namespace");
            if (ORIGINAL_METADATA_NS.equals(ns)) {
                count++;
            }
        }
        return count;
    }

    private static Document parse(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to parse OME-XML: " + e.getMessage(), e);
        }
    }

    private static String serialize(Document doc) {
        try {
            var tf = TransformerFactory.newInstance();
            var transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            var writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to serialize OME-XML: " + e.getMessage(), e);
        }
    }

    /**
     * Get direct child elements matching a namespace and local name.
     * If namespace or localName is null, matches any.
     */
    static List<Element> getChildElements(
            Element parent, String namespace, String localName) {
        var result = new ArrayList<Element>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element elem) {
                boolean nsMatch = namespace == null
                        || namespace.equals(elem.getNamespaceURI());
                boolean nameMatch = localName == null
                        || localName.equals(elem.getLocalName());
                if (nsMatch && nameMatch) {
                    result.add(elem);
                }
            }
        }
        return result;
    }

    private static Element getFirstChild(
            Element parent, String namespace, String localName) {
        var children = getChildElements(parent, namespace, localName);
        return children.isEmpty() ? null : children.getFirst();
    }

    private static void removeChildren(
            Element parent, String namespace, String localName) {
        var children = getChildElements(parent, namespace, localName);
        for (var child : children) {
            parent.removeChild(child);
        }
    }
}
