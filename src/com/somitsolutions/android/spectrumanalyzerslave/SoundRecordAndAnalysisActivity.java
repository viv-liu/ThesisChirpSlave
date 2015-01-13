package com.somitsolutions.android.spectrumanalyzerslave;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import ca.uol.aig.fftpack.RealDoubleFFT;


public class SoundRecordAndAnalysisActivity extends Activity implements OnClickListener{
	public static final String TAG = "SoundRecordAndAnalysisActivitySlave";
	private final int SPEED_OF_SOUND = 344;
	
	int frequency = 48000;
    int channelConfiguration = AudioFormat.CHANNEL_CONFIGURATION_MONO;
    int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;

    private final int sampleRate = 48000;
    private final int NUM_SAMPLES = sampleRate/2;//duration * sampleRate;
    private final double sample[] = new double[NUM_SAMPLES];
    private final int freqOfTone = 880; // hz A5?

    private AudioTrack mAudioTrack;
    private final byte generatedSnd[] = new byte[2 * NUM_SAMPLES];
    
    AudioRecord audioRecord;
    private RealDoubleFFT transformer;
    int blockSize;// = 256;
    private Button startStopButton;
    boolean started = false;
    boolean playedSound = false;

    private long mSendTime = 0;
    private long mReceiveTime = 0;
    private final int num_records = 300;
    private int times_index = 0;
    private long timesRecord[] = new long[num_records];
    private long selfSpeakerDelay;
    
    RecordAudio recordTask;
    ImageView imageViewDisplaySpectrum;
    MyImageView imageViewScale;
    Bitmap bitmapDisplaySpectrum;
    TextView selfStatusText;
    
    Canvas canvasDisplaySpectrum;
    
    
    Paint paintSpectrumDisplay;
    Paint paintScaleDisplay;
    static SoundRecordAndAnalysisActivity mainActivity;
    LinearLayout main;
    int width;
    int height;
    int left_Of_BitmapScale;
    int left_Of_DisplaySpectrum;
    private final static int ID_BITMAPDISPLAYSPECTRUM = 1;
    private final static int ID_IMAGEVIEWSCALE = 2;
    
    // Views
    private Button mPlayButton;
    private Button mResetButton;
    
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        genTone();
        
