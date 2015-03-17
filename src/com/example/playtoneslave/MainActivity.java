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
    //private final int duration = 1;
	private final int micSampleRate = 8000;
	
    private final int SELF_TONE_FREQUENCY = 500; // hz, A5
    private final int SELF_TONE_FREQUENCY_2 = SELF_TONE_FREQUENCY * 2;
    private final int OTHER_TONE_FREQUENCY = 400;
    private final int OTHER_TONE_FREQUENCY_2 = OTHER_TONE_FREQUENCY * 2;

    // Microphone in variables
    int channelConfiguration = AudioFormat.CHANNEL_IN_MONO;
    int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
    AudioRecord audioRecord;
    RecordAudio recordTask;
    int blockSize = 256;
    boolean started = false;
    
    // Speaker out variables
    private AudioTrack mAudioTrack;
    private final int speakerSampleRate = 8000;
    private final double outToneDurationInS = 0.125;
    private final int numTones = 12;
    private final int numSamplesInTone_mic = (int) (micSampleRate * outToneDurationInS);
    private final int NUM_SAMPLES = (int) (speakerSampleRate * outToneDurationInS * numTones);
   
    private final double sample[] = new double[NUM_SAMPLES];
    private final byte generatedSnd[] = new byte[2 * NUM_SAMPLES];
    private final double durationOfSilenceInS = 0.5;
    private final int numSamplesInSilence = (int) (speakerSampleRate * durationOfSilenceInS);
    
	private List<Integer>selfToneEdges = new ArrayList<Integer>();
	private List<Integer>otherToneEdges = new ArrayList<Integer>();
	
    // Views
    private TextView mStatusText;
    private TextView mOtherToneIndexText;
    private TextView mSelfToneIndexText;
    private TextView mIndexDiffText;
    private Button mResetButton;
    private Button mSaveButton;
    
 // Constant UI messages
    private String init_s = "Initial state, press the button to start listening.";
    private String recording_s = "Recording in progress...";
    private String saving_s = "Saving buffer contents to file...";
    
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
        mStatusText.setText(init_s);
        mOtherToneIndexText = (TextView) findViewById(R.id.textView2);
        mSelfToneIndexText = (TextView) findViewById(R.id.textView3);
        mIndexDiffText = (TextView) findViewById(R.id.textView5);
        
        mResetButton = (Button) findViewById(R.id.button1);        
    	mResetButton.setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				mIndexDiffText.setText("Index diff");
				mStatusText.setText(init_s);
				grandBuffer.clear();
				started = false;
				if(mAudioTrack != null) mAudioTrack.stop();
				if(recordTask != null) {
					recordTask.cancel(false);
					recordTask.reset();
				}
				mSaveButton.setText("Start listening");
			}
	      });
    	
    	mSaveButton = (Button) findViewById(R.id.button2);        
    	mSaveButton.setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				if(mSaveButton.getText().equals("Start listening")) {
					mStatusText.setText(recording_s);
					started = true;
					recordTask = new RecordAudio();
					recordTask.execute();
					mSaveButton.setText("Stop and analyze");
					return;
				} else if(mSaveButton.getText().equals("Stop and analyze")) {
					started = false;
					recordTask.cancel(true);
					bufferAnalysis();
					mSaveButton.setText("Save records");
					return;
				} else if(mSaveButton.getText().equals("Save records")) {
					mStatusText.setText(saving_s);
					// Save records
					createBigBufferFile(grandBuffer, selfToneEdges, otherToneEdges);
					
					// Reset
					if(mAudioTrack != null) mAudioTrack.stop();
					if(recordTask != null) recordTask.cancel(false);
					mIndexDiffText.setText("Index diff");
					mStatusText.setText(init_s);
					grandBuffer.clear();
					started = false;
					if(mAudioTrack != null) mAudioTrack.stop();
					if(recordTask != null) {
						recordTask.cancel(false);
						recordTask.reset();
					}
					mSaveButton.setText("Start listening");
					mStatusText.setText(init_s);
					return;
				}
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
                speakerSampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, generatedSnd.length,
                AudioTrack.MODE_STATIC);
        mAudioTrack.write(generatedSnd, 0, generatedSnd.length);
    }
    
    void genTone(){
    	int numSamplesPerTone = (int) (speakerSampleRate * outToneDurationInS);
    	int curFreq = SELF_TONE_FREQUENCY;
    	for(int i = 0; i < NUM_SAMPLES; i+=numSamplesPerTone) {
	        // fill out the array
	        for (int j = 0; j < numSamplesPerTone; ++j) {
	            sample[j + i] = Math.sin(2 * Math.PI * j / (speakerSampleRate/curFreq));
	        }
	        curFreq = (curFreq == SELF_TONE_FREQUENCY ? SELF_TONE_FREQUENCY_2 : SELF_TONE_FREQUENCY);
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
    	mAudioTrack.stop();
    	mAudioTrack.reloadStaticData();
        mAudioTrack.play();
    }
    
    
    
    private class RecordAudio extends AsyncTask<Void, Boolean, Void> {
    	
    	private final String SELF_DETECTED_STRING = "DETECTED_SELF";
    	
    	private final int CONSEQ_COUNT_THRESHOLD = 5;
    	private int expectedSelfSpacing = expectedZerosSpacing(micSampleRate, SELF_TONE_FREQUENCY);
    	private int expectedOtherSpacing = expectedZerosSpacing(micSampleRate, OTHER_TONE_FREQUENCY);
    	
    	boolean heardSelf = true;
    	boolean heardOther = false;
    	int indexToPlaySound = -1;
    	int indexToEndRecording = -1;
    	
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
            	int i_lastZero = 0;
            	int conseqCount = 0;
            	
            	zeroCrossingIndices.clear();
            	bufferReadResult = audioRecord.read(buffer, 0, blockSize);
            	
            	//Log.e(AUDIO_RECORD_TIMING, String.valueOf(delta1));
            	if(isCancelled())
                    	break;

	            for (int i = 1; i < blockSize && i < bufferReadResult; i++) {
	            	
	            	if(grandBuffer.size() <= 100000)
	            		grandBuffer.add(buffer[i-1]);
	            	
	            	if(indexToPlaySound > 0 && grandBuffer.size() >= indexToPlaySound) {
	            		indexToPlaySound = -1;
	            		playSound();
	            	}
	            	
	            	if(indexToEndRecording > 0 && grandBuffer.size() >= indexToEndRecording) {
	            		indexToEndRecording = -1;
	            		publishProgress(true);
	            		break;
	            	}
	            	
	            	if((conseqCount < CONSEQ_COUNT_THRESHOLD) &&
	            		((buffer[i] < 0 && buffer[i-1] > 0) || (buffer[i] > 0 && buffer[i-1] < 0))) {
	            		if(!heardSelf) {
	            			if(((i-i_lastZero) >= (expectedSelfSpacing - 1)) && 
	            					((i-i_lastZero) <= (expectedSelfSpacing + 1))) {
	            				conseqCount++;
	            				Log.d(TAG, "Good spacing " + String.valueOf(i));
	            			} else {
	            				conseqCount = 0;
	            				Log.d(TAG, "Bad spacing " + String.valueOf(i));
	            			}
	            			if(conseqCount >= CONSEQ_COUNT_THRESHOLD) {
	            				heardSelf = true;
	            				indexToEndRecording = grandBuffer.size() + (int) (micSampleRate * outToneDurationInS * numTones);
		            			//i_startConseq = lastNoiseIndex; //grandBuffer.size() - (conseqCount - 1) * expectedSelfSpacing;
		            		}
	            		} else
	            		if(!heardOther) {
	            			if(((i-i_lastZero) >= (expectedOtherSpacing - 1)) && 
	            					((i-i_lastZero) <= (expectedOtherSpacing + 1))) {
	            				conseqCount++;
	            				Log.d(TAG, "Good spacing " + String.valueOf(i));
	            			} else {
	            				conseqCount = 0;
	            				Log.d(TAG, "Bad spacing " + String.valueOf(i));
	            			}
	            			if(conseqCount >= CONSEQ_COUNT_THRESHOLD) {
	            				heardOther = true;
	            				heardSelf = false;
	            				indexToPlaySound = grandBuffer.size() + (int) (micSampleRate * outToneDurationInS * numTones) + numSamplesInSilence;
		            			//i_startConseq = lastNoiseIndex; //grandBuffer.size() - (conseqCount - 1) * expectedOtherSpacing;
		            		}
	            		}
	            		i_lastZero = i;
	            	}
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
		protected void onProgressUpdate(Boolean... isRecordingFinished) {
        	if(isRecordingFinished[0]) {
				mSaveButton.setText("Stop and analyze");
				mSaveButton.performClick();
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
        
        private int expectedZerosSpacing(int sampleRate, int pitch) {
        	double numTsInT = sampleRate/pitch;
        	int expectedSpacing = (int) numTsInT / 2; // index spacing, 2 zero crossings in a single sinusoid
        	Log.d(TAG, "Expected spacing = " + String.valueOf(expectedSpacing));
        	Log.d(TAG, "sample rate = " + String.valueOf(sampleRate) + " pitch = " + String.valueOf(pitch));
        	return expectedSpacing;
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
        	Log.d("Spacing", "Consec pattern threshold = " + String.valueOf(CONSEC_PATTERN_THRESHOLD));
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
        	heardSelf = false;
        	heardOther = false;
        }
	}
    
/*************************************************************
 * DATA ANALYSIS
 *************************************************************/
	private int expectedZerosSpacing(int sampleRate, int pitch) {
    	double Ts = 1.0 / sampleRate;
    	double T = 1.0 / pitch;
    	int numTsInT = (int) (T/Ts);
    	int expectedSpacing = numTsInT / 2;
    	return expectedSpacing;
    }
	private void bufferAnalysis() {
		int expectedSelfSpacing = expectedZerosSpacing(micSampleRate, OTHER_TONE_FREQUENCY);
		int expectedSelfSpacing_2 = expectedZerosSpacing(micSampleRate, OTHER_TONE_FREQUENCY_2);
    	
    	int expectedSpacing_1 = expectedSelfSpacing;
    	int expectedSpacing_2 = expectedSelfSpacing_2;
    	
    	int listeningFor = 1;
    	
		final int CONSEQ_COUNT_THRESHOLD = 10;
		int i_lastZero = 0;
    	int conseqCount = 0;
    	
    	// Contains the index of the edge of each occurence of the second tone in each captured tone pair
    	selfToneEdges.clear();
    	otherToneEdges.clear();
    	
    	for (int i = 1; i < grandBuffer.size(); i++) {
			
        	if((conseqCount < CONSEQ_COUNT_THRESHOLD) &&
        		((grandBuffer.get(i) < 0 && grandBuffer.get(i-1) > 0) || (grandBuffer.get(i) > 0 && grandBuffer.get(i-1) < 0))) {
        		//Log.d(TAG, "Found a zero at index " + String.valueOf(i));
        		if(listeningFor == 1) {
        			Log.d(TAG, "Listening for 1");
        			if(((i-i_lastZero) >= (expectedSpacing_1 - 1)) && 
        					((i-i_lastZero) <= (expectedSpacing_1 + 1))) {
        				conseqCount++;
        				//Log.d(TAG, "Good spacing " + String.valueOf(i));
        			} else {
        				conseqCount = 0;
        				//Log.d(TAG, "Bad spacing " + String.valueOf(i));
        			}
        			if(conseqCount >= CONSEQ_COUNT_THRESHOLD) {
        				Log.d(TAG, "Transitioning to 2");
        				//heard first tone
        				listeningFor = 2;
            			conseqCount = 0;
            		}
        			
        		} else if(listeningFor == 2) {
        			Log.d(TAG, "Listening for 2");
        			if(((i-i_lastZero) >= (expectedSpacing_2 - 1)) && 
        					((i-i_lastZero) <= (expectedSpacing_2 + 1))) {
        				
        				if(otherToneEdges.size() < numTones/2) {
        					otherToneEdges.add(i);
        					Log.d(TAG, "Add to other");
        					if(otherToneEdges.size() >= numTones/2) {
        						expectedSpacing_1 = expectedZerosSpacing(micSampleRate, SELF_TONE_FREQUENCY);
            					expectedSpacing_2 = expectedZerosSpacing(micSampleRate, SELF_TONE_FREQUENCY_2);
            					Log.d(TAG, "Change expectations");
        					}
        				} else if(otherToneEdges.size() >= numTones/2 && selfToneEdges.size() < numTones/2) {
        					selfToneEdges.add(i);
        					Log.d(TAG, "Add to self");
        					if(selfToneEdges.size() >= numTones/2) {
        						Log.d(TAG, "break");
            					break;
        					}
        					
        				}
        				listeningFor = 1;
        			}
        		}
        		i_lastZero = i;
        	}
        }
    	
		if(selfToneEdges.size() >= numTones / 2 && otherToneEdges.size() >= numTones / 2) {
			mSelfToneIndexText.setText("Self tone index: " + String.valueOf(selfToneEdges.get(0)) + " ,"
					+ String.valueOf(selfToneEdges.get(1)) + " ,"
					+ String.valueOf(selfToneEdges.get(2)));
			mOtherToneIndexText.setText("Other tone index: " + String.valueOf(otherToneEdges.get(0)) + " ,"
					+ String.valueOf(otherToneEdges.get(1)) + " ,"
					+ String.valueOf(otherToneEdges.get(2)));
			mIndexDiffText.setText("Index Diff: " + String.valueOf(selfToneEdges.get(0) - otherToneEdges.get(0)) + " ,"
					+ String.valueOf(selfToneEdges.get(1) - otherToneEdges.get(1)) + " ,"
					+ String.valueOf(selfToneEdges.get(2) - otherToneEdges.get(2)));
		} else {
				mIndexDiffText.setText("Only managed to find " + otherToneEdges.size() + " other tones and " + selfToneEdges.size() + " self tones.");
		}
	}
/*************************************************************
 * FILE IO OPERATIONS
 *************************************************************/
    /**
     * Write all grandBuffer contents to a csv file.
     * @param buffer
     */
    private void createBigBufferFile(List<Short> buffer, List<Integer>selfEdges, List<Integer>otherEdges) {
    	Calendar c = Calendar.getInstance(); 
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
        dateFormat.setTimeZone(c.getTimeZone());
        timeFormat.setTimeZone(c.getTimeZone());
    	String FILE_NAME = timeFormat.format(c.getTime())+"_SlaveRecord.csv";
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
    	    	writeCsvHeader("Self edges", "Other edges");
    	    	for(int i = 0; i < Math.min(selfEdges.size(), otherEdges.size()); i++) {
    	    		writeCsvHeader(String.valueOf(selfEdges.get(i)), String.valueOf(otherEdges.get(i)));
    	    	}
    	    	// Write contents of buffer in
	            for(int i = 0; i < buffer.size(); i++) {
	            	String line = buffer.get(i).toString() + "\n";
	          	  	writer.write(line);
	            }
	            
	            writer.flush();
	            writer.close(); 
	            Log.d("FILEIO", "FINISHED WRITING");
	            Toast.makeText(this, "Buffer written to file.", Toast.LENGTH_SHORT).show();
	            mStatusText.setText(init_s);
    	    } catch (IOException e) {
    	        e.printStackTrace();
    	    }    
    	} else {
    	
    		Log.d("ERROR", "External storage not writable");
    	}
    }
    
    private void createBigDiffFile(List<Integer> buffer) {
    	Calendar c = Calendar.getInstance(); 
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
        dateFormat.setTimeZone(c.getTimeZone());
        timeFormat.setTimeZone(c.getTimeZone());
    	String FILE_NAME = timeFormat.format(c.getTime())+"_DiffList.csv";
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
	            mStatusText.setText(init_s);
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

    private void writeCsvHeader(String h1, String h2) throws IOException {
	   String line = String.format(" ,%s,%s\n", h1,h2);
	   writer.write(line);
	   }

	private void writeCsvData(long selfTime, long waitTime, long selfAudioRecordDelay) throws IOException {  
	  String line = String.format("%d,%d,%d\n", selfTime, waitTime, selfAudioRecordDelay);
	  writer.write(line);
	}
}