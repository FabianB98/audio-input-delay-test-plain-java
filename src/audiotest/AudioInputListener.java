package audiotest;

/**
 * A listener that gets called when an {@link AudioInput} has captured some audio samples.
 */
public interface AudioInputListener {
    /**
     * This method will get called whenever a new set of audio samples was captured.
     *
     * @param data The captured audio samples.
     */
    void audioFrameCaptured(byte[] data);
}
