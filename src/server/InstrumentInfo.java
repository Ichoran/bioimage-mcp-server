package lab.kerrr.mcpbio.bioimageserver;

/**
 * Instrument and objective metadata.
 *
 * <p>All fields are nullable — microscopy formats vary wildly in what
 * instrument metadata they record.  A record with all nulls is valid
 * (it means "no instrument metadata available").
 *
 * @param objectiveModel         objective model name (e.g. "Plan-Apochromat 63x/1.4 Oil")
 * @param manufacturer           objective manufacturer
 * @param nominalMagnification   stated magnification (e.g. 63.0)
 * @param calibratedMagnification actual measured magnification, if available
 * @param numericalAperture      NA of the objective
 * @param immersion              immersion medium (e.g. "Oil", "Water", "Air")
 * @param correction             optical correction type (e.g. "PlanApo", "PlanFluor")
 */
public record InstrumentInfo(
        String objectiveModel,
        String manufacturer,
        Double nominalMagnification,
        Double calibratedMagnification,
        Double numericalAperture,
        String immersion,
        String correction) {

    /** Returns true if no instrument metadata is available at all. */
    public boolean isEmpty() {
        return objectiveModel == null
                && manufacturer == null
                && nominalMagnification == null
                && calibratedMagnification == null
                && numericalAperture == null
                && immersion == null
                && correction == null;
    }
}
