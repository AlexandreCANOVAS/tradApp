package com.translator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;

public class BubbleService extends Service {
    private static final String TAG = "BubbleService";
    private static final String CHANNEL_ID = "BubbleServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int CAPTURE_INTERVAL = 1000; // 1 seconde entre chaque capture

    private WindowManager windowManager;
    private View bubbleView;
    private TextView textOriginal;
    private TextView textTranslated;
    private View translationContainer;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private TextRecognizer textRecognizer;
    private Translator translator;
    private Handler handler;
    private String targetLanguage = "en";
    private int resultCode;
    private Intent data;
    private boolean isProcessingImage = false;
    private Handler captureHandler;
    private final Runnable captureRunnable = new Runnable() {
        @Override
        public void run() {
            if (imageReader != null && !isProcessingImage) {
                Image image = imageReader.acquireLatestImage();
                if (image != null) {
                    processImage(image);
                    image.close();
                }
            }
            captureHandler.postDelayed(this, CAPTURE_INTERVAL);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Toast.makeText(this, "Démarrage du service...", Toast.LENGTH_SHORT).show();
        Log.d(TAG, "onCreate: Starting BubbleService");
        
        try {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, createNotification());
            
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                throw new Exception("Could not get WindowManager");
            }
            
            handler = new Handler(Looper.getMainLooper());
            
            // Initialiser le recognizer et translator avant tout
            setupTextRecognizer();
            setupTranslator();
            
            createBubbleView();
            Toast.makeText(this, "Service démarré avec succès", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "onCreate: BubbleService started successfully");
        } catch (Exception e) {
            String error = "Erreur: " + e.getMessage();
            Log.e(TAG, "onCreate: Error", e);
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private void setupTextRecognizer() {
        try {
            Log.d(TAG, "setupTextRecognizer: Initializing text recognizer");
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            if (textRecognizer == null) {
                throw new Exception("Failed to create TextRecognizer");
            }
            Log.d(TAG, "setupTextRecognizer: Text recognizer initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "setupTextRecognizer: Error", e);
            showToast("Erreur d'initialisation du recognizer");
            stopSelf();
        }
    }

    private void setupTranslator() {
        try {
            Log.d(TAG, "setupTranslator: Initializing translator");
            TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.FRENCH)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build();
            
            translator = Translation.getClient(options);
            
            // Télécharger le modèle et attendre qu'il soit prêt
            translator.downloadModelIfNeeded()
                .addOnSuccessListener(unused -> {
                    Log.d(TAG, "setupTranslator: Model downloaded successfully");
                    showToast("Modèle de traduction prêt");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "setupTranslator: Model download failed", e);
                    showToast("Erreur de téléchargement du modèle");
                    stopSelf();
                });
        } catch (Exception e) {
            Log.e(TAG, "setupTranslator: Error", e);
            showToast("Erreur d'initialisation du traducteur");
            stopSelf();
        }
    }

