package com.slava.explosivemuxer;

import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Decoder extends AudioSource {
    private static final int TIMEOUT_US = 10000;
    private final boolean mIsVideo;
    private int mFPS;
    private MediaExtractor mExtractor;
    private MediaCodec mDecoder;
    private int mSampleRate = 0;

    private static final String VIDEO = "video/";
    private static final String AUDIO = "audio/";
    private static final String TAG = "VideoDecoder";
    private int mChannelsCount;
    BufferInfo info;
    ByteBuffer[] inputBuffers;
    ByteBuffer[] outputBuffers;
    private boolean allowRun = true;
    private long time;
    private int mFramesCounter;

    public Decoder(Surface surface, String filePath, boolean isVideo) {
        mIsVideo = isVideo;
        mExtractor = new MediaExtractor();
        try {
            mExtractor.setDataSource(filePath);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        String seachMime = isVideo ? VIDEO : AUDIO;
        for (int i = 0; i < mExtractor.getTrackCount(); i++) {
            MediaFormat format = mExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith(seachMime)) {
                if (isVideo) {
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                        mFPS = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                    else mFPS = 30;
                } else {
                    mSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    mChannelsCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
                mExtractor.selectTrack(i);
                try {
                    mDecoder = MediaCodec.createDecoderByType(mime);
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
                mDecoder.configure(format, surface, null, 0);
                break;
            }
        }
        if (mDecoder == null) return;
        mDecoder.start();
        info = new BufferInfo();
        inputBuffers = mDecoder.getInputBuffers();
        outputBuffers = mDecoder.getOutputBuffers();
    }

    @Override
    public void run() {
        if (mDecoder == null) return;
        loop:
        while ((mMixer == null && allowRun) || (mMixer != null && mMixer.isEncoderReady())) {
            int inIndex = mDecoder.dequeueInputBuffer(TIMEOUT_US);
            if (inIndex < 0) continue;

            ByteBuffer buffer = inputBuffers[inIndex];

            int sampleSize = mExtractor.readSampleData(buffer, 0);
            if (mExtractor.advance() && sampleSize > 0) {
                mDecoder.queueInputBuffer(inIndex, 0, sampleSize, mExtractor.getSampleTime(), 0);
            } else {
                mDecoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }

            int outIndex = mDecoder.dequeueOutputBuffer(info, TIMEOUT_US);
            switch (outIndex) {
                case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                    Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED");
                    outputBuffers = mDecoder.getOutputBuffers();
                    break;
                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                    break;
                case MediaCodec.INFO_TRY_AGAIN_LATER:
                    Log.d("DecodeActivity", "dequeueOutputBuffer timed out!");
                    break;
                default:
                    if(mMixer != null) {
                        while (mMixer.isNeedPause(mId)) {
                            try {
                                sleep(5);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            if (!mMixer.isEncoderReady()) break loop;
                        }
                        ByteBuffer outBuffer = outputBuffers[outIndex];
                        // Log.v(TAG, "We can't use this buffer but render it due to the API limit, " + outBuffer);
                        final byte[] chunk = new byte[info.size];
                        outBuffer.get(chunk); // Read the buffer all at once
                        outBuffer.clear(); // ** MUST DO!!! OTHERWISE THE NEXT TIME YOU GET THIS SAME BUFFER BAD THINGS WILL HAPPEN
                        //Log.v("AudioMixer", "pull file size:" + info.size + "/" + mWritten + " offset:" + info.offset);
                        ByteBuffer buf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
                        buf.position(info.size);
                        buf.flip();
                        mMixer.put(buf, info.size, mSampleRate, mId);
                    }
                    mDecoder.releaseOutputBuffer(outIndex, true /* Surface init */);
                    if(mIsVideo) {
                        if(time == 0) {
                            time = System.currentTimeMillis();
                        } else {
                            long dt = System.currentTimeMillis() - time;
                             while (dt * mFPS < mFramesCounter * 1000) {
                                try {
                                    sleep(5);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                                dt = System.currentTimeMillis() - time;
                            }
                        }
                        mFramesCounter++;
                    }
                    break;
            }
            // All decoded frames have been rendered, we can stop playing now
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                Log.d(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
                break;
            }
        }

        mDecoder.stop();
        mDecoder.release();
        mDecoder = null;
    }

    public void requestStop() {
        allowRun = false;
    }
}
