package audiotest;

import javax.sound.sampled.*;
import java.util.ArrayList;

/**
 * Represents an audio input device.
 */
public class AudioInput {
    private final Mixer mixer;
    private final TargetDataLine line;

    /**
     * Creates a new instance of AudioInput.
     *
     * @param mixer The {@link Mixer} of the audio input device.
     * @param line The {@link TargetDataLine} of the audio input device.
     */
    public AudioInput(Mixer mixer, TargetDataLine line) {
        this.mixer = mixer;
        this.line = line;
    }

    /**
     * Gets a list of all available AudioInputs.
     *
     * @return a list of all available AudioInputs.
     */
    public static ArrayList<AudioInput> getAudioInputs() {
        ArrayList<AudioInput> result = new ArrayList<>();

        //Iterate over all available mixers.
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(info);

                //Iterate over all available TargetDataLines of the current mixer.
                for (Line.Info lineInfo : mixer.getTargetLineInfo()) {
                    try {
                        Line line = mixer.getLine(lineInfo);

                        if (line instanceof TargetDataLine)
                            result.add(new AudioInput(mixer, (TargetDataLine) line));
                    } catch (LineUnavailableException e) {
                        System.err.println("Couldn't get the TargetDataLine for Mixer \"" + info.getName() + "\" as " +
                                "is currently unavailable!");
                        e.printStackTrace();
                    } catch (SecurityException e) {
                        System.err.println("Couldn't get the TargetDataLine for Mixer \"" + info.getName() + "\" due " +
                                "to security restrictions!");
                        e.printStackTrace();
                    }
                }
            } catch (SecurityException e) {
                System.err.println("Couldn't access Mixer \"" + info.getName() + "\" due to security restrictions!");
                e.printStackTrace();
            }
        }

        return result;
    }

    /**
     * Gets all supported {@link AudioFormat}s for this audio input device.
     *
     * @return all supported {@link AudioFormat}s for this audio input device.
     */
    public AudioFormat[] getSupportedAudioFormats() {
        return ((DataLine.Info) line.getLineInfo()).getFormats();
    }

    /**
     * Opens the audio input device with the given format and the given bufferSize.
     *
     * @param format The {@link AudioFormat} to use.
     * @param bufferSize The buffer size (in sample frames) to use. Might be negative if the lines default buffer size
     *                   should be used.
     * @throws LineUnavailableException Will be thrown if the line can't be opened due to resource restrictions.
     */
    public void open(AudioFormat format, int bufferSize) throws LineUnavailableException {
        int bufferSizeInBytes = bufferSize * format.getFrameSize();
        if (!line.isOpen()) {
            //Open the line.
            if (bufferSize > 0)
                line.open(format, bufferSizeInBytes);
            else
                line.open(format);
        }

        //Check if the buffer size was set correctly.
        if (bufferSize > 0 && line.getBufferSize() != bufferSizeInBytes)
            System.err.println("Couldn't set the buffer size to the desired " + bufferSizeInBytes + " bytes! Actual " +
                    "buffer size is " + line.getBufferSize() + " bytes instead...");
    }

    /**
     * Closes the audio input device.
     */
    public void close() {
        line.close();
    }

    /**
     * Starts the audio input device, so it may engage in data I/O.
     */
    public void start() {
        line.start();
    }

    /**
     * Stops the audio input device.
     */
    public void stop() {
        line.stop();
    }

    /**
     * Flushes the audio input devices internal buffer.
     */
    public void flush() {
        line.flush();
    }

    @Override
    public String toString() {
        return mixer.getMixerInfo().getName();
    }

    @Override
    protected void finalize() throws Throwable {
        stop();
        close();
        super.finalize();
    }

    /**
     * A thread that continuously captures audio samples in packets of a defined size until interrupted.
     */
    public class AudioCaptureThread extends Thread {
        private AudioInputListener listener;
        private int sampleSize;
        private volatile boolean running = true;

        /**
         * Creates a new instance of AudioCaptureThread.
         *
         * @param listener The {@link AudioInputListener} to update with the captured audio samples.
         * @param sampleSize The sample size (in audio frames) of each audio sample packet.
         */
        public AudioCaptureThread(AudioInputListener listener, int sampleSize) {
            this.listener = listener;
            this.sampleSize = sampleSize;
            start();
        }

        @Override
        public void run() {
            //Create a buffer for the audio samples.
            int bytesToRead = sampleSize * line.getFormat().getFrameSize();
            byte[] data = new byte[bytesToRead];

            while (!isInterrupted() && running) {
                //Read the next set of audio samples.
                int bytesRead = 0;
                while (bytesRead < bytesToRead) {
                    bytesRead += line.read(data, bytesRead, Math.min(bytesToRead, bytesToRead - bytesRead));
                }

                //Update the listener.
                listener.audioFrameCaptured(data);
            }
        }

        @Override
        public void interrupt() {
            super.interrupt();
            running = false;
        }
    }
}
