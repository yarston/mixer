package com.slava.explosivemuxer;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.nio.ByteBuffer;

public class MicThread extends AudioSource {

    private static final String TAG = "MediaAudioEncoder";

    private static final int SAMPLE_RATE = 44100;    // 44.1[KHz] is only setting guaranteed to be available on all devices.
    private static final int BIT_RATE = 64000;
    public static final int SAMPLES_PER_FRAME = 1024;    // AAC, bytes/frame/channel
    public static final int FRAMES_PER_BUFFER = 25;    // AAC, frame/buffer/sec
    private static final int[] AUDIO_SOURCES = new int[]{
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
    };

    @Override
    public void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        try {
            final int min_buffer_size = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            int buffer_size = SAMPLES_PER_FRAME * FRAMES_PER_BUFFER;
            if (buffer_size < min_buffer_size) buffer_size = ((min_buffer_size / SAMPLES_PER_FRAME) + 1) * SAMPLES_PER_FRAME * 2;

            AudioRecord audioRecord = null;
            for (final int source : AUDIO_SOURCES) {
                try {
                    audioRecord = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, buffer_size);
                    if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) audioRecord = null;
                } catch (final Exception e) {
                    audioRecord = null;
                }
                if (audioRecord != null) break;
            }
            if (audioRecord != null) {
                try {
                    //if (isRunning) {
                    if (mMixer.isEncoderReady()) {
                        Log.v(TAG, "AudioThread:start audio recording");
                        final ByteBuffer buf = ByteBuffer.allocateDirect(SAMPLES_PER_FRAME);//Endianess для миксера
                        int readBytes;
                        audioRecord.startRecording();
                        try {
                            loop:
                            while (mMixer.isEncoderReady()) {
                                while (mMixer.isNeedPause(mId)) {
                                    try {
                                        sleep(5);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                    if (!mMixer.isEncoderReady()) break loop;
                                }
                                // read audio data from internal mic
                                buf.clear();
                                readBytes = audioRecord.read(buf, SAMPLES_PER_FRAME);
                                if (readBytes > 0) {
                                    // set audio data to encoder
                                    buf.position(readBytes);
                                    buf.flip();

                                    mMixer.put(buf, readBytes, SAMPLE_RATE, mId);
                                }
                            }
                            //mEncoder.frameAvailableSoon();
                        } finally {
                            audioRecord.stop();
                        }
                    }
                } finally {
                    audioRecord.release();
                }
            } else {
                Log.e(TAG, "failed to initialize AudioRecord");
            }
        } catch (final Exception e) {
            Log.e(TAG, "AudioThread#run", e);
        }
        Log.v(TAG, "AudioThread:finished");
    }
}