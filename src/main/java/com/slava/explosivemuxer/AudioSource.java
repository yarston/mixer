package com.slava.explosivemuxer;

public class AudioSource extends Thread{

    Mixer2 mMixer;
    int mId;

    AudioSource setMixer(Mixer2 mixer, int id) {
        mMixer = mixer;
        mId = id;
        return this;
    }
}