     // Initialize audio 
        mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, generatedSnd.length,
                AudioTrack.MODE_STATIC);
        mAudioTrack.write(generatedSnd, 0, generatedSnd.length);
        
        Display display = getWindowManager().getDefaultDisplay();
    	//Point size = new Point();
    	//display.get(size);
    	width = display.getWidth();
    	height = display.getHeight();
    	
    	getValidSampleRates();
    	
		blockSize = 256;
    }
    
    void genTone(){
        // fill out the array
        for (int i = 0; i < NUM_SAMPLES; ++i) {
            sample[i] = Math.sin(2 * Math.PI * i / (sampleRate/freqOfTone));
        }

        // convert to 16 bit PCM sound array
        // assumes the sample buffer is normalized.
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
    	if(!playedSound) {
	    	mAudioTrack.stop();
	    	mAudioTrack.reloadStaticData();
	        startTiming();
	        mAudioTrack.play();
	        playedSound = true;
        }
    }
    
    void startTiming() {
    	mSendTime = System.nanoTime();
    }
    
    long stopTiming() {
    	mReceiveTime = System.nanoTime();
    	long delta = mReceiveTime - mSendTime;
    	/*if(times_index + 1 < num_records - 1) {
    		timesRecord[times_index++] = delta;
    	}*/
    	Log.d(TAG, "Time of flight = " + String.valueOf(delta) + "ns == " 
														+ String.valueOf(delta/1000000000.0) + "s");
		Log.d(TAG, "Distance approx = " + String.valueOf(delta/1000000000.0 * SPEED_OF_SOUND));
		return delta;
    }
    
    @Override
	public void onWindowFocusChanged (boolean hasFocus) {
    	//left_Of_BimapScale = main.getC.getLeft();
    	MyImageView  scale = (MyImageView)main.findViewById(ID_IMAGEVIEWSCALE);
    	ImageView bitmap = (ImageView)main.findViewById(ID_BITMAPDISPLAYSPECTRUM);
    	left_Of_BitmapScale = scale.getLeft();
    	left_Of_DisplaySpectrum = bitmap.getLeft();
    }
    private class RecordAudio extends AsyncTask<Void, Boolean, Void> {
    	private LinkedList<Double> selfFiveBuffer = new LinkedList<Double>();
    	private LinkedList<Double> receiveFiveBuffer = new LinkedList<Double>();
    	double selfFiveAverage;
    	double receiveFiveAverage;
   
        @Override
        protected Void doInBackground(Void... params) {
       
        	if(isCancelled()){
        		return null;
        	}
        	List<Integer> zeroCrossingIndices = new ArrayList<Integer>();
            int bufferSize = AudioRecord.getMinBufferSize(frequency, channelConfiguration, audioEncoding);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, frequency, channelConfiguration, audioEncoding, bufferSize);
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
            	zeroCrossingIndices.clear();
            	bufferReadResult = audioRecord.read(buffer, 0, blockSize);
            
            	if(isCancelled())
                    	break;

            	double buffer_i;
            	double buffer_i_prev;
	            for (int i = 1; i < blockSize && i < bufferReadResult; i++) {
	            	buffer_i = (double) buffer[i] / 32768.0;
	            	buffer_i_prev = (double) buffer[i - 1] / 32768.0;
	            	
	            	if(buffer_i < 0.0 && buffer_i_prev > 0.0 || buffer_i > 0.0 && buffer_i_prev < 0.0) {
	            		zeroCrossingIndices.add(i);
	            		// zero crossing detected
	            		Log.d(TAG, "zero crossing at index i = " + String.valueOf(i));
	            	}
	                //toTransform[i] = (double) buffer[i] / 32768.0; // signed 16 bit
	            }
	            
	            if(detectTone(sampleRate, freqOfTone, zeroCrossingIndices)) {
            		Log.e(TAG, "Detected self tone " + String.valueOf(freqOfTone));
            		selfSpeakerDelay = stopTiming();
            		publishProgress(false);
            	}
            	if(detectTone(sampleRate, freqOfTone / 2, zeroCrossingIndices)) {
            		Log.e(TAG, "Detected receive tone = " + String.valueOf(freqOfTone / 2));
            		playSound();
            	}

	            //transformer.ft(toTransform);
	            //publishProgress(toTransform);
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
        
        /*protected void onProgressUpdate(double[]... toTransform) {
        	Log.e("RecordingProgress", "Displaying in progress");
        	double magnitudeSelfTone = toTransform[0][880*64/1000];
        	double magnitudeReceiveTone = toTransform[0][440*64/1000];
        	
        	if(selfFiveBuffer.size() >= 3) {
        		selfFiveBuffer.remove(0);
        	}
        	selfFiveBuffer.add(magnitudeSelfTone);
        	for(int i = 0; i < selfFiveBuffer.size(); i++) {
        		selfFiveAverage += selfFiveBuffer.get(i);
        	}
        	selfFiveAverage /= selfFiveBuffer.size();
        	selfFiveAverage = magnitudeSelfTone;
        	if(selfFiveAverage > 1.5) {
        		if(selfSpeakerDelay <= 0) {
	        		selfSpeakerDelay = stopTiming();
	        		Log.d(TAG, "DETECTED 880Hz, selfFiveAverage = " + String.valueOf(selfFiveAverage));
	        		selfStatusText.setText("selfSpeakerDelay = " + String.valueOf(selfSpeakerDelay/1000000.0) + "ms" + 
	        		"\nselfFiveAverage = " + String.valueOf(selfFiveAverage));
        		}
        	}
        	
        	if(receiveFiveBuffer.size() >= 3) {
        		receiveFiveBuffer.remove(0);
        	}
        	receiveFiveBuffer.add(magnitudeReceiveTone);
        	for(int i = 0; i < receiveFiveBuffer.size(); i++) {
        		receiveFiveAverage += receiveFiveBuffer.get(i);
        	}
        	receiveFiveAverage /= receiveFiveBuffer.size();
        	receiveFiveAverage = magnitudeReceiveTone;
        	if(receiveFiveAverage > 1.5) {
        		Log.d(TAG, "DETECTED 440Hz, receiveFiveAverage = " + String.valueOf(receiveFiveAverage));
        		playSound();
        	}
        	
        	if (width > 512){
        		for (int i = 0; i < toTransform[0].length; i++) {
                    int x = 2*i;
                    int downy = (int) (150 - (toTransform[0][i] * 10));
                    int upy = 150;
                    canvasDisplaySpectrum.drawLine(x, downy, x, upy, paintSpectrumDisplay);
                    }
                    
                    imageViewDisplaySpectrum.invalidate();
               }
        	
        	else{
        		for (int i = 0; i < toTransform[0].length; i++) {
                    int x = i;
                    int downy = (int) (150 - (toTransform[0][i] * 10));
                    int upy = 150;
                    canvasDisplaySpectrum.drawLine(x, downy, x, upy, paintSpectrumDisplay);
                    }
                    
                    imageViewDisplaySpectrum.invalidate();
                    }
                
        	}*/
        protected void onProgressUpdate(Boolean receiveComplete) {
        	if(!receiveComplete) {
    			selfStatusText.setText("selfSpeakerDelay = " + String.valueOf(selfSpeakerDelay/1000000.0) + "ms");
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
        	//}
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
        	double Ts = 1.0 / sampleRate;
        	double T = 1.0 / pitch;
        	
        	int numTsInT = (int) (T/Ts);
        	
        	int expectedSpacing = numTsInT / 2; // index spacing, 2 zero crossings in a single sinusoid
        	
        	int a = indices.get(0);
        	int b;
        	int conseqCount = 0;
        	for(int i = 1; i < indices.size(); i++) {
        		b = indices.get(i);
        		if(b - a >= expectedSpacing - 1 && b - a <= expectedSpacing + 1) {
        			if(++conseqCount >= 20) {
        				return true;
        			}
        		} else {
        			conseqCount = 0;
        		}
        		a = b;
        	}
        	return false;
        }
                
       }
   
        public void onClick(View v) {
        
        if (started == true) {
	        started = false;
	        startStopButton.setText("Start");
	        recordTask.cancel(true);
	        //recordTask = null;
	        canvasDisplaySpectrum.drawColor(Color.BLACK);
        } else {
        	selfSpeakerDelay = 0;
        	playedSound = false;
	        started = true;
	        startStopButton.setText("Stop");
	        recordTask = new RecordAudio();
	        recordTask.execute();
        }  
        
     }
        
        static SoundRecordAndAnalysisActivity getMainActivity(){
        	return mainActivity;
        }
        
        public void onStop(){
        	super.onStop();
        	/*started = false;
            startStopButton.setText("Start");*/
            //if(recordTask != null){
            	recordTask.cancel(true); 
            //}
            Intent intent = new Intent(Intent.ACTION_MAIN);
        	intent.addCategory(Intent.CATEGORY_HOME);
        	intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        	startActivity(intent);
        }
        
        public void onStart(){
        	
        	super.onStart();
        	
        	main = new LinearLayout(this);
        	main.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        	main.setOrientation(LinearLayout.VERTICAL);
        	setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        	requestWindowFeature(Window.FEATURE_NO_TITLE);
        	getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
        	WindowManager.LayoutParams.FLAG_FULLSCREEN);
        	transformer = new RealDoubleFFT(blockSize);
        	imageViewDisplaySpectrum = new ImageView(this);
        	if(width > 512){
        		bitmapDisplaySpectrum = Bitmap.createBitmap((int)512,(int)300,Bitmap.Config.ARGB_8888);
        	}
        	else{
        		bitmapDisplaySpectrum = Bitmap.createBitmap((int)256,(int)150,Bitmap.Config.ARGB_8888);
        	}
        	LinearLayout.LayoutParams layoutParams_imageViewScale = null;
        	
        	canvasDisplaySpectrum = new Canvas(bitmapDisplaySpectrum);
        	paintSpectrumDisplay = new Paint();
        	paintSpectrumDisplay.setColor(Color.GREEN);
        	imageViewDisplaySpectrum.setImageBitmap(bitmapDisplaySpectrum);
        	
        	if(width >512){
        	
	        	LinearLayout.LayoutParams layoutParams_imageViewDisplaySpectrum=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
	        	((MarginLayoutParams) layoutParams_imageViewDisplaySpectrum).setMargins(100, 600, 0, 0);
	        	imageViewDisplaySpectrum.setLayoutParams(layoutParams_imageViewDisplaySpectrum);
	        	layoutParams_imageViewScale= new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
	        	((MarginLayoutParams) layoutParams_imageViewScale).setMargins(100, 20, 0, 0);
        	}
        	else if ((width >320) && (width<512)){
	        	LinearLayout.LayoutParams layoutParams_imageViewDisplaySpectrum=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
	        	((MarginLayoutParams) layoutParams_imageViewDisplaySpectrum).setMargins(60, 250, 0, 0);
	        	imageViewDisplaySpectrum.setLayoutParams(layoutParams_imageViewDisplaySpectrum);
	        	layoutParams_imageViewScale=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
	        	((MarginLayoutParams) layoutParams_imageViewScale).setMargins(60, 20, 0, 100);
        	}
        	else if (width < 320){
        		imageViewDisplaySpectrum.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));
        		layoutParams_imageViewScale=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        	}
        	imageViewDisplaySpectrum.setId(ID_BITMAPDISPLAYSPECTRUM);
        	main.addView(imageViewDisplaySpectrum);
        	imageViewScale = new MyImageView(this);
        	imageViewScale.setLayoutParams(layoutParams_imageViewScale);
        	imageViewScale.setId(ID_IMAGEVIEWSCALE);
        	main.addView(imageViewScale);
        	startStopButton = new Button(this);
        	startStopButton.setText("Start");
        	startStopButton.setOnClickListener(this);
        	startStopButton.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));
        	main.addView(startStopButton);
        	selfStatusText = new TextView(this);
        	main.addView(selfStatusText);
        	
        	setContentView(main);
        	mainActivity = this;
        	}
        
		@Override
			public void onBackPressed() {
			super.onBackPressed();
			recordTask.cancel(true);
			Intent intent = new Intent(Intent.ACTION_MAIN);
			intent.addCategory(Intent.CATEGORY_HOME);
			intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(intent);
		}
		@Override
		protected void onDestroy() {
			super.onDestroy();
			recordTask.cancel(true);
			Intent intent = new Intent(Intent.ACTION_MAIN);
			intent.addCategory(Intent.CATEGORY_HOME);
			intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		startActivity(intent);
		}
	//Custom Imageview Class
	public class MyImageView extends ImageView {
		Paint paintScaleDisplay;
		Bitmap bitmapScale;
		Canvas canvasScale;
		//Bitmap scaled;
		public MyImageView(Context context) {
			super(context);
			// TODO Auto-generated constructor stub
			if(width >512){
				bitmapScale = Bitmap.createBitmap((int)512,(int)50,Bitmap.Config.ARGB_8888);
			}
			else{
				bitmapScale = Bitmap.createBitmap((int)256,(int)50,Bitmap.Config.ARGB_8888);
			}
			paintScaleDisplay = new Paint();
			paintScaleDisplay.setColor(Color.WHITE);
			paintScaleDisplay.setStyle(Paint.Style.FILL);
			canvasScale = new Canvas(bitmapScale);
			setImageBitmap(bitmapScale);
			invalidate();
		}
		@Override
		protected void onDraw(Canvas canvas)
		{
			super.onDraw(canvas);
			if(width > 512){
				canvasScale.drawLine(0, 30, 512, 30, paintScaleDisplay);
				for(int i = 0,j = 0; i< 512; i=i+128, j++){
					for (int k = i; k<(i+128); k=k+16){
						canvasScale.drawLine(k, 30, k, 25, paintScaleDisplay);
					}
					canvasScale.drawLine(i, 40, i, 25, paintScaleDisplay);
					String text = Integer.toString(j) + " KHz";
					canvasScale.drawText(text, i, 45, paintScaleDisplay);
				}
				canvas.drawBitmap(bitmapScale, 0, 0, paintScaleDisplay);
			}
			else if ((width >320) && (width<512)){
				canvasScale.drawLine(0, 30, 0 + 256, 30, paintScaleDisplay);
				for(int i = 0,j = 0; i<256; i=i+64, j++){
					for (int k = i; k<(i+64); k=k+8){
						canvasScale.drawLine(k, 30, k, 25, paintScaleDisplay);
					}
					canvasScale.drawLine(i, 40, i, 25, paintScaleDisplay);
					String text = Integer.toString(j) + " KHz";
					canvasScale.drawText(text, i, 45, paintScaleDisplay);
				}
				canvas.drawBitmap(bitmapScale, 0, 0, paintScaleDisplay);
			}
			else if (width <320){
				canvasScale.drawLine(0, 30, 256, 30, paintScaleDisplay);
				for(int i = 0,j = 0; i<256; i=i+64, j++){
					for (int k = i; k<(i+64); k=k+8){
						canvasScale.drawLine(k, 30, k, 25, paintScaleDisplay);
					}
					canvasScale.drawLine(i, 40, i, 25, paintScaleDisplay);
					String text = Integer.toString(j) + " KHz";
					canvasScale.drawText(text, i, 45, paintScaleDisplay);
				}
				canvas.drawBitmap(bitmapScale, 0, 0, paintScaleDisplay);
			}
		}
	}

	public void getValidSampleRates() {
        for (int rate : new int[] {8000, 11025, 16000, 22050, 44100, 48000}) {  // add the rates you wish to check against
            int bufferSize = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_CONFIGURATION_DEFAULT, AudioFormat.ENCODING_PCM_16BIT);
            if (bufferSize > 0) {
                Log.d(TAG, "Rate is allowed: " + String.valueOf(rate));
            }
        }
    }    
}
    
