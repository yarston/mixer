package com.slava.explosivemuxer;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.slava.explosivemuxer.encoder.MediaAudioEncoder;
import com.slava.explosivemuxer.encoder.MediaEncoder;
import com.slava.explosivemuxer.encoder.MediaMuxerWrapper;
import com.slava.explosivemuxer.encoder.MediaVideoEncoder;
import com.slava.explosivemuxer.glutils.CameraGLView;

import java.io.File;
import java.io.IOException;

import butterknife.BindView;
import butterknife.ButterKnife;

import static com.slava.explosivemuxer.glutils.CameraGLView.SCALE_CROP_CENTER;

public class MainActivity extends AppCompatActivity implements SeekBar.OnSeekBarChangeListener {

    //ffmpeg -ss 00:00:10.0 -i 1.mp4 -vcodec copy -c:a mp3 -t 00:01:30.0 out.mp4

    // Необходимо организовать склеивание трех аудиопотоков
    // 1. один аудиопоток идет с микрофона
    // 2. другой из вшитой аудиодорожки(любое мп3)
    // 3. родная дорожка от видео
    // (она должна по звуку выделяться в большую сторону чем остальные)
    // в один аудиопоток и наложить это на видеопоток.
    // Отрендерить на клиенте и пошарить результат в лс телеграмма.
    // Длинна минута. качество hd

    @BindView(R.id.surfaceView1) CameraGLView svCamera;
    @BindView(R.id.surfaceView2) SurfaceView svVideoFile;
    @BindView(R.id.seekBar1) SeekBar sbMic;
    @BindView(R.id.seekBar2) SeekBar sbVideoFile;
    @BindView(R.id.seekBar3) SeekBar sbAudioFile;

    @BindView(R.id.button1) Button bMix;
    @BindView(R.id.button2) Button bShare;
    @BindView(R.id.textView4) TextView tStatus;

    private static final String FILE_PATH_1 = Environment.getExternalStorageDirectory() + "/in_video.mp4";
    private static final String FILE_PATH_2 = Environment.getExternalStorageDirectory() + "/in_audio.mp3";
    private static final String FILE_PATH_OUT = Environment.getExternalStorageDirectory() + "/out.mp4";

    private MediaMuxerWrapper mMuxer;
    private Mixer2 mMixer;

    private Decoder mVideoDecoder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
        tStatus.setText(R.string.staus_ready);
        bMix.setOnClickListener(v-> recording());
        bShare.setOnClickListener(v->shareTelegram());
        svCamera.setVideoSize(1280, 720);
        svCamera.setScaleMode(SCALE_CROP_CENTER);
        sbMic.setOnSeekBarChangeListener(this);
        sbVideoFile.setOnSeekBarChangeListener(this);
        sbAudioFile.setOnSeekBarChangeListener(this);
    }

    float getVolume(SeekBar s) {
        return (float) s.getProgress() / (float) s.getMax();
    }

    void recording() {
        if (mMuxer == null) {
            try {
                mMuxer = new MediaMuxerWrapper(FILE_PATH_OUT);
                new MediaVideoEncoder(mMuxer, mMediaEncoderListener, 1280, 720);
                MediaAudioEncoder audioEncoder = new MediaAudioEncoder(mMuxer, mMediaEncoderListener);

                mMixer = new Mixer2(audioEncoder, 44100);
                mMixer.addSource(new MicThread(), getVolume(sbMic));
                mMixer.addSource(new Decoder(null, FILE_PATH_1, false), getVolume(sbVideoFile));
                mMixer.addSource(new Decoder(null, FILE_PATH_2, false), getVolume(sbAudioFile));

                mMuxer.prepare();
                mMuxer.startRecording();
                mMixer.startAllSources();

                mVideoDecoder = new Decoder(svVideoFile.getHolder().getSurface(), FILE_PATH_1, true);
                mVideoDecoder.start();

                bMix.setText(R.string.stop);
                tStatus.setText(R.string.status_rec);
                new Handler().postDelayed(() -> {
                    if(mMuxer != null) recording();
                    }, 60000);
            } catch (final IOException e) {
                tStatus.setText(R.string.status_fail);
            }
        } else {
            bMix.setText(R.string.start);
            tStatus.setText(R.string.status_stopped);
            bShare.setEnabled(true);
            mVideoDecoder.requestStop();
            mMixer.stop();
            mMuxer.stopRecording();
            mMuxer = null;
            mMixer = null;
        }
    }

    void shareTelegram() {
        final String appName = "org.telegram.messenger";
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("video/mp4");
        i.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(new File(FILE_PATH_OUT)));
        i.setPackage("org.telegram.messenger");
        i.putExtra(Intent.EXTRA_TEXT, "lol");//
        startActivity(Intent.createChooser(i, "Share with"));
    }

    /**
     * callback methods from encoder
     */
    private final MediaEncoder.MediaEncoderListener mMediaEncoderListener = new MediaEncoder.MediaEncoderListener() {
        @Override
        public void onPrepared(final MediaEncoder encoder) {
            if (encoder instanceof MediaVideoEncoder)
                svCamera.setVideoEncoder((MediaVideoEncoder) encoder);
        }

        @Override
        public void onStopped(final MediaEncoder encoder) {
            if (encoder instanceof MediaVideoEncoder)
                svCamera.setVideoEncoder(null);
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        svCamera.onResume();
    }

    @Override
    public void onPause() {
        if (mMuxer != null) recording();
        svCamera.onPause();
        super.onPause();
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if(mMixer != null)
        switch (seekBar.getId()) {
            case R.id.seekBar1:
                mMixer.setVolume(getVolume(seekBar), 0);
                break;
            case R.id.seekBar2:
                mMixer.setVolume(getVolume(seekBar), 1);
                break;
            case R.id.seekBar3:
                mMixer.setVolume(getVolume(seekBar), 2);
                break;
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {

    }
}
