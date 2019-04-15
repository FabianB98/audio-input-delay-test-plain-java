package audiotest;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;

public class Main {
    private static final int SAMPLE_SIZE = 1024;

    public static void main(String[] args) {
        //Get all audio input devices.
        ArrayList<AudioInput> inputs = AudioInput.getAudioInputs();

        //Show a list of all audio inputs.
        System.out.println("Found the following audio inputs:");
        for (int i = 0; i < inputs.size(); i++)
            System.out.println("  - " + i + ": " + inputs.get(i));
        System.out.println();

        //Ask the user to select an input device.
        System.out.println("Enter the index of the input device to use...");
        Scanner scanner = new Scanner(System.in);
        int inputDeviceIndex;
        try {
            inputDeviceIndex = Integer.parseInt(scanner.nextLine());
        } catch (NumberFormatException e) {
            System.err.println("Given input is not a valid integer! Defaulting to zero...");
            e.printStackTrace();
            inputDeviceIndex = 0;
        }
        final AudioInput input = inputs.get(inputDeviceIndex);
        System.out.println();

        //Show all supported audio formats of the selected input device.
        AudioFormat[] inputFormats = input.getSupportedAudioFormats();
        System.out.println("Desired input device supports the following audio formats:");
        for (int i = 0; i < inputFormats.length; i++)
            System.out.println("  - " + i + ": " + inputFormats[i]);
        System.out.println();

        //Let the user select an audio format.
        System.out.println("Enter the index of the audio format to use...");
        int inputFormatIndex;
        try {
            inputFormatIndex = Integer.parseInt(scanner.nextLine());
        } catch (NumberFormatException e) {
            System.err.println("Given input is not a valid integer! Defaulting to zero...");
            e.printStackTrace();
            inputFormatIndex = 0;
        }
        System.out.println();

        //Let the user specify a sample if the audio format doesn't have one.
        final AudioFormat inputFormat;
        if (inputFormats[inputFormatIndex].getSampleRate() == AudioSystem.NOT_SPECIFIED) {
            System.out.println("Desired audio format doesn't specify a sample rate. Please enter the sample rate to use...");
            float sampleRate;
            try {
                sampleRate = Float.parseFloat(scanner.nextLine());
            } catch (NumberFormatException e) {
                System.err.println("Given input is not a valid floating point value! Defaulting to 44100.0...");
                e.printStackTrace();
                sampleRate = 44100.0f;
            }
            AudioFormat format = inputFormats[inputFormatIndex];
            inputFormat = new AudioFormat(format.getEncoding(), sampleRate, format.getSampleSizeInBits(),
                    format.getChannels(), format.getFrameSize(), sampleRate, format.isBigEndian());
            System.out.println();
        } else {
            inputFormat = inputFormats[inputFormatIndex];
        }

        //Let the user specify a buffer size.
        System.out.println("Enter the desired buffer size...");
        int bufferSize;
        try {
            bufferSize = Integer.parseInt(scanner.nextLine());
        } catch (NumberFormatException e) {
            System.err.println("Given input is not a valid integer! Defaulting to 4096...");
            e.printStackTrace();
            bufferSize = 4096;
        }
        System.out.println();

        //Start capturing audio data.
        System.out.println("Starting to capture audio samples as soon as you're ready (Press enter to start). To " +
                "pause the console output while the application is capturing audio, press enter again.");
        scanner.nextLine();

        try {
            input.open(inputFormat, bufferSize);
        } catch (LineUnavailableException e) {
            System.err.println("Can't open the desired audio input device due to resource restrictions! Aborting...");
            e.printStackTrace();
            scanner.close();
            System.exit(1);
        }
        input.start();
        AudioInputListenerImplementation listener = new AudioInputListenerImplementation(inputFormat);
        AudioInput.AudioCaptureThread thread = input.new AudioCaptureThread(listener, SAMPLE_SIZE);

        //Handle user inputs.
        loop:
        while (true) {
            //Wait until the user wants to input something and pause the console output.
            scanner.nextLine();
            listener.output = false;
            System.out.println("Output paused. Enter \"flush\", \"restart\", \"reopen\", \"stop\", \"quit\" or nothing...");

            //Wait for the actual user input and handle it.
            String line = scanner.nextLine().trim();
            switch (line) {
                case "flush":
                    System.out.println("Flushing audio input...");
                    input.flush();
                    break;
                case "restart":
                    System.out.println("Restarting audio input...");
                    input.stop();
                    input.start();
                    break;
                case "reopen":
                    System.out.println("Reopening audio input...");
                    input.stop();
                    input.close();
                    try {
                        input.open(inputFormat, bufferSize);
                    } catch (LineUnavailableException e) {
                        System.err.println("Couldn't reopen the line due to resource restrictions! Stopping...");
                        e.printStackTrace();
                        break loop;
                    }
                    input.start();
                    break;
                case "stop":
                case "quit":
                    System.out.println("Stopping...");
                    break loop;
            }

            //Unpause the console output.
            System.out.println("Output unpaused...");
            listener.output = true;
        }

        //Clean up.
        scanner.close();
        thread.interrupt();
        try {
            thread.join();
        } catch (InterruptedException ignored) {
        }
        input.stop();
        input.close();
    }

    /**
     * An implementation of {@link AudioInputListener} that calculates and prints the RMS of the captured audio samples.
     */
    private static class AudioInputListenerImplementation implements AudioInputListener {
        volatile boolean output = true;

        private AudioFormat inputFormat;
        private int[] dataAsInts;
        private ByteToIntConverter converter;

        public AudioInputListenerImplementation(AudioFormat inputFormat) {
            this.inputFormat = inputFormat;

            dataAsInts = new int[SAMPLE_SIZE * inputFormat.getChannels()];
            converter = new ByteToIntConverter(inputFormat);
        }

        @Override
        public void audioFrameCaptured(byte[] data) {
            if (output) {
                //Calculate and print the RMS value of the captured audio samples.
                converter.bytesToInts(data, dataAsInts);
                double quadraticSum = Arrays.stream(dataAsInts)
                        .mapToDouble((int value) -> ((double) value) * ((double) value)).sum();
                double rms = Math.sqrt(quadraticSum / dataAsInts.length);
                System.out.println("RMS: " + rms);
            }
        }
    }

    /**
     * A helper class for converting the captured audio sample bytes to integers.
     */
    private static class ByteToIntConverter {
        private AudioFormat format;

        private int bytesPerInt;
        private ByteOrder order;
        private int srcPos;

        private int highestBitPos;
        private boolean signed;

        private byte[] localBytes;
        private ByteBuffer byteBuffer;

        public ByteToIntConverter(AudioFormat format) {
            this.format = format;

            //Determine the amount of bytes per integer, the byte order and the offset for the conversion.
            bytesPerInt = (int) Math.round(Math.ceil(((float) format.getSampleSizeInBits()) / 8.0f));
            order = format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
            srcPos = format.isBigEndian() ? 4 - bytesPerInt : 0;

            //Determine the formula to use for getting the most significant (i.e. highest order) byte of a number.
            highestBitPos = format.isBigEndian() ? 0 : bytesPerInt - 1;
            signed = format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED;

            //Create a buffer for converting exactly four bytes into an integer.
            localBytes = new byte[4];
            byteBuffer = ByteBuffer.wrap(localBytes).order(order);
        }

        /**
         * Converts the given byte data into integers.
         *
         * @param data   The bytes to convert to integers.
         * @param result The array which will be filled with the result. Note: This is a parameter by suggestion of a
         *               commenter on Stackoverflow to remove the creation of new result arrays inside the update loop.
         *               Therefore the length of this array must be the length of the data array divided by bytesPerInt.
         */
        public void bytesToInts(byte[] data, int[] result) {
            for (int i = 0; i < data.length / bytesPerInt; i++) {
                //Determine if the highest bit of the number is set.
                boolean highestBit = (((int) data[i * bytesPerInt + highestBitPos]) & 0x80) != 0;

                //Create exactly four bytes that represent the same number.
                byte initialValue = highestBit && signed ? (byte) -1 : (byte) 0;
                for (int j = 0; j < 4; j++)
                    localBytes[j] = initialValue;
                System.arraycopy(data, i * bytesPerInt, localBytes, srcPos, bytesPerInt);

                //Convert the four bytes into an integer.
                byteBuffer.position(0);
                result[i] = byteBuffer.getInt();
            }
        }
    }
}
