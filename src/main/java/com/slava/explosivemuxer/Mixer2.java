package com.slava.explosivemuxer;

import com.slava.explosivemuxer.encoder.MediaEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Mixer2 {
    private final MediaEncoder mEncoder;
    private final int mFreq;
    private final int MAX_SAMPLES = 65536;
    private static int BYTES_IN_SAMPLE = 2;
    private static int CHANNELS_IN_STEREO = 2;
    private static int OUTBUFF_SAMPLES = 512;
    private static int OUTBUFF_BYTES = OUTBUFF_SAMPLES * BYTES_IN_SAMPLE;
    private final byte[] mArrayBuffer = new byte[MAX_SAMPLES * BYTES_IN_SAMPLE];
    private long mWrittenSamples;
    private boolean mIsStopped = false;
    private List<SourceData> mSources = new ArrayList<>();

    Mixer2(MediaEncoder encoder, int freq) {
        mEncoder = encoder;
        mFreq = freq;
    }

    synchronized void put(ByteBuffer data, int nBytes, int freq, int id) {
        if (mIsStopped) return;
        int len = (int) ((nBytes / BYTES_IN_SAMPLE));// количество сэмплов для записи (по 2 байта)
        SourceData source = mSources.get(id);
        int startBuff = mSources.get(id).mBufferedSamples;

        //микширование
        ShortBuffer srcb = data.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        ShortBuffer dstb = ByteBuffer.wrap(mArrayBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        for (int i = 0; i < len; i++) {
            int dstPosition = startBuff + i;//(int) (i * compression);
            int srcSample = (int) (srcb.get(i) * source.mVolume);
            int dstSampleOld = dstb.get(dstPosition);
            int dstSampleNew = srcSample + dstSampleOld;
            //ограничение амплитуды выходного сигнала
            if (dstSampleNew < Short.MIN_VALUE) dstSampleNew = Short.MIN_VALUE;
            if (dstSampleNew > Short.MAX_VALUE) dstSampleNew = Short.MAX_VALUE;
            dstb.put(dstPosition, (short) dstSampleNew);
        }

        //вычисление границ буффера, пригодного для отправки в кодер
        source.mBufferedSamples += len;
        int buffMin = Integer.MAX_VALUE; //до этого индекса аудиоданные со всех источников смикшированы полностью
        int buffMax = 0;                 // до этого - частично
        for (SourceData s : mSources) {
            int ns = s.mBufferedSamples;
            if (ns < buffMin) buffMin = ns;
            if (ns > buffMax) buffMax = ns;
        }

        while (buffMin >= OUTBUFF_SAMPLES) {
            //отправка в кодек куска данных с начала буффера
            ByteBuffer outByteBuffer = ByteBuffer.wrap(mArrayBuffer);
            outByteBuffer.position(OUTBUFF_BYTES);
            outByteBuffer.flip();
            mEncoder.encode(outByteBuffer, OUTBUFF_BYTES);
            mEncoder.frameAvailableSoon();
            mWrittenSamples += OUTBUFF_SAMPLES;

            int copyLenBytes = buffMax * BYTES_IN_SAMPLE - OUTBUFF_BYTES;
            //сдвиг буффера влево
            if (copyLenBytes > 0)
                System.arraycopy(mArrayBuffer, OUTBUFF_BYTES, mArrayBuffer, 0, copyLenBytes);
            //очистка прежде занятого фрагмента буффера
            Arrays.fill(mArrayBuffer, buffMax * BYTES_IN_SAMPLE - OUTBUFF_BYTES, buffMax * BYTES_IN_SAMPLE, (byte) 0);
            for (SourceData s : mSources) s.mBufferedSamples -= OUTBUFF_SAMPLES;
            buffMin -= OUTBUFF_SAMPLES;
            buffMax -= OUTBUFF_SAMPLES;
        }
    }

    void stop() {
        mIsStopped = true;
        int buffMin = Integer.MAX_VALUE;
        for (SourceData s : mSources) {
            int ns = s.mBufferedSamples;
            if (ns < buffMin) buffMin = ns;
        }

        while (buffMin >= OUTBUFF_SAMPLES) {
            //отправка в кодек куска данных с начала буффера
            ByteBuffer outByteBuffer = ByteBuffer.wrap(mArrayBuffer);
            outByteBuffer.position(OUTBUFF_BYTES);
            outByteBuffer.flip();
            mEncoder.encode(outByteBuffer, OUTBUFF_BYTES);
        }
    }

    boolean isNeedPause(int srcId) {
        return mSources.get(srcId).mBufferedSamples > 4096;
    }

    boolean isEncoderReady() {
        return mEncoder.isReady();
    }

    public long getTimeMs() {
        return mWrittenSamples * 1000 / mFreq;
    }

    void addSource(AudioSource source, float initVolume) {
        mSources.add(new SourceData(source.setMixer(this, mSources.size()), initVolume));

    }

    void setVolume(float volume, int sourceId) {
        mSources.get(sourceId).mVolume = volume;
    }

    void startAllSources() {
        for (SourceData source : mSources) source.mSource.start();
    }

    class SourceData {
        AudioSource mSource;
        int mBufferedSamples;
        float mVolume;

        SourceData(AudioSource source, float vol) {
            mSource = source;
            mVolume = vol;
        }
    }
}