    private void createBubbleView() {
        Log.d(TAG, "createBubbleView: Creating bubble view");
        
        try {
            // Inflate the bubble layout
            LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            bubbleView = inflater.inflate(R.layout.bubble_layout, null);
            
            // Get views
            textOriginal = bubbleView.findViewById(R.id.text_original);
            textTranslated = bubbleView.findViewById(R.id.text_translated);
            translationContainer = bubbleView.findViewById(R.id.translation_container);
            
            // Setup window parameters
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            );
            
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 100;
            params.y = 100;
            
            // Add touch listener
            bubbleView.setOnTouchListener(new BubbleTouchListener());
            
            // Add the view to window
            Log.d(TAG, "createBubbleView: Adding view to window manager");
            windowManager.addView(bubbleView, params);
            
            Log.d(TAG, "createBubbleView: Bubble view created successfully");
        } catch (Exception e) {
            Log.e(TAG, "createBubbleView: Error", e);
            showToast("Erreur lors de la création de la bulle");
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: Received start command");
        
        if (intent != null) {
            resultCode = intent.getIntExtra("result_code", 0);
            data = intent.getParcelableExtra("projection_data");
            targetLanguage = intent.getStringExtra("target_language");
            
            Log.d(TAG, "onStartCommand: resultCode=" + resultCode + ", data=" + (data != null));
            
            if (resultCode != 0 && data != null) {
                setupMediaProjection();
            } else {
                Log.e(TAG, "onStartCommand: Invalid projection data");
                stopSelf();
            }
        } else {
            Log.e(TAG, "onStartCommand: Null intent received");
            stopSelf();
        }
        
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Bubble Service Channel",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Traducteur Live")
            .setContentText("Service de traduction actif")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build();
    }

    private void setupMediaProjection() {
        try {
            MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            
            if (mediaProjection == null) {
                Log.e(TAG, "setupMediaProjection: Failed to get MediaProjection");
                return;
            }

            // Enregistrer le callback avant de créer le display
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.d(TAG, "MediaProjection stopped");
                    stopSelf();
                }
            }, new Handler());

            setupVirtualDisplay();
            Log.d(TAG, "setupMediaProjection: MediaProjection created successfully");
        } catch (Exception e) {
            Log.e(TAG, "setupMediaProjection: Error", e);
            stopSelf();
        }
    }

    private void setupVirtualDisplay() {
        try {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int screenWidth = metrics.widthPixels;
            int screenHeight = metrics.heightPixels;
            int screenDensity = metrics.densityDpi;

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(null, null); // On n'utilise plus le listener

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);

            Log.d(TAG, "setupVirtualDisplay: Virtual display created successfully");
        } catch (Exception e) {
            Log.e(TAG, "setupVirtualDisplay: Error", e);
            showToast("Erreur lors de la création de l'affichage virtuel");
            stopSelf();
        }
    }

    private void startCapture() {
        if (captureHandler == null) {
            captureHandler = new Handler(Looper.getMainLooper());
        }
        captureHandler.post(captureRunnable);
    }

    private void stopCapture() {
        if (captureHandler != null) {
            captureHandler.removeCallbacks(captureRunnable);
        }
    }

    private void processImage(Image image) {
        if (isProcessingImage || textRecognizer == null || translator == null) {
            return;
        }

        isProcessingImage = true;
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();

            // Réduire la taille de l'image pour de meilleures performances
            int scaleFactor = 2;
            int scaledWidth = width / scaleFactor;
            int scaledHeight = height / scaleFactor;

            Bitmap originalBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            originalBitmap.copyPixelsFromBuffer(buffer);

            // Redimensionner l'image
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, scaledWidth, scaledHeight, true);
            originalBitmap.recycle();

            InputImage inputImage = InputImage.fromBitmap(scaledBitmap, 0);
            
            textRecognizer.process(inputImage)
                .addOnSuccessListener(text -> {
                    String detectedText = text.getText().trim();
                    Log.d(TAG, "processImage: Detected text: " + detectedText);
                    
                    if (!detectedText.isEmpty()) {
                        handler.post(() -> {
                            textOriginal.setText(detectedText);
                            translationContainer.setVisibility(View.VISIBLE);
                            textTranslated.setText("Traduction en cours...");
                        });
                        
                        translateText(detectedText);
                    } else {
                        Log.d(TAG, "processImage: No text detected");
                    }
                    
                    scaledBitmap.recycle();
                    isProcessingImage = false;
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "processImage: Text recognition failed", e);
                    handler.post(() -> textTranslated.setText("Erreur de reconnaissance"));
                    scaledBitmap.recycle();
                    isProcessingImage = false;
                });
        } catch (Exception e) {
            Log.e(TAG, "processImage: Error", e);
            handler.post(() -> textTranslated.setText("Erreur de traitement"));
            isProcessingImage = false;
        }
    }

    private void translateText(String text) {
        if (translator == null) {
            Log.e(TAG, "translateText: Translator is null");
            showToast("Erreur: Translator non initialisé");
            return;
        }

        try {
            translator.translate(text)
                .addOnSuccessListener(translatedText -> {
                    Log.d(TAG, "translateText: Translation success: " + translatedText);
                    handler.post(() -> textTranslated.setText(translatedText));
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "translateText: Translation failed", e);
                    handler.post(() -> textTranslated.setText("Erreur de traduction"));
                    
                    // Si le translator est fermé, on le réinitialise
                    if (e instanceof IllegalStateException && 
                        e.getMessage() != null && 
                        e.getMessage().contains("Translator has been closed")) {
                        Log.d(TAG, "translateText: Reinitializing translator");
                        setupTranslator();
                    }
                });
        } catch (Exception e) {
            Log.e(TAG, "translateText: Error", e);
            handler.post(() -> textTranslated.setText("Erreur de traduction"));
        }
    }

    private void showToast(final String message) {
        handler.post(() -> Toast.makeText(BubbleService.this, message, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onDestroy() {
        stopCapture();
        Log.d(TAG, "onDestroy: Cleaning up resources");
        
        if (windowManager != null && bubbleView != null) {
            try {
                windowManager.removeView(bubbleView);
            } catch (Exception e) {
                Log.e(TAG, "onDestroy: Error removing view", e);
            }
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        if (translator != null) {
            try {
                translator.close();
            } catch (Exception e) {
                Log.e(TAG, "onDestroy: Error closing translator", e);
            }
            translator = null;
        }

        if (textRecognizer != null) {
            try {
                textRecognizer.close();
            } catch (Exception e) {
                Log.e(TAG, "onDestroy: Error closing text recognizer", e);
            }
            textRecognizer = null;
        }

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private class BubbleTouchListener implements View.OnTouchListener {
        private int initialX;
        private int initialY;
        private float initialTouchX;
        private float initialTouchY;
        private boolean isDragging = false;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) bubbleView.getLayoutParams();

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    isDragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float deltaX = event.getRawX() - initialTouchX;
                    float deltaY = event.getRawY() - initialTouchY;
                    
                    // Si le déplacement est significatif, c'est un drag
                    if (!isDragging && (Math.abs(deltaX) > 5 || Math.abs(deltaY) > 5)) {
                        isDragging = true;
                    }
                    
                    if (isDragging) {
                        params.x = initialX + (int) deltaX;
                        params.y = initialY + (int) deltaY;
                        windowManager.updateViewLayout(bubbleView, params);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        // C'était un clic, pas un drag
                        captureAndTranslate();
                    }
                    return true;
            }
            return false;
        }
    }

    private void captureAndTranslate() {
        if (imageReader == null || isProcessingImage) {
            return;
        }

        Image image = imageReader.acquireLatestImage();
        if (image != null) {
            processImage(image);
            image.close();
        }
    }
}