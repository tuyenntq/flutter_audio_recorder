package com.zeno.flutter_audio_recorder;

/*
~ Nilesh Deokar @nieldeokar on 09/17/18 8:11 AM
*/

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.HashMap;

public class AACRecordThread extends RecordThread {
    private static final String TAG = AACRecordThread.class.getSimpleName();

    private static final int SAMPLE_RATE = 44100;
    private static final int SAMPLE_RATE_INDEX = 4;
    private static final int CHANNELS = 1;
    private static final int BIT_RATE = 32000;
    private static final int SAMPLE_BUFFER_SIZE = 6400;

    private MediaCodec mediaCodec = null;
    private AudioRecord audioRecord = null;
    private FileOutputStream fileOutputStream = null;
    private double peakPower = -120;
    private double averagePower = -120;
    private Thread recordingThread = null;
    private long dataSize = 0;
    private ShortBuffer sampleDataBuffer = ShortBuffer.allocate(SAMPLE_BUFFER_SIZE);
    private int currentFilledDataSize = 0;

    AACRecordThread(int sampleRate, String filePath, String extension) {
        super(sampleRate, filePath, extension);
        // TODO: find a way to change sample rate
        this.sampleRate = SAMPLE_RATE;
    }

    @Override
    public void start() throws IOException {
        Log.d(TAG, "bufferSize: " + this.bufferSize);

        this.audioRecord = createAudioRecord(this.bufferSize);
        this.mediaCodec = createMediaCodec(this.bufferSize);

        fileOutputStream = new FileOutputStream(this.filePath);

        this.mediaCodec.start();

        try {
            audioRecord.startRecording();
        } catch (Exception e) {
            Log.w(TAG, e);
            mediaCodec.release();
            throw new IOException(e);
        }

        status = "recording";
        startThread();
    }

    @Override
    public void pause() {
        status = "paused";
        peakPower = -120;
        averagePower = -120;
        audioRecord.stop();
        recordingThread = null;
    }

    @Override
    public void resume() {
        status = "recording";
        audioRecord.startRecording();
        startThread();
    }

