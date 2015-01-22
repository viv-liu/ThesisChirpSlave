package com.example.playtoneslave;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;


public class MainActivity extends Activity {
	public static final String TAG = "PlayToneSlave";
    // originally from http://marblemice.blogspot.com/2010/04/generate-and-play-tone-in-android.html
    // and modified by Steve Pomeroy <steve@staticfree.info>
    //private final int duration = 1; // seconds
	private final int micSampleRate = 8000;
    private final int sampleRate = 8000;
    private final int NUM_SAMPLES = sampleRate/6;
    private final double sample[] = new double[NUM_SAMPLES];
    private final int TONE_FREQUENCY = 880; // hz, A5

    // Microphone in variables
    int channelConfiguration = AudioFormat.CHANNEL_IN_MONO; //TODO: CHANNEL_IN_MONO?
    int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
    AudioRecord audioRecord;
    RecordAudio recordTask;
    int blockSize = 512;
    boolean started = false;
    
    // Speaker out variables
    private AudioTrack mAudioTrack;
    private final byte generatedSnd[] = new byte[2 * NUM_SAMPLES];
    
    private long mStartTime = 0;
    private long mStopTime = 0;
    private int beepNum = 0;
    private long selfSpeakerDelay = -1;
    
    // Views
    private TextView mStatusText;
    private TextView mTimeWaitedText;
    private TextView mSelfDelayText;
    private TextView mBeepNumText;
    private Button mResetButton;
    private Button mSaveButton;
    
    // File operations
    FileWriter writer;
    private List<Long> selfTimesRecord;
    private List<Long> waitTimesRecord;
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        genTone();
        selfTimesRecord = new ArrayList<Long>();
        waitTimesRecord = new ArrayList<Long>();
        
        mStatusText = (TextView) findViewById(R.id.textView1);
        mTimeWaitedText = (TextView) findViewById(R.id.textView2);
        mSelfDelayText = (TextView) findViewById(R.id.textView3);
        mBeepNumText = (TextView) findViewById(R.id.textView4);
        
