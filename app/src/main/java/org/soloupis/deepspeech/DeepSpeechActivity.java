package org.soloupis.deepspeech;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.AsyncTask;
import android.os.Bundle;

import android.os.Environment;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Button;

import android.media.MediaPlayer;
import android.widget.Toast;

import com.skyfishjy.library.RippleBackground;

import java.io.File;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import org.soloupis.deepspeech.libdeepspeech.DeepSpeechModel;

public class DeepSpeechActivity extends AppCompatActivity implements WordRecorder.HotwordSpeechListener {

    DeepSpeechModel _m = null;

    EditText _tfliteModel;
    EditText _audioFile;

    TextView _decodedString;
    TextView _tfliteStatus;

    private Button _startInference, startRecording, stopRecording;
    private WordRecorder hotwordRecorder;
    private RippleBackground rippleBackground;
    private ImageButton centerImage, centerImageGoogle;

    private String wholeSentence, inferenceString;
    private AudioManager am;

    final int BEAM_WIDTH = 40;/*
    final float LM_ALPHA = 0.75f;
    final float LM_BETA = 1.85f;*/

    private final int REQ_CODE_SPEECH_INPUT = 100;

    //Soundpool
    SoundPool sp;
    int explosion = 0;

    //Vad
    private Vad mVad;

    //listener
    private boolean listenerBoolDone = false;

    //Permissions
    int PERMISSION_ALL = 123;
    String[] PERMISSIONS = {
            Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        setContentView(R.layout.activity_deep_speech);

        _decodedString = findViewById(R.id.decodedString);
        _tfliteStatus = findViewById(R.id.tfliteStatus);
        _tfliteModel = findViewById(R.id.tfliteModel);
        this._audioFile = findViewById(R.id.audioFile);
        rippleBackground = findViewById(R.id.content);
        centerImage = findViewById(R.id.centerImage);

        _tfliteStatus.setText("Ready! Press mic button...");
        mVad = new Vad();
        int arxiVad = mVad.start();
        hotwordRecorder = new WordRecorder("hotKey", 0, DeepSpeechActivity.this, mVad, this);

        inferenceString = "/sdcard/deepspeech4/soloupis.wav";
        newModel("/sdcard/deepspeech4/output_graph.tflite");

        //Noise suppression
        ((AudioManager) getSystemService(Context.AUDIO_SERVICE)).setParameters("noise_suppression=on");

        centerImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                if (!rippleBackground.isRippleAnimationRunning()) {
                    rippleBackground.startRippleAnimation();
                    centerImage.setImageDrawable(getResources().getDrawable(R.drawable.ic_pause_white_56dp));
                    _tfliteStatus.setText("Speak to the microphone...");
                    _decodedString.setText("");
                    wholeSentence = "";
                    hotwordRecorder.startRecording();

                    /*//Declare the timer
                    t = new Timer();
                    //Set the schedule function and rate
                    t.scheduleAtFixedRate(new TimerTask() {
                                              @Override
                                              public void run() {
                                                  //Called each time of some milliseconds(the period parameter)
                                                  *//*hotwordRecorder.stopRecording();
                                                  hotwordRecorder.startRecording();
                                                  AsyncTaskRunner runner = new AsyncTaskRunner();
                                                  runner.execute(inferenceString);*//*
                                              }
                                          },
                            //Set how long before to start calling the TimerTask (in milliseconds)
                            4000,
                            //Set the amount of time between each execution (in milliseconds)
                            4000);*/

                } else {
                    rippleBackground.stopRippleAnimation();
                    centerImage.setImageDrawable(getResources().getDrawable(R.drawable.ic_mic_none_white_56dp));
                    hotwordRecorder.stopRecording();
                    AsyncTaskRunner runner = new AsyncTaskRunner();
                    runner.execute(inferenceString);

                    generateTxtOnSD("WholeTranscription.txt", wholeSentence);

                    /*//Finally stop timer
                    t.cancel();*/

                }
            }
        });

        findViewById(R.id.centerImageGoogle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                _decodedString.setText("");
                promptSpeechInput();
            }
        });

        //SoundPool
        sp = new SoundPool(1, AudioManager.STREAM_MUSIC, 0);

        explosion = sp.load(inferenceString, 0);
        if (explosion != 0) {

            sp.play(explosion, 1, 1, 0, 0, 1.0f);
        }

        //Check for permissions
        initialize();
    }

    public static boolean hasPermissions(Context context, String... permissions) {
        if (context != null && permissions != null) {
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;

                }
            }
        }
        return true;
    }

    private void initialize() {

        if (!hasPermissions(this, PERMISSIONS)) {
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSION_ALL);
        }

    }

    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    }

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
        if (this._m == null) {
            this._m = new DeepSpeechModel(tfliteModel, BEAM_WIDTH);
        }
    }

    private void doInference(final String audioFile) {
        final long[] inferenceExecTime = {0};

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

            Log.i("BYTES", String.valueOf(bytes.length));

            short[] shorts = new short[bytes.length / 2];
            // to turn bytes to shorts as either big endian or little endian.
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);

            long inferenceStartTime = System.currentTimeMillis();

            Log.i("SHORTS", String.valueOf(shorts.length));

            wholeSentence += _m.stt(shorts, shorts.length) + ". ";

            inferenceExecTime[0] = System.currentTimeMillis() - inferenceStartTime;

        } catch (FileNotFoundException ex) {

        } catch (IOException ex) {

        } finally {

        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                _decodedString.setText("\"..." + wholeSentence + " ...\"");
                _tfliteStatus.setText("Finished! Took " + inferenceExecTime[0] + "ms");
            }
        });
    }

    public void playAudioFile() {
        try {
            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(inferenceString);
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

    /**
     * Showing google speech input dialog
     */
    private void promptSpeechInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 20000);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 20000);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                getString(R.string.speech_prompt));
        try {
            startActivityForResult(intent, REQ_CODE_SPEECH_INPUT);
        } catch (ActivityNotFoundException a) {
            Toast.makeText(getApplicationContext(),
                    getString(R.string.speech_not_supported),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Receiving speech input
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQ_CODE_SPEECH_INPUT: {
                if (resultCode == RESULT_OK && null != data) {

                    ArrayList<String> result = data
                            .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                    _decodedString.setText(result.get(0));
                }
                break;
            }

        }
    }

    @Override
    public void onSpeechChange(int speechInt) {

        if (speechInt == 1234) {
            listenerBoolDone = true;
        } else {
            listenerBoolDone = false;
            //Log.e("DeepspeechActivity",String.valueOf(speechInt));
        }

        if (listenerBoolDone) {
            //Log.e("DeepspeechActivity",String.valueOf(speechInt));
            hotwordRecorder.stopRecording();
            //animation
            //rippleBackground.stopRippleAnimation();
            //centerImage.setImageDrawable(getResources().getDrawable(R.drawable.ic_mic_none_white_56dp));
            hotwordRecorder.startRecording();

            AsyncTaskRunner runner = new AsyncTaskRunner();
            runner.execute(inferenceString);
        }

    }

    //AsyncTask for WriteWav
    private class AsyncTaskRunner extends AsyncTask<String, String, String> {

        @Override
        protected String doInBackground(String... strings) {

            doInference(strings[0]);

            return null;
        }
    }

    //Write .txt file
    private void generateTxtOnSD(String sFileName, String sBody) {
        try {
            File root = new File(Environment.getExternalStorageDirectory(), "deepspeech4");
            if (!root.exists()) {
                root.mkdirs();
            }
            File gpxfile = new File(root, sFileName);
            FileWriter writer = new FileWriter(gpxfile);
            writer.append(sBody);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}