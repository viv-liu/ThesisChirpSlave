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
    private final int NUM_SAMPLES = sampleRate/4;
    private final double sample[] = new double[NUM_SAMPLES];
    private final int SELF_TONE_FREQUENCY = 1000; // hz, A5
    private final int OTHER_TONE_FREQUENCY = SELF_TONE_FREQUENCY/2;

    // Microphone in variables
    int channelConfiguration = AudioFormat.CHANNEL_IN_MONO;
    int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
    AudioRecord audioRecord;
    RecordAudio recordTask;
    int blockSize = 32;
    boolean started = false;
    
    // Speaker out variables
    private AudioTrack mAudioTrack;
    private final byte generatedSnd[] = new byte[2 * NUM_SAMPLES];
    
    private int beepNum = 0;
    
    // Views
    private TextView mStatusText;
    private TextView mOtherToneIndexText;
    private TextView mSelfToneIndexText;
    private TextView mBeepNumText;
    private TextView mIndexDiffText;
    private Button mResetButton;
    private Button mSaveButton;
    
    // File operations
    FileWriter writer;
    private List<Short> grandBuffer;
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        genTone();
        grandBuffer  = new ArrayList<Short>();
        
        mStatusText = (TextView) findViewById(R.id.textView1);
        mOtherToneIndexText = (TextView) findViewById(R.id.textView2);
        mSelfToneIndexText = (TextView) findViewById(R.id.textView3);
        mBeepNumText = (TextView) findViewById(R.id.textView4);
        mIndexDiffText = (TextView) findViewById(R.id.textView5);
        
        mResetButton = (Button) findViewById(R.id.button1);        
    	mResetButton.setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				mStatusText.setText("Initial state. Waiting for master signal.");
				beepNum = 0;
				grandBuffer.clear();
				if(mAudioTrack != null) mAudioTrack.stop();
				if(recordTask != null) {
					recordTask.cancel(false);
					recordTask.reset();
				}
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
				//createTimesRecordFile();
				createBigBufferFile(grandBuffer);
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
            sample[i] = Math.sin(2 * Math.PI * i / (sampleRate/SELF_TONE_FREQUENCY));
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
    	mAudioTrack.stop();
    	mAudioTrack.reloadStaticData();
        mAudioTrack.play();
    }
    
    private void createBigBufferFile(List<Short> buffer) {
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
	            Toast.makeText(this, "Buffer written to file.", Toast.LENGTH_SHORT).show();
    	    } catch (IOException e) {
    	        e.printStackTrace();
    	    }    
    	} else {
    	
    		Log.d("ERROR", "External storage not writable");
    	}
    }

    	private void createBigRecordAudioDurationFile(List<Long> buffer) {
        	Calendar c = Calendar.getInstance(); 
            SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
            dateFormat.setTimeZone(c.getTimeZone());
            timeFormat.setTimeZone(c.getTimeZone());
        	String FILE_NAME = timeFormat.format(c.getTime())+"AudioRecordDuration.csv";
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
    
    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }

    private void writeCsvHeader(String h1, String h2, String h3) throws IOException {
	   String line = String.format("%s,%s,%s\n", h1,h2,h3);
	   writer.write(line);
	   }

	private void writeCsvData(long selfTime, long waitTime, long selfAudioRecordDelay) throws IOException {  
	  String line = String.format("%d,%d,%d\n", selfTime, waitTime, selfAudioRecordDelay);
	  writer.write(line);
	}
    
    private class RecordAudio extends AsyncTask<Void, Boolean, Void> {
    	
    	private final String SELF_DETECTED_STRING = "DETECTED_SELF";
    	private final long Ts = (long) 1.0/micSampleRate;
    	
    	private int curBufferCount = 0;
    	private int selfToneBeginningIndex = -1;
    	private int otherToneBeginningIndex = -1;
    	
    	boolean heardSelf = false;
    	boolean heardOther = false;
        @Override
        protected Void doInBackground(Void... params) {
        	if(isCancelled()){
        		return null;
        	}
        	List<Integer> zeroCrossingIndices = new ArrayList<Integer>();
            int bufferSize = AudioRecord.getMinBufferSize(micSampleRate, channelConfiguration, audioEncoding);
            Log.d(SELF_DETECTED_STRING, "min buffer size = " + String.valueOf(bufferSize));
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, micSampleRate, channelConfiguration, audioEncoding, 60000);
            
            int bufferReadResult;
            short[] buffer = new short[blockSize];
            //double[] toTransform = new double[blockSize];
            try{
            	audioRecord.startRecording();
            }
            catch(IllegalStateException e){
            	Log.e("Recording failed", e.toString());
            	
            }
            while (started) {
            	zeroCrossingIndices.clear();
            	bufferReadResult = audioRecord.read(buffer, 0, blockSize);
            	curBufferCount++;
            	
            	//Log.e(AUDIO_RECORD_TIMING, String.valueOf(delta1));
            	if(isCancelled())
                    	break;

	            for (int i = 1; i < blockSize && i < bufferReadResult; i++) {
	            	
	            	if(grandBuffer.size() <= 100000)
	            		grandBuffer.add(buffer[i-1]);
	            	
	            	if((buffer[i] < 0 && buffer[i-1] > 0) || (buffer[i] > 0 && buffer[i-1] < 0)) {
	            		zeroCrossingIndices.add(i);
	            	}
	            }
	            int isOtherTone = detectTone(micSampleRate, OTHER_TONE_FREQUENCY, zeroCrossingIndices);
	            int isSelfTone = detectTone(micSampleRate, SELF_TONE_FREQUENCY, zeroCrossingIndices);
	            
	            if(!heardOther && isOtherTone > 0) {
            		heardOther = true;
            		heardSelf = false;
            		otherToneBeginningIndex = (curBufferCount - 1) * blockSize + isOtherTone;
            		publishProgress(false);
            		playSound();
            	}
            	
            	else if(!heardSelf && isSelfTone > 0) {
            		heardOther = false;
            		heardSelf = true;
            		selfToneBeginningIndex = (curBufferCount - 1) * blockSize + isSelfTone;
            		publishProgress(true);
            	}
	            
	            if(isCancelled())
	            	break;
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
		protected void onProgressUpdate(Boolean... isLaterBeep) {
        	mBeepNumText.setText("Beep num: " + String.valueOf(beepNum));
        	mSelfToneIndexText.setText("Self tone index: " + String.valueOf(selfToneBeginningIndex));
        	mOtherToneIndexText.setText("Other tone index: " + String.valueOf(otherToneBeginningIndex));
        	if(isLaterBeep[0])
        		mIndexDiffText.setText("Index diff: " + String.valueOf(selfToneBeginningIndex - otherToneBeginningIndex));
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
        
        /**
         * Detect the presence of a tone and returns the index of beginning of tone.
         * @param sampleRate
         * @param pitch
         * @param indices
         * @return index of beginning of tone in the 512 buffer
         */
        private int detectTone(int sampleRate, int pitch, List<Integer> indices) {
        	
        	double Ts = 1.0 / sampleRate;
        	double T = 1.0 / pitch;
        	int numTsInT = (int) (T/Ts);
        	int expectedSpacing = numTsInT / 2; // index spacing, 2 zero crossings in a single sinusoid
        	
        	int CONSEC_PATTERN_THRESHOLD = blockSize/expectedSpacing - 2;
        	
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
        			return -1;
        		}
        		
        		if(consecCount >= CONSEC_PATTERN_THRESHOLD) {
        			Log.d("Spacing", "Woohoo conseq count = " + String.valueOf(consecCount));
        			return indices.get(i-consecCount);
        		}
        	}
        	return -1;
        }
        
        public void reset() {
        	curBufferCount = 0;
        	selfToneBeginningIndex = -1;
        	otherToneBeginningIndex = -1;
        	heardSelf = false;
        	heardOther = false;
        }
	}
}