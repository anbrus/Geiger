package com.anton.brusnitsyn.geiger;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.media.*;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by anton on 20.03.16.
 */
public class ActivityMain extends Activity {
    private MediaPlayer player;
    private AudioRecord recorder;
    private final int sampleRate=44100;
    private int sampleCount=0;
    private Thread threadRecord;
    private boolean threadRecordStop=true;
    private final short levelDetect=2000;
    private final short levelRelease=1000;
    private Timer timerRadioactivity;
    private int counterImpulse;
    boolean soundOn=true;

    class HeadphonesReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (threadRecordStop) return;

            AudioManager m = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (m.isWiredHeadsetOn()) return;

            Toast.makeText(ActivityMain.this, "Geiger device removed!", Toast.LENGTH_LONG).show();
            stop();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);
        setActionBar((Toolbar)findViewById(R.id.toolbarMain));
        registerReceiver(new HeadphonesReceiver(), new IntentFilter(Intent.ACTION_HEADSET_PLUG));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.measureStart).setVisible(threadRecordStop);
        menu.findItem(R.id.measureStop).setVisible(!threadRecordStop);
        menu.findItem(R.id.soundOn).setVisible(!soundOn);
        menu.findItem(R.id.soundOff).setVisible(soundOn);

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.measureStart: onStartClicked(); return true;
            case R.id.measureStop: stop(); return true;
            case R.id.soundOn: soundOn=true; invalidateOptionsMenu(); return true;
            case R.id.soundOff: soundOn=false; invalidateOptionsMenu(); return true;
            default: return super.onOptionsItemSelected(item);
        }
    }

    private void onStartClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if ((checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) ||
                    (checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED)){
                requestPermissions(new String[]{Manifest.permission.MODIFY_AUDIO_SETTINGS, Manifest.permission.RECORD_AUDIO}, 1);
            } else {
                Log.d(getClass().getName(), "Already granted access");
                start();
            }
        }else start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 1: {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(getClass().getName(), "Permission Granted");
                    start();
                } else {
                    Log.d(getClass().getName(), "Permission Failed");
                }
            }
        }
    }

    private void calculateRadioactivity() {
        double rentgenHour=(double)counterImpulse/1000000;

        String s="";
        if(rentgenHour<1e-3) {
            s += String.format("%d мкР/ч", Math.round (rentgenHour * 1e6));
        }else if(rentgenHour<1) {
            s += String.format("%d мР/ч", Math.round (rentgenHour * 1e3));
        }else {
            s += String.format("%d Р/ч", Math.round (rentgenHour * 1e3));
        }

        final String msg=s;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                appendMessage(msg);
            }
        });

        counterImpulse=0;
    }

    private void appendMessage(String msg) {
        final TextView log=(TextView)findViewById(R.id.textSamples);
        String s="";
        if(!log.getText().toString().isEmpty()) s+="\n";

        GregorianCalendar cal=new GregorianCalendar();
        SimpleDateFormat df=new SimpleDateFormat();
        s+=df.format(cal.getTime());
        s+="\t";
        s+=msg;
        log.append(s);

        log.postDelayed(new Runnable() {
            @Override
            public void run() {
                ScrollView scrollMain=(ScrollView)findViewById(R.id.scrollMain);
                scrollMain.fullScroll(ScrollView.FOCUS_DOWN);
            }
        }, 0);
    }

    private void start() {
        stop();

        AudioManager m=(AudioManager)getSystemService(AUDIO_SERVICE);
        if(!m.isWiredHeadsetOn()) {
            Toast.makeText(this, "Geiger device not connected!", Toast.LENGTH_LONG).show();
            return;
        }
        m.setMode(AudioManager.MODE_IN_CALL);
        m.setSpeakerphoneOn(true);

        player=new MediaPlayer();
        try {
            AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.beep);
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();

            player.setAudioStreamType(AudioManager.STREAM_VOICE_CALL);
            player.prepare();
            player.setVolume(0.05f, 0.05f);
        }catch(IOException e) {
            Log.e(getClass().getName(), e.toString());
        }

        sampleCount=AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)/2;
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, sampleCount*2);

        if(soundOn) player.start();

        try {
            recorder.startRecording();
        }catch(Exception e) {
            Log.e(getClass().getName(), e.toString());
        }

        counterImpulse=0;
        timerRadioactivity=new Timer();
        timerRadioactivity.schedule(new TimerTask() {
            @Override
            public void run() {
                calculateRadioactivity();
            }
        }, 46*1000, 46*1000);

        appendMessage("Начало измерения");

        threadRecord=new Thread(new Runnable() {
            @Override
            public void run() {
                short data[]=new short[sampleCount];
                boolean detected=false;

                while(!threadRecordStop) {
                    int readed = recorder.read(data, 0, sampleCount);
                    for(int n=0; n<readed; n++) {
                        if(detected) {
                            if(data[n]<levelRelease) detected=false;
                        }else {
                            if(data[n]>levelDetect) {
                                detected=true;
                                onDetect();
                            }
                        }
                    }
                }
            }
        });
        threadRecordStop=false;
        threadRecord.start();
        invalidateOptionsMenu();
        findViewById(R.id.textSamples).setKeepScreenOn(true);
    }

    private void onDetect() {
        Log.d(getClass().getName(), "Detected");

        counterImpulse++;

        if(soundOn) player.start();
    }

    private void stop() {
        findViewById(R.id.textSamples).setKeepScreenOn(false);
        if(timerRadioactivity!=null) {
            timerRadioactivity.cancel();
            timerRadioactivity.purge();
            timerRadioactivity = null;
        }

        AudioManager m=(AudioManager)getSystemService(AUDIO_SERVICE);
        m.setMode(AudioManager.MODE_NORMAL);
        m.setSpeakerphoneOn(false);

        threadRecordStop=true;
        try {
            if ((threadRecord!=null) && threadRecord.isAlive()) threadRecord.join();
        }catch(InterruptedException e) {}
        if(recorder!=null) {
            recorder.stop();
            recorder.release();
            recorder=null;
            appendMessage("Завершение измерения");
        }
        if(player!=null) {
            player.release();
            player=null;
        }
        invalidateOptionsMenu();
    }

    @Override
    public void finish() {
        stop();

        super.finish();
    }
}