        mResetButton = (Button) findViewById(R.id.button1);        
    	mResetButton.setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				mStatusText.setText("Initial state. Waiting for master signal.");
				beepNum = 0;
				selfTimesRecord.clear();
				waitTimesRecord.clear();
				if(mAudioTrack != null) mAudioTrack.stop();
				if(recordTask != null) recordTask.cancel(false);
				started = true;
				recordTask = new RecordAudio();
				recordTask.execute();
			}
	      });
    	
    	mSaveButton = (Button) findViewById(R.id.button2);        
    	mSaveButton.setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				mStatusText.setText("Initial state. Waiting for master signal.");
				if(mAudioTrack != null) mAudioTrack.stop();
				if(recordTask != null) recordTask.cancel(false);
				createTimesRecordFile();
			}
	      });
    }

    @Override
    protected void onPause() {
    	super.onPause();
    	if(mAudioTrack != null) mAudioTrack.release();
    	if(recordTask != null) recordTask.cancel(false);
    }
    
    @Override
    protected void onResume() {
    	super.onResume();
    	mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, generatedSnd.length,
                AudioTrack.MODE_STATIC);
        mAudioTrack.write(generatedSnd, 0, generatedSnd.length);
    }
    
    void genTone(){
        // fill out the array
        for (int i = 0; i < NUM_SAMPLES; ++i) {
            sample[i] = Math.sin(2 * Math.PI * i / (sampleRate/TONE_FREQUENCY));
        }

        // convert to 16 bit pcm sound array
        // assumes the sample buffer is normalised.
        int idx = 0;
        for (final double dVal : sample) {
            // scale to maximum amplitude
            final short val = (short) ((dVal * 32767));
            // in 16 bit wav PCM, first byte is the low order byte
            generatedSnd[idx++] = (byte) (val & 0x00ff);
            generatedSnd[idx++] = (byte) ((val & 0xff00) >>> 8);

        }
    }

    void playSound(){
    	beepNum++;
    	selfSpeakerDelay = -1;
    	mAudioTrack.stop();
    	mAudioTrack.reloadStaticData();
    	startTiming();
        mAudioTrack.play();
    }
    
    void startTiming() {
    	mStartTime = System.nanoTime();
    }
    
    long stopTiming() {
    	mStopTime = System.nanoTime();
    	long delta = mStopTime - mStartTime;
		Log.d("Slave sound times", "Time of flight = " + String.valueOf(delta) + "ns == " 
														+ String.valueOf(delta/1000000000.0) + "s");
		return delta;
    }
    
    private void createBigBufferFile(List<Double> buffer) {
    	Calendar c = Calendar.getInstance(); 
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
        dateFormat.setTimeZone(c.getTimeZone());
        timeFormat.setTimeZone(c.getTimeZone());
    	String FILE_NAME = timeFormat.format(c.getTime())+"Buffer.csv";
    	if (isExternalStorageWritable()) {
    		File root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS); 
    		File dir = new File (root.getAbsolutePath() + "/Thesis/" + dateFormat.format(c.getTime()));
    	    if (!dir.exists()) {
                dir.mkdirs();
            }
    	    //Toast.makeText(this, dir.toString(), Toast.LENGTH_SHORT).show();
    	    File file = new File(dir.getAbsolutePath(), FILE_NAME);
    	    try {
    	    	writer = new FileWriter(file);
	            
    	    	// Write contents of buffer in
	            for(int i = 0; i < buffer.size(); i++) {
	            	String line = buffer.get(i).toString() + "\n";
	          	  	writer.write(line);
	            }
	            
	            writer.flush();
	            writer.close(); 
	            Log.d("FILEIO", "FINISHED WRITING");
	            //Toast.makeText(this, "Buffer written to file.", Toast.LENGTH_SHORT).show();
    	    } catch (IOException e) {
    	        e.printStackTrace();
    	    }    
    	} else {
    	
    		Log.d("ERROR", "External storage not writable");
    	}
    }
    private void createTimesRecordFile() {
    	Calendar c = Calendar.getInstance(); 
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
        dateFormat.setTimeZone(c.getTimeZone());
        timeFormat.setTimeZone(c.getTimeZone());
    	String FILE_NAME = timeFormat.format(c.getTime())+".csv";
    	if (isExternalStorageWritable()) {
    		File root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS); 
    		File dir = new File (root.getAbsolutePath() + "/Thesis/" + dateFormat.format(c.getTime()));
    	    if (!dir.exists()) {
                dir.mkdirs();
            }
    	    Toast.makeText(this, dir.toString(), Toast.LENGTH_SHORT).show();
    	    File file = new File(dir.getAbsolutePath(), FILE_NAME);
    	    try {
    	    	writer = new FileWriter(file);
	            
	            writeCsvHeader("Slave selfTimesRecord", "Slave waitTimesRecord");
	            for(int i = 0; i < selfTimesRecord.size(); i++) {
	            	writeCsvData(selfTimesRecord.get(i), waitTimesRecord.get(i));
	            }
	            
	            writer.flush();
	            writer.close(); 
	            Log.d("FILEIO", "FINISHED WRITING");
	            Toast.makeText(this, "Records written to file.", Toast.LENGTH_SHORT).show();
    	    } catch (IOException e) {
    	        e.printStackTrace();
    	    }    
    	} else {
    	
    		Log.d("ERROR", "External storage not writable");
    	}
    }
    
    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }

    private void writeCsvHeader(String h1, String h2) throws IOException {
	   String line = String.format("%s %s\n", h1,h2);
	   writer.write(line);
	   }

	private void writeCsvData(long selfTime, long waitTime) throws IOException {  
	  String line = String.format("%d,%d,\n", selfTime, waitTime);
	  writer.write(line);
	}
    
    private class RecordAudio extends AsyncTask<Void, Boolean, Void> {
    	
    	private final String SELF_DETECTED_STRING = "DETECTED_SELF";
    	private final String OTHER_DETECTED_STRING = "DETECTED_OTHER";
    	private final String WHILE_LOOP_TIMING = "WHILE_LOOP_TIMING";
    	private final String AUDIO_RECORD_TIMING = "AUDIO_RECORD_TIMING";
    	private final String BUFFER_VISUALIZATION = "BUFFER_VISUALIZATION";
    	private long timeWaited;
    	private List<Double> buffer_list = new ArrayList<Double>();
    	boolean heardSelf = false;
    	boolean heardOther = false;
        @Override
        protected Void doInBackground(Void... params) {
        	if(isCancelled()){
        		return null;
        	}
        	List<Integer> zeroCrossingIndices = new ArrayList<Integer>();
            int bufferSize = AudioRecord.getMinBufferSize(micSampleRate, channelConfiguration, audioEncoding);
            Log.d(SELF_DETECTED_STRING, "min buffer size = " + String.valueOf(AudioRecord.getMinBufferSize(micSampleRate, channelConfiguration, audioEncoding)));
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, micSampleRate, channelConfiguration, audioEncoding, bufferSize);
            
            int bufferReadResult;
            short[] buffer = new short[blockSize];
            double[] toTransform = new double[blockSize];
            try{
            	audioRecord.startRecording();
            }
            catch(IllegalStateException e){
            	Log.e("Recording failed", e.toString());
            	
            }
            while (started) {
            	long startTime = System.nanoTime();
            	
            	long startTime1 = System.nanoTime();
            	bufferReadResult = audioRecord.read(buffer, 0, blockSize);
            	long delta1 = System.nanoTime() - startTime1;
            	
            	zeroCrossingIndices.clear();
            	Log.e(AUDIO_RECORD_TIMING, String.valueOf(delta1));
            	if(isCancelled())
                    	break;

            	double buffer_i;
            	double buffer_i_prev;
	            for (int i = 1; i < blockSize && i < bufferReadResult; i++) {
	            	buffer_i = (double) buffer[i] / 32768.0;
	            	buffer_i_prev = (double) buffer[i - 1] / 32768.0;
	            	if(buffer_list.size() <= 100000) {
	            		buffer_list.add(buffer_i_prev);
	            	} else {
	            		createBigBufferFile(buffer_list);
	            		this.cancel(false);
	            	}
	            	
	            	if(buffer_i < 0.0 && buffer_i_prev > 0.0 || buffer_i > 0.0 && buffer_i_prev < 0.0) {
	            		zeroCrossingIndices.add(i);
	            		// zero crossing detected
	            		//Log.d(TAG, "zero crossing at index i = " + String.valueOf(i));
	            	}
	            	
	                toTransform[i] = (double) buffer[i] / 32768.0; // signed 16 bit
	            }
	            
	            if(!heardOther && detectTone(micSampleRate, TONE_FREQUENCY / 2, zeroCrossingIndices)) {
            		Log.e(OTHER_DETECTED_STRING, "Detected receive tone = " + String.valueOf(TONE_FREQUENCY / 2));            		
            		timeWaited = waitASecond();
            		waitTimesRecord.add(timeWaited);
            		heardOther = true;
            		heardSelf = false;
            		publishProgress(false);
            		playSound();
            	}
            	
            	else if(!heardSelf && detectTone(micSampleRate, TONE_FREQUENCY, zeroCrossingIndices)) {
            		Log.e(SELF_DETECTED_STRING, "Detected self tone " + String.valueOf(TONE_FREQUENCY));
            		selfSpeakerDelay = stopTiming();
            		selfTimesRecord.add(selfSpeakerDelay);
            		
            		if(selfTimesRecord.size() != waitTimesRecord.size()) {
            			isCancelled();
            		}
            		heardOther = false;
            		heardSelf = true;
            		publishProgress(true);
            	}
	            
	            if(isCancelled())
	            	break;
	            long delta = System.nanoTime() - startTime;
	            Log.e(WHILE_LOOP_TIMING, String.valueOf(delta));
            }
            
            try{
            	audioRecord.stop();
            }
            catch(IllegalStateException e){
            	Log.e("Stop failed", e.toString());
            	
            }               
            
            return null;
        }
        
        @Override
		protected void onProgressUpdate(Boolean... isSpeakerDelay) {
        	mBeepNumText.setText("Beep num: " + String.valueOf(beepNum));
			if(isSpeakerDelay[0]) {
				mSelfDelayText.setText("selfSpeakerDelay: " + String.valueOf(selfSpeakerDelay/1000000.0) + "ms");
			} else {
				mTimeWaitedText.setText("Heard master, timeWaited: " + String.valueOf(timeWaited/1000000.0) + "ms");
			}
		}
        
        protected void onPostExecute(Void result) {
        	try{
            	audioRecord.stop();
            }
            catch(IllegalStateException e){
            	Log.e("Stop failed", e.toString());
            	
            }
        	recordTask.cancel(true); 
        	
        	Intent intent = new Intent(Intent.ACTION_MAIN);
        	intent.addCategory(Intent.CATEGORY_HOME);
        	intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        	startActivity(intent);
        }
        
        /*
         * ONLY DETECT TONES THAT ARE MULTIPLES OF EACH OTHER
         * PRONE TO SAY FALSE THAN TRUE
         */
        private boolean detectTone(int sampleRate, int pitch, List<Integer> indices) {
        	
        	if(indices.size() <= 0) {
        		return false;
        	}
        	int CONSEC_PATTERN_THRESHOLD = (indices.size() < 20) ? indices.size() : 20;
        	double Ts = 1.0 / sampleRate;
        	double T = 1.0 / pitch;
        	
        	int numTsInT = (int) (T/Ts);
        	
        	int expectedSpacing = numTsInT / 2; // index spacing, 2 zero crossings in a single sinusoid
        	Log.d(TAG, "Expected spacing for " + String.valueOf(pitch) + " = " + String.valueOf(expectedSpacing));
        	int diff = 0;
        	int consecCount = 0;
        	
        	for(int i = 1; i < indices.size(); i++) {
        		diff = indices.get(i) - indices.get(i-1);
        		if((diff >= (expectedSpacing - 1)) && (diff <= (expectedSpacing + 1))) {
        			consecCount++;
        			Log.d("Spacing", "Spacing ok, index " + String.valueOf(i) + " = " + String.valueOf(indices.get(i)));
        		} else {
        			Log.e("Spacing", "Failed, index " + String.valueOf(i) + " = " + String.valueOf(indices.get(i)));
        			return false;
        		}
        		
        		if(consecCount >= CONSEC_PATTERN_THRESHOLD) {
        			Log.d("Spacing", "Woohoo conseq count = " + String.valueOf(consecCount));
        			return true;
        		}
        	}
        	return false;
        }
        private long waitASecond() {
        	long startTime = System.nanoTime();
        	long delta = 0;
        	while(delta < 500000000) {
        		delta = System.nanoTime() - startTime;
        	}
        	
        	return delta;
        }
        
        public void resetListening() {
        	heardSelf = false;
        	heardOther = false;
        }
	}
}