package co.medcorder.medcorderaudio;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.util.Log;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;

import java.io.File;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;

/**
 * MedcorderAudioPlugin
 */
public class MedcorderAudioPlugin implements MethodCallHandler, EventChannel.StreamHandler,PluginRegistry.RequestPermissionsResultListener {
  /**
   * Plugin registration.
   */
  private static final String TAG = "MEDCORDER";
  private EventChannel.EventSink eventSink;

  private Context context;
  private Timer recordTimer;
  private Timer playTimer;

  private MediaRecorder recorder;
  private String currentOutputFile;
  private boolean isRecording = false;
  private double recorderSecondsElapsed;

  private MediaPlayer player;
  private String currentPlayingFile;
  private boolean isPlaying = false;
  private double playerSecondsElapsed;
  private static final int REQUEST_PERMISSIONS_REQUEST_CODE = 123;

  private Activity activity;

  MedcorderAudioPlugin(Activity _activity){
    this.activity = _activity;
    this.context = this.activity.getApplicationContext();
  }

  public static void registerWith(Registrar registrar) {
    final MedcorderAudioPlugin plugin = new MedcorderAudioPlugin(registrar.activity());

    final MethodChannel methodChannel = new MethodChannel(registrar.messenger(), "medcorder_audio");
    methodChannel.setMethodCallHandler(plugin);

    final EventChannel eventChannel = new EventChannel(registrar.messenger(), "medcorder_audio_events");
    eventChannel.setStreamHandler(plugin);

    final MedcorderAudioPlugin instance = new MedcorderAudioPlugin(registrar.activity());
    registrar.addRequestPermissionsResultListener(instance);

  }

  @Override
  public void onListen(Object arguments, EventChannel.EventSink events) {
    eventSink = events;
  }

  @Override
  public void onCancel(Object arguments) {
    eventSink = null;
  }

  @Override
  public void onMethodCall(MethodCall call, Result result) {
    if (call.method.equals("setAudioSettings")) {
      result.success("OK");
    } else if (call.method.equals("backAudioSettings")) {
      result.success("OK");
    } else if (call.method.equals("startRecord")) {
      result.success(startRecord((String) call.arguments) ? "OK" : "FAIL");
    } else if (call.method.equals("stopRecord")) {
      result.success(stopRecord() ? "OK" : "FAIL");
    } else if (call.method.equals("startPlay")) {
      HashMap params = (HashMap) call.arguments;
      String fileName = (String) params.get("file");
      double position = (double) params.get("position");
      result.success(startPlay(fileName, position) ? "OK" : "FAIL");
    } else if (call.method.equals("stopPlay")) {
      stopPlay();
      result.success("OK");
    } else if (call.method.equals("checkMicrophonePermissions")) {
      result.success(checkMicrophonePermissions() ? "OK" : "NO");
    } else if (call.method.equals("base64")) {
      result.success(base64());
    } else {
      result.notImplemented();
    }
  }
    
  private void sendEvent(Object o){
    if (eventSink != null){
      eventSink.success(o);
    }
  }

  private boolean checkMicrophonePermissions(){
    int permissionCheck = ActivityCompat.checkSelfPermission(activity,
            Manifest.permission.RECORD_AUDIO);
    boolean permissionGranted = permissionCheck == PackageManager.PERMISSION_GRANTED;

    if(!permissionGranted){
      ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.RECORD_AUDIO},
              REQUEST_PERMISSIONS_REQUEST_CODE);
    }
    return permissionGranted;
  }



  private boolean startRecord(String fileName){
    Log.d(TAG, "startRecord:" + fileName);
    recorder = new MediaRecorder();
    try {
      currentOutputFile = activity.getApplicationContext().getFilesDir() + "/" + fileName + ".aac";
      recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      int outputFormat = MediaRecorder.OutputFormat.AAC_ADTS;
      recorder.setOutputFormat(outputFormat);
      int audioEncoder = MediaRecorder.AudioEncoder.AAC;
      recorder.setAudioEncoder(audioEncoder);
      recorder.setAudioSamplingRate(16000);
      recorder.setAudioChannels(2);
      recorder.setAudioEncodingBitRate(32000);
      recorder.setOutputFile(currentOutputFile);
    }
    catch(final Exception e) {
      return false;
    }

    try {
      recorder.prepare();
      recorder.start();
      isRecording = true;
      startRecordTimer();
    } catch (final Exception e) {
      return false;
    }

    return true;
  }

  public boolean stopRecord(){
    if (!isRecording){
      // sendEvent("recordingFinished");
      return true;
    }

    stopRecordTimer();
    isRecording = false;

    try {
      recorder.stop();
      recorder.release();
    }
    catch (final RuntimeException e) {
      return false;
    }
    finally {
      recorder = null;
    }

    // sendEvent("recordingFinished");
    return true;
  }

  private void startRecordTimer(){
    stopRecordTimer();
    recordTimer = new Timer();
    recordTimer.scheduleAtFixedRate(new TimerTask() {
      @Override
      public void run() {
        updateRecordingWithCode("recording");
        recorderSecondsElapsed = recorderSecondsElapsed + 0.1;
      }
    }, 0, 100);
  }

  private void stopRecordTimer(){
    recorderSecondsElapsed = 0.0;
    if (recordTimer != null) {
      recordTimer.cancel();
      recordTimer.purge();
      recordTimer = null;
    }
  }

  private void startPlayTimer(){
    stopPlayTimer();
    playTimer = new Timer();
    playTimer.scheduleAtFixedRate(new TimerTask() {
      @Override
      public void run() {
        updatePlayingWithCode("playing");
        playerSecondsElapsed = playerSecondsElapsed + 0.1;
      }
    }, 0, 100);
  }

  private void stopPlayTimer(){
    playerSecondsElapsed = 0.0;
    if (playTimer != null) {
      playTimer.cancel();
      playTimer.purge();
      playTimer = null;
    }
  }

  private boolean startPlay(String fileName, double duration){
    try{
      if (player != null && player.isPlaying()){
        player.stop();
        player.release();
      }
    }catch(Exception e){

    }finally {
      player = null;
    }

    currentPlayingFile = activity.getApplicationContext().getFilesDir() + "/" + fileName + ".aac";
    File file = new File(currentPlayingFile);
    if (file.exists()) {
      Uri uri = Uri.fromFile(file);
      player =  MediaPlayer.create(this.context, uri);
      player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
        boolean callbackWasCalled = false;

        @Override
        public synchronized void onCompletion(MediaPlayer mp) {
          if (callbackWasCalled) return;
          callbackWasCalled = true;
          stopPlayTimer();
          updatePlayingWithCode("audioPlayerDidFinishPlaying");
        }
      });
      player.seekTo(new Double(duration).intValue() * 1000);
      player.start();
      startPlayTimer();
      isPlaying = true;
    }else{
      return false;
    }

    return true;
  }

  private boolean stopPlay(){
    try{
      if (player.isPlaying()){
        player.stop();
        player.release();
      }
    }catch(Exception e){

    }
    finally {
      player = null;
    }

    stopPlayTimer();
    updatePlayingWithCode("audioPlayerDidFinishPlaying");
    isPlaying = false;
    return true;
  }

  private String base64(){
    return "data:audio/aac;base64,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";
  }

  private void updateRecordingWithCode(String code){
    HashMap<String, Object> body = new HashMap<String, Object>();
    body.put("code", code);
    body.put("url", currentOutputFile);
    body.put("peakPowerForChannel", (double) recorder.getMaxAmplitude());
    body.put("currentTime", recorderSecondsElapsed);
    sendEvent(body);
  }

  private void updatePlayingWithCode(String code){
    HashMap<String, Object> body = new HashMap<String, Object>();
    body.put("code", code);
    if (player.isPlaying()) {
      body.put("url", currentPlayingFile);
      body.put("currentTime", (double) new Double(player.getCurrentPosition()) / 1000.0);
      body.put("duration", (double) new Double(player.getDuration()) / 1000.0);
    }
    sendEvent(body);
  }

  @Override
  public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE) {
      if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        return true;
      }
    }
    return false;
  }
}