    @Override
    public HashMap<String, Object> stop() {
        status = "stopped";

        // Return Recording Object
        HashMap<String, Object> currentResult = new HashMap<>();
        currentResult.put("duration", getDuration() * 1000);
        currentResult.put("path", filePath);
        currentResult.put("audioFormat", extension);
        currentResult.put("peakPower", peakPower);
        currentResult.put("averagePower", averagePower);
        currentResult.put("isMeteringEnabled", true);
        currentResult.put("status", status);


        resetRecorder();
        recordingThread = null;

        mediaCodec.stop();
        audioRecord.stop();

        mediaCodec.release();
        audioRecord.release();

        try {
            fileOutputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return currentResult;
    }

    @Override
    public int getDuration() {
        long duration = dataSize / (sampleRate * 2 * 1);
        return (int) duration;
    }

    @Override
    public double getPeakPower() {
        return peakPower;
    }

    @Override
    public double getAveragePower() {
        return averagePower;
    }

    @Override
    public String getFilePath() {
        return filePath;
    }

    @Override
    public void run() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        ByteBuffer[] codecInputBuffers = mediaCodec.getInputBuffers();
        ByteBuffer[] codecOutputBuffers = mediaCodec.getOutputBuffers();

        while (status == "recording") {
            boolean success = handleCodecInput(audioRecord, mediaCodec, codecInputBuffers, Thread.currentThread().isAlive());
            if (success) {
                try {
                    handleCodecOutput(mediaCodec, codecOutputBuffers, bufferInfo, fileOutputStream);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void startThread() {
        recordingThread = new Thread(this, "Audio Processing Thread");
        recordingThread.start();
    }

    private void resetRecorder() {
        peakPower = -120;
        averagePower = -120;
        dataSize = 0;
        currentFilledDataSize = 0;
    }

    private boolean handleCodecInput(AudioRecord audioRecord,
                                     MediaCodec mediaCodec, ByteBuffer[] codecInputBuffers,
                                     boolean running) {
        byte[] audioRecordData = new byte[bufferSize];
        int length = audioRecord.read(audioRecordData, 0, audioRecordData.length);
        dataSize += audioRecordData.length;
        updatePowers(audioRecordData);
        updateListener(audioRecordData);

        if (length == AudioRecord.ERROR_BAD_VALUE ||
                length == AudioRecord.ERROR_INVALID_OPERATION ||
                length != bufferSize) {

            if (length != bufferSize) {
                Log.d(TAG, "length != bufferSize");
                return false;
            }
        }

        int codecInputBufferIndex = mediaCodec.dequeueInputBuffer(10 * 1000);

        if (codecInputBufferIndex >= 0) {
            ByteBuffer codecBuffer = codecInputBuffers[codecInputBufferIndex];
            codecBuffer.clear();
            codecBuffer.put(audioRecordData);
            mediaCodec.queueInputBuffer(codecInputBufferIndex, 0, length, 0, running ? 0 : MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }

        return true;
    }

    private void handleCodecOutput(MediaCodec mediaCodec,
                                   ByteBuffer[] codecOutputBuffers,
                                   MediaCodec.BufferInfo bufferInfo,
                                   OutputStream outputStream)
            throws IOException {
        int codecOutputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);

        while (codecOutputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
            if (codecOutputBufferIndex >= 0) {
                ByteBuffer encoderOutputBuffer = codecOutputBuffers[codecOutputBufferIndex];

                encoderOutputBuffer.position(bufferInfo.offset);
                encoderOutputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                    byte[] header = createAdtsHeader(bufferInfo.size - bufferInfo.offset);


                    outputStream.write(header);

                    byte[] data = new byte[encoderOutputBuffer.remaining()];
                    encoderOutputBuffer.get(data);
                    outputStream.write(data);
                }

                encoderOutputBuffer.clear();

                mediaCodec.releaseOutputBuffer(codecOutputBufferIndex, false);
            } else if (codecOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                codecOutputBuffers = mediaCodec.getOutputBuffers();
            }

            codecOutputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);
        }
    }

    private void updateListener(byte[] bdata) {
        if (recordingListener != null) {
            short[] data = byte2short(bdata);
            if (currentFilledDataSize == 0) {
                int length = Math.min(data.length, SAMPLE_BUFFER_SIZE);
                sampleDataBuffer.put(data, 0, length);
                currentFilledDataSize = length;
            } else if (currentFilledDataSize < SAMPLE_BUFFER_SIZE) {
                int length = Math.min(data.length, SAMPLE_BUFFER_SIZE - currentFilledDataSize);
                sampleDataBuffer.put(data, 0, length);
                currentFilledDataSize += length;
            }

            if (currentFilledDataSize == SAMPLE_BUFFER_SIZE && sampleDataBuffer.hasArray()) {
                short[] sampleData = sampleDataBuffer.array();
                double[] audioData = new double[sampleData.length];
                for (int i = 0; i < sampleData.length; i++) {
                    audioData[i] = sampleData[i] / 32767.0;
                }
                recordingListener.onAudioData(audioData);

                currentFilledDataSize = 0;
                sampleDataBuffer.clear();
            }
        }
    }

    private void updatePowers(byte[] bdata) {
        short[] data = byte2short(bdata);
        String[] escapeStatusList = new String[]{"paused", "stopped", "initialized", "unset"};

        double sum = 0;
        double max = 0;
        // Take the buffer content, perform a square sum operation
        for (int i = 0; i < data.length; i++) {
            double square = data[i] * data[i];
            sum += square;
            if (square > max) {
                max = square;
            }
        }
        // The sum of the squares divided by the total length of the data gives the volume.
        double mean = sum / (double) data.length;

        if (mean == 0 || Arrays.asList(escapeStatusList).contains(status)) {
            averagePower = -120; // to match iOS silent case
            peakPower = -120;
        } else {
            // iOS factor : to match iOS power level
            double iOSFactor = 0.3;
            averagePower = 10 * Math.log(mean / (32767.0 * 32767.0)) * iOSFactor;
            peakPower = 10 * Math.log(max / (32767.0 * 32767.0)) * iOSFactor;
        }
        // Log.d(LOG_NAME, "Peak: " + mPeakPower + " average: "+ mAveragePower);
    }

    private short[] byte2short(byte[] bData) {
        short[] out = new short[bData.length / 2];
        ByteBuffer.wrap(bData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
        return out;
    }

    private byte[] createAdtsHeader(int length) {
        int frameLength = length + 7;
        byte[] adtsHeader = new byte[7];

        adtsHeader[0] = (byte) 0xFF; // Sync Word
        adtsHeader[1] = (byte) 0xF1; // MPEG-4, Layer (0), No CRC
        adtsHeader[2] = (byte) ((MediaCodecInfo.CodecProfileLevel.AACObjectLC - 1) << 6);
        adtsHeader[2] |= (((byte) SAMPLE_RATE_INDEX) << 2);
        adtsHeader[2] |= (((byte) CHANNELS) >> 2);
        adtsHeader[3] = (byte) (((CHANNELS & 3) << 6) | ((frameLength >> 11) & 0x03));
        adtsHeader[4] = (byte) ((frameLength >> 3) & 0xFF);
        adtsHeader[5] = (byte) (((frameLength & 0x07) << 5) | 0x1f);
        adtsHeader[6] = (byte) 0xFC;

        return adtsHeader;
    }

    private AudioRecord createAudioRecord(int bufferSize) {
        AudioRecord audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize * 10);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.d(TAG, "Unable to initialize AudioRecord");
            throw new RuntimeException("Unable to initialize AudioRecord");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                android.media.audiofx.NoiseSuppressor noiseSuppressor = android.media.audiofx.NoiseSuppressor
                        .create(audioRecord.getAudioSessionId());
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);
                }
            }
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                android.media.audiofx.AutomaticGainControl automaticGainControl = android.media.audiofx.AutomaticGainControl
                        .create(audioRecord.getAudioSessionId());
                if (automaticGainControl != null) {
                    automaticGainControl.setEnabled(true);
                }
            }
        }


        return audioRecord;
    }

    private MediaCodec createMediaCodec(int bufferSize) throws IOException {
        MediaCodec mediaCodec = MediaCodec.createEncoderByType("audio/mp4a-latm");
        MediaFormat mediaFormat = new MediaFormat();

        mediaFormat.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
        mediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
        mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, CHANNELS);
        mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        mediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);

        try {
            mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            Log.w(TAG, e);
            mediaCodec.release();
            throw new IOException(e);
        }

        return mediaCodec;
    }
}
