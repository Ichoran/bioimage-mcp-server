package lab.kerrr.mcpbio.bioimageserver;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Metadata for a single channel in a microscopy image.
 *
 * <p>All optional fields use empty optionals rather than nulls.
 * Wavelengths are in nanometers.  Color is an ARGB int (as used
 * by the OME data model).
 *
 * @param index                zero-based channel index within its series
 * @param name                 channel name, or null if unnamed
 * @param fluor                fluorophore name, or null if not specified
 * @param excitationWavelength excitation wavelength in nm
 * @param emissionWavelength   emission wavelength in nm
 * @param color                display color as ARGB int
 */
public record ChannelInfo(
        int index,
        String name,
        String fluor,
        OptionalDouble excitationWavelength,
        OptionalDouble emissionWavelength,
        OptionalInt color) {

    public ChannelInfo {
        if (index < 0) {
            throw new IllegalArgumentException("channel index must be non-negative");
        }
    }

    /** Convenience constructor for the common case with all-known fields. */
    public static ChannelInfo of(int index, String name, String fluor,
                                 double excitationNm, double emissionNm, int argb) {
        return new ChannelInfo(index, name, fluor,
                OptionalDouble.of(excitationNm), OptionalDouble.of(emissionNm),
                OptionalInt.of(argb));
    }

    /** Convenience constructor for a channel with only a name. */
    public static ChannelInfo named(int index, String name) {
        return new ChannelInfo(index, name, null,
                OptionalDouble.empty(), OptionalDouble.empty(),
                OptionalInt.empty());
    }
}
