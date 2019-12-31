package org.soloupis.deepspeech;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Hotword recorder.
 * <p>
 * Expected flow:
 * 1. Create HotwordRecorder()
 * 2. Call startRecording()
 * 3. Call stopRecording()
 * 4. Call validateSample()
 * 5. Call writeWav()
 * 6. Repeat 2-5 until desired number of samples is reached.
 * 7. Call writeConfig()
 */
public class HotwordRecorder {
    private int AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION;
    private int CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO;
    private int SAMPLE_RATE = 16000;
    private int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);
    private AudioFormat AUDIO_FORMAT = new AudioFormat.Builder().setEncoding(ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_MASK)
            .build();

    private ByteArrayOutputStream mPcmStream;
    private AudioRecord mRecorder,mRecorderVad;
    private boolean mRecording;
    private Thread mThread,mVadThread;
    private String mHotwordKey;
    private double[] mSampleLengths;
    private int mSamplesTaken;
    private Context mContext;

    //Vad and silence
    private Vad mVad;
    private boolean done = false;
    //private boolean cancelled;
    private int mMinimumVoice = 150;
    private int mMaximumSilence = 500;
    private int mUpperLimit = 10;
    static final int FRAME_SIZE = 80;

    /**
     * Hotword recording constructor.
     *
     * @param key              Hotword key
     * @param numberRecordings Number of recordings to be taken
     */
    public HotwordRecorder(String key, int numberRecordings, Context context, Vad vad) {
        mHotwordKey = key;
        mPcmStream = new ByteArrayOutputStream();
        mRecording = false;
        mSampleLengths = new double[numberRecordings];
        mSamplesTaken = 0;
        mContext = context;
        mVad = vad;
    }

    /**
     * Start the recording process.
     */
    public void startRecording() {

        mRecorder = new AudioRecord.Builder().setAudioSource(AUDIO_SOURCE)
                .setAudioFormat(AUDIO_FORMAT)
                .setBufferSizeInBytes(BUFFER_SIZE)
                .build();

        mRecorderVad = new AudioRecord.Builder().setAudioSource(AUDIO_SOURCE)
                .setAudioFormat(AUDIO_FORMAT)
                .setBufferSizeInBytes(BUFFER_SIZE)
                .build();

        mVad.start();
        done= false;
        mRecorder.startRecording();
        mRecorderVad.startRecording();
        mRecording = true;

        /*mThread = new Thread(readAudio);
        mThread.start();*/

        mVadThread = new Thread(readVad);
        mVadThread.start();
    }

    /**
     * Stop the recording process.
     */
    public void stopRecording() {
        if (mRecorder != null && mRecorder.getState() == AudioRecord.STATE_INITIALIZED) {
            mRecording = false;
            mRecorder.stop();

            mVad.stop();
            mRecorderVad.stop();
            done = true;
            Log.i("STREAM_PCM", String.valueOf(mPcmStream.size()));

            AsyncTaskRunner runner = new AsyncTaskRunner();
            runner.execute(mPcmStream);

            mPcmStream = new ByteArrayOutputStream();

        }
    }

    /**
     * Read audio from the audio recorder stream.
     */
    private Runnable readAudio =
            new Runnable() {
                public void run() {
                    int readBytes;
                    short[] buffer = new short[BUFFER_SIZE];

                    while (mRecording) {
                        readBytes = mRecorder.read(buffer, 0, BUFFER_SIZE);

                        //Higher volume of microphone
                        //https://stackoverflow.com/questions/25441166/how-to-adjust-microphone-sensitivity-while-recording-audio-in-android
                        if (readBytes > 0) {
                            for (int i = 0; i < readBytes; ++i) {
                                buffer[i] = (short) Math.min((int) (buffer[i] * 6.0), (int) Short.MAX_VALUE);
                            }
                        }


                        if (readBytes != AudioRecord.ERROR_INVALID_OPERATION) {
                            for (short s : buffer) {
                                writeShort(mPcmStream, s);
                            }
                        }
                    }

                    /*mRecorder.release();
                    mRecorder = null;*/

                }
            };

    private Runnable readVad =
            new Runnable() {
                public void run() {
                    try {
                        int vad = 0;
                        boolean finishedvoice = false;
                        boolean touchedvoice = false;
                        boolean touchedsilence = false;
                        boolean raisenovoice = false;
                        long samplesvoice = 0;
                        long samplessilence = 0;
                        long dtantes = System.currentTimeMillis();
                        long dtantesmili = System.currentTimeMillis();

                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

                        while (mRecording) {
                            int nshorts = 0;

                            short[] mBuftemp = new short[FRAME_SIZE * 1 * 2];
                            nshorts = mRecorderVad.read(mBuftemp, 0, mBuftemp.length);

                            vad = mVad.feed(mBuftemp, nshorts);

                            long dtdepois = System.currentTimeMillis();

                            if (vad == 0) {
                                if (touchedvoice) {
                                    samplessilence += dtdepois - dtantesmili;
                                    if (samplessilence > mMaximumSilence) touchedsilence = true;
                                }
                            } else { // vad == 1 => Active voice
                                samplesvoice += dtdepois - dtantesmili;
                                if (samplesvoice > mMinimumVoice) touchedvoice = true;

                                for (int i = 0; i < mBuftemp.length; ++i) {
                                    mBuftemp[i] *= 5.0;
                                }
                            }
                            dtantesmili = dtdepois;

                            if (touchedvoice && touchedsilence)
                                finishedvoice = true;

                            if (finishedvoice) {
                                done = true;
                                Log.e("FINISHED_VOICE","FINISHED_VOICE");
                            }

                            if ((dtdepois - dtantes) / 1000 > mUpperLimit) {
                                done = true;
                                if (touchedvoice) {
                                    Log.e("TOUCHED_VOICE","TOUCHED_VOICE");
                                } else {
                                    raisenovoice = true;
                                }
                            }

                            if (nshorts <= 0)
                                break;
                        }

                        /*mRecorderVad.release();*/
                        /*mRecorderVad = null;*/



            /*mVad.stop();
            recorder.stop();
            recorder.release();*/

                        if (raisenovoice)
                            Log.e("RAISED_NO_VOICE","RAISED_NO_VOICE");
            /*if (cancelled) {
                cancelled = false;
                Log.e("CANCELED","CANCELED");
                return;
            }*/

                    } catch (Exception exc) {
                        String error = String.format("General audio error %s", exc.getMessage());
                        Log.e("GENERAL_ERROR","GENERAL_ERROR");
                        exc.printStackTrace();
                    }

                }
            };

    /*private void vadAndSilence() {

        try {
            int vad = 0;
            boolean finishedvoice = false;
            boolean touchedvoice = false;
            boolean touchedsilence = false;
            boolean raisenovoice = false;
            long samplesvoice = 0;
            long samplessilence = 0;
            long dtantes = System.currentTimeMillis();
            long dtantesmili = System.currentTimeMillis();

            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

            while (!this.done) {
                int nshorts = 0;

                short[] mBuftemp = new short[FRAME_SIZE * 1 * 2];
                nshorts = mRecorder.read(mBuftemp, 0, mBuftemp.length);

                vad = mVad.feed(mBuftemp, nshorts);

                long dtdepois = System.currentTimeMillis();

                if (vad == 0) {
                    if (touchedvoice) {
                        samplessilence += dtdepois - dtantesmili;
                        if (samplessilence > mMaximumSilence) touchedsilence = true;
                    }
                } else { // vad == 1 => Active voice
                    samplesvoice += dtdepois - dtantesmili;
                    if (samplesvoice > mMinimumVoice) touchedvoice = true;

                    for (int i = 0; i < mBuftemp.length; ++i) {
                        mBuftemp[i] *= 5.0;
                    }
                }
                dtantesmili = dtdepois;

                if (touchedvoice && touchedsilence)
                    finishedvoice = true;

                if (finishedvoice) {
                    this.done = true;
                    Log.e("FINISHED_VOICE","FINISHED_VOICE");
                }

                if ((dtdepois - dtantes) / 1000 > mUpperLimit) {
                    this.done = true;
                    if (touchedvoice) {
                        Log.e("TOUCHED_VOICE","TOUCHED_VOICE");
                    } else {
                        raisenovoice = true;
                    }
                }

                if (nshorts <= 0)
                    break;
            }

            *//*mVad.stop();
            recorder.stop();
            recorder.release();*//*

            if (raisenovoice)
                Log.e("RAISED_NO_VOICE","RAISED_NO_VOICE");
            *//*if (cancelled) {
                cancelled = false;
                Log.e("CANCELED","CANCELED");
                return;
            }*//*

        } catch (Exception exc) {
            String error = String.format("General audio error %s", exc.getMessage());
            Log.e("GENERAL_ERROR","GENERAL_ERROR");
            exc.printStackTrace();
        }
    }*/

    /**
     * Convert raw PCM data to a wav file.
     * <p>
     * See: https://stackoverflow.com/questions/43569304/android-how-can-i-write-byte-to-wav-file
     *
     * @return Byte array containing wav file data.
     */
    private byte[] pcmToWav(ByteArrayOutputStream byteArrayOutputStream) throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        byte[] pcmAudio = byteArrayOutputStream.toByteArray();

        writeString(stream, "RIFF"); // chunk id
        writeInt(stream, 36 + pcmAudio.length); // chunk size
        writeString(stream, "WAVE"); // format
        writeString(stream, "fmt "); // subchunk 1 id
        writeInt(stream, 16); // subchunk 1 size
        writeShort(stream, (short) 1); // audio format (1 = PCM)
        writeShort(stream, (short) 1); // number of channels
        writeInt(stream, SAMPLE_RATE); // sample rate
        writeInt(stream, SAMPLE_RATE * 2); // byte rate
        writeShort(stream, (short) 2); // block align
        writeShort(stream, (short) 16); // bits per sample
        writeString(stream, "data"); // subchunk 2 id
        writeInt(stream, pcmAudio.length); // subchunk 2 size
        stream.write(pcmAudio);

        return stream.toByteArray();
    }

    /**
     * Trim the silence from this recording.
     */
    private void trimSilence() {
        // TODO
    }

    /**
     * Validate this recording.
     *
     * @return Boolean indicating whether or not the sample is valid.
     */
    public boolean validateSample() {
        if (mSamplesTaken >= mSampleLengths.length) {
            return false;
        }

        trimSilence();

        double seconds = mPcmStream.size() / SAMPLE_RATE;

        if (seconds > 5) {
            return false;
        }

        for (int i = 0; i < mSamplesTaken; ++i) {
            if (Math.abs(mSampleLengths[i] - seconds) > 0.3) {
                return false;
            }
        }

        mSampleLengths[mSamplesTaken++] = seconds;
        return true;
    }

    /**
     * Write a 32-bit integer to an output stream, in Little Endian format.
     *
     * @param output Output stream
     * @param value  Integer value
     */
    private void writeInt(final ByteArrayOutputStream output, final int value) {
        output.write(value);
        output.write(value >> 8);
        output.write(value >> 16);
        output.write(value >> 24);
    }

    /**
     * Write a 16-bit integer to an output stream, in Little Endian format.
     *
     * @param output Output stream
     * @param value  Integer value
     */
    private void writeShort(final ByteArrayOutputStream output, final short value) {
        output.write(value);
        output.write(value >> 8);
    }

    /**
     * Write a string to an output stream.
     *
     * @param output Output stream
     * @param value  String value
     */
    private void writeString(final ByteArrayOutputStream output, final String value) {
        for (int i = 0; i < value.length(); i++) {
            output.write(value.charAt(i));
        }
    }

    /**
     * Generate a JSON config for the hotword.
     *
     * @return JSONObject containing config.
     */
    private JSONObject generateConfig() {
        JSONObject obj = new JSONObject();

        try {
            obj.put("hotword_key", mHotwordKey);
            obj.put("kind", "personal");
            obj.put("dtw_ref", 0.22);
            obj.put("from_mfcc", 1);
            obj.put("to_mfcc", 13);
            obj.put("band_radius", 10);
            obj.put("shift", 10);
            obj.put("window_size", 10);
            obj.put("sample_rate", SAMPLE_RATE);
            obj.put("frame_length_ms", 25.0);
            obj.put("frame_shift_ms", 10.0);
            obj.put("num_mfcc", 13);
            obj.put("num_mel_bins", 13);
            obj.put("mel_low_freq", 20);
            obj.put("cepstral_lifter", 22.0);
            obj.put("dither", 0.0);
            obj.put("window_type", "povey");
            obj.put("use_energy", false);
            obj.put("energy_floor", 0.0);
            obj.put("raw_energy", true);
            obj.put("preemphasis_coefficient", 0.97);
        } finally {
            return obj;
        }
    }

    /**
     * Write a wav file from the current sample.
     *
     * @throws IOException
     */
    public void writeWav(ByteArrayOutputStream byteArrayOutputStream) {
        byte[] wav = new byte[0];
        try {
            wav = pcmToWav(byteArrayOutputStream);
            Log.i("WAV_size", String.valueOf(wav.length));
        } catch (IOException e) {
            e.printStackTrace();
        }
        FileOutputStream stream = null;

        try {
            try {
                stream = new FileOutputStream(Environment.getExternalStorageDirectory().toString() +
                        "/deepspeech4/soloupis.wav", false);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            try {
                stream.write(wav);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Write a JSON config file for this hotword.
     *
     * @param output Output file
     * @throws IOException
     */
    public void writeConfig(final File output) throws IOException {
        byte[] config = generateConfig().toString().getBytes();
        FileOutputStream stream = null;

        try {
            stream = new FileOutputStream(output);
            stream.write(config);
        } finally {
            if (stream != null) {
                stream.close();
            }
        }
    }

    //AsyncTask for WriteWav
    private class AsyncTaskRunner extends AsyncTask<ByteArrayOutputStream, String, String> {

        @Override
        protected String doInBackground(ByteArrayOutputStream... byteArrayOutputStreams) {

            Log.i("ASYNC_BACK",String.valueOf(byteArrayOutputStreams[0].size()));

            writeWav(byteArrayOutputStreams[0]);

            return null;
        }

        @Override
        protected void onPostExecute(String s) {
            //Toast.makeText(mContext, s, Toast.LENGTH_SHORT).show();
        }
    }
}
