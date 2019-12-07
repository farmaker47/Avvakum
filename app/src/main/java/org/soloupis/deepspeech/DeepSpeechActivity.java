package org.soloupis.deepspeech;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

import android.os.Handler;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Button;

import android.media.MediaPlayer;

import com.skyfishjy.library.RippleBackground;

import java.io.RandomAccessFile;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;

import org.soloupis.deepspeech.libdeepspeech.DeepSpeechModel;

public class DeepSpeechActivity extends AppCompatActivity {

    DeepSpeechModel _m = null;

    EditText _tfliteModel;
    EditText _audioFile;

    TextView _decodedString;
    TextView _tfliteStatus;

    private Button _startInference, startRecording, stopRecording;
    private HotwordRecorder hotwordRecorder;
    private RippleBackground rippleBackground;
    private ImageButton centerImage;

    private Timer t;

    final int BEAM_WIDTH = 40;
    final float LM_ALPHA = 0.75f;
    final float LM_BETA = 1.85f;

    private char readLEChar(RandomAccessFile f) throws IOException {
        byte b1 = f.readByte();
        byte b2 = f.readByte();
        return (char) ((b2 << 8) | b1);
    }

    private int readLEInt(RandomAccessFile f) throws IOException {
        byte b1 = f.readByte();
        byte b2 = f.readByte();
        byte b3 = f.readByte();
        byte b4 = f.readByte();
        return (int) ((b1 & 0xFF) | (b2 & 0xFF) << 8 | (b3 & 0xFF) << 16 | (b4 & 0xFF) << 24);
    }

    private void newModel(String tfliteModel) {
        //this._tfliteStatus.setText("Creating model");
        if (this._m == null) {
            this._m = new DeepSpeechModel(tfliteModel, BEAM_WIDTH);
        }
    }

    private void doInference(final String audioFile) {
        final long[] inferenceExecTime = {0};

        /*this._startInference.setEnabled(false);*/

        this.newModel("/sdcard/deepspeech3/output_graph.tflite");

        //this._tfliteStatus.setText("Extracting audio features ...");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    RandomAccessFile wave = new RandomAccessFile(audioFile, "r");

                    wave.seek(20);
                    char audioFormat = readLEChar(wave);
                    assert (audioFormat == 1); // 1 is PCM
                    // tv_audioFormat.setText("audioFormat=" + (audioFormat == 1 ? "PCM" : "!PCM"));

                    wave.seek(22);
                    char numChannels = readLEChar(wave);
                    assert (numChannels == 1); // MONO
                    // tv_numChannels.setText("numChannels=" + (numChannels == 1 ? "MONO" : "!MONO"));

                    wave.seek(24);
                    int sampleRate = readLEInt(wave);
                    assert (sampleRate == _m.sampleRate()); // desired sample rate
                    // tv_sampleRate.setText("sampleRate=" + (sampleRate == 16000 ? "16kHz" : "!16kHz"));

                    wave.seek(34);
                    char bitsPerSample = readLEChar(wave);
                    assert (bitsPerSample == 16); // 16 bits per sample
                    // tv_bitsPerSample.setText("bitsPerSample=" + (bitsPerSample == 16 ? "16-bits" : "!16-bits" ));

                    wave.seek(40);
                    int bufferSize = readLEInt(wave);
                    assert (bufferSize > 0);
                    // tv_bufferSize.setText("bufferSize=" + bufferSize);

                    wave.seek(44);
                    byte[] bytes = new byte[bufferSize];
                    wave.readFully(bytes);

                    short[] shorts = new short[bytes.length / 2];
                    // to turn bytes to shorts as either big endian or little endian.
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);

                    //this._tfliteStatus.setText("Running inference ...");

                    long inferenceStartTime = System.currentTimeMillis();

                    String decoded = _m.stt(shorts, shorts.length);

                    inferenceExecTime[0] = System.currentTimeMillis() - inferenceStartTime;

                    _decodedString.setText("\"..." + decoded + "...\"");

                } catch (FileNotFoundException ex) {

                } catch (IOException ex) {

                } finally {

                }

                _tfliteStatus.setText("Finished! Took " + inferenceExecTime[0] + "ms");
            }
        });



        /*this._startInference.setEnabled(true);*/
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_deep_speech);

        this._decodedString = findViewById(R.id.decodedString);
        this._tfliteStatus = findViewById(R.id.tfliteStatus);

        this._tfliteModel = findViewById(R.id.tfliteModel);
        this._audioFile = findViewById(R.id.audioFile);

        /*this._tfliteModel.setText("/sdcard/deepspeech2/output_graph.tflite");*/
        this._tfliteStatus.setText("Ready! Press mic button...");

        /*this._audioFile.setText("/sdcard/deepspeech2/soloupis.wav");*/

        /*this._startInference = findViewById(R.id.btnStartInference);*/

        hotwordRecorder = new HotwordRecorder("hotKey", 5);

        /*startRecording = findViewById(R.id.btnStartRecording);
        startRecording.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                hotwordRecorder.startRecording();
            }
        });
        stopRecording = findViewById(R.id.btnStopRecording);

        stopRecording.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                hotwordRecorder.stopRecording();
                hotwordRecorder.writeWav();
                *//*if(hotwordRecorder.validateSample()){}*//*
            }
        });*/

        rippleBackground = findViewById(R.id.content);
        centerImage = findViewById(R.id.centerImage);

        //Declare the timer
        t = new Timer();

        centerImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!rippleBackground.isRippleAnimationRunning()) {
                    rippleBackground.startRippleAnimation();
                    centerImage.setImageDrawable(getResources().getDrawable(R.drawable.ic_pause_white_56dp));
                    _tfliteStatus.setText("Speak to the microphone...");
                    _decodedString.setText("");
                    hotwordRecorder.startRecording();

                    //Set the schedule function and rate
                    t.scheduleAtFixedRate(new TimerTask() {
                                              @Override
                                              public void run() {
                                                  //Called each time of some milliseconds(the period parameter)
                                                  hotwordRecorder.stopRecording();
                                                  hotwordRecorder.writeWav();
                                                  doInference("/sdcard/deepspeech3/soloupis.wav");
                                                  //hotwordRecorder.startRecording();
                                              }
                                          },
                            //Set how long before to start calling the TimerTask (in milliseconds)
                            0,
                            //Set the amount of time between each execution (in milliseconds)
                            2000);

                } else {
                    rippleBackground.stopRippleAnimation();
                    centerImage.setImageDrawable(getResources().getDrawable(R.drawable.ic_mic_none_white_56dp));
                    hotwordRecorder.stopRecording();
                    //_tfliteStatus.setText("Wait for the transcription...");
                    hotwordRecorder.writeWav();
                    doInference("/sdcard/deepspeech3/soloupis.wav");

                    Handler handler = new Handler();
                    handler.postDelayed(new Runnable() {
                        public void run() {
                            // Actions to do after 500 milliseconds
                            //playAudioFile();
                            //doInference("/sdcard/deepspeech3/soloupis.wav");

                        }
                    }, 100);

                    //Finally stop timer
                    t.cancel();
                }
            }
        });

    }

    /*public void onClick_inference_handler(View v) {
        this.playAudioFile();
        this.doInference(this._audioFile.getText().toString());
    }*/

    public void playAudioFile() {
        try {
            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource("/sdcard/deepspeech3/soloupis.wav");
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (IOException ex) {

        }
    }

    public void onClick_audio_handler(View v) {
        this.playAudioFile();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (this._m != null) {
            this._m.freeModel();
        }
    }
}