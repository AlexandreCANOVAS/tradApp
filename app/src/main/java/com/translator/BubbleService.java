package com.translator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
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
    private View overlayView;
    private TextView overlayText;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private TextRecognizer textRecognizer;
    private Translator translator;
    private LanguageIdentifier languageIdentifier;
    private Handler handler;
    private String targetLanguage = "en";
    private boolean isProcessingImage = false;
    private long lastProcessingTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: Starting service");
        
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            handler = new Handler(Looper.getMainLooper());
            
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, createNotification());
            
            createBubbleView();
            
            setupTextRecognizer();
            setupLanguageIdentifier();
            
            showToast("Service démarré");
        } catch (Exception e) {
            Log.e(TAG, "onCreate: Error", e);
            showToast("Erreur lors du démarrage du service");
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

    private void setupLanguageIdentifier() {
        languageIdentifier = LanguageIdentification.getClient();
    }

    private void setupTranslator(String sourceLanguage) {
        Log.d(TAG, "setupTranslator: Setting up translator from " + sourceLanguage + " to " + targetLanguage);
        if (translator != null) {
            translator.close();
        }
        
        TranslatorOptions options = new TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build();
            
        translator = Translation.getClient(options);
        
        translator.downloadModelIfNeeded()
            .addOnSuccessListener(unused -> {
                Log.d(TAG, "setupTranslator: Model downloaded successfully");
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "setupTranslator: Failed to download model", e);
                showToast("Erreur de téléchargement du modèle de traduction");
            });
    }

    private void createBubbleView() {
        try {
            bubbleView = LayoutInflater.from(this).inflate(R.layout.bubble_layout, null);
            
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

            bubbleView.setOnTouchListener(new View.OnTouchListener() {
                private int initialX;
                private int initialY;
                private float initialTouchX;
                private float initialTouchY;
                private boolean isDragging = false;
                private long touchStartTime;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    WindowManager.LayoutParams params = (WindowManager.LayoutParams) bubbleView.getLayoutParams();
                    
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            touchStartTime = System.currentTimeMillis();
                            initialX = params.x;
                            initialY = params.y;
                            initialTouchX = event.getRawX();
                            initialTouchY = event.getRawY();
                            isDragging = false;
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            float deltaX = event.getRawX() - initialTouchX;
                            float deltaY = event.getRawY() - initialTouchY;
                            
                            if (!isDragging && (Math.abs(deltaX) > 5 || Math.abs(deltaY) > 5)) {
                                isDragging = true;
                            }
                            
                            if (isDragging) {
                                params.x = initialX + (int) deltaX;
                                params.y = initialY + (int) deltaY;
                                try {
                                    windowManager.updateViewLayout(bubbleView, params);
                                } catch (Exception e) {
                                    Log.e(TAG, "Error updating bubble position", e);
                                }
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                            if (!isDragging && System.currentTimeMillis() - touchStartTime < 200) {
                                captureAndTranslate();
                            }
                            return true;
                    }
                    return false;
                }
            });
            
            windowManager.addView(bubbleView, params);
            
        } catch (Exception e) {
            Log.e(TAG, "createBubbleView: Error", e);
            showToast("Erreur lors de la création de la bulle");
        }
    }

    private void createOverlayView() {
        try {
            LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            overlayView = inflater.inflate(R.layout.overlay_layout, null);
            overlayText = overlayView.findViewById(R.id.overlay_text);
            
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            );
            
            params.gravity = Gravity.CENTER;
            
            windowManager.addView(overlayView, params);
            overlayView.setVisibility(View.GONE);
            
        } catch (Exception e) {
            Log.e(TAG, "createOverlayView: Error", e);
            showToast("Erreur lors de la création de l'overlay");
        }
    }

    private void setupMediaProjection(int resultCode, Intent data) {
        try {
            MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            
            if (mediaProjection == null) {
                Log.e(TAG, "setupMediaProjection: Failed to get MediaProjection");
                showToast("Erreur d'initialisation de la capture d'écran");
                stopSelf();
                return;
            }

            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.d(TAG, "MediaProjection stopped");
                    handler.post(() -> {
                        stopSelf();
                    });
                }
            }, handler);

            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int screenWidth = metrics.widthPixels;
            int screenHeight = metrics.heightPixels;
            int screenDensity = metrics.densityDpi;

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
            
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);

            Log.d(TAG, "setupMediaProjection: Virtual display created successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "setupMediaProjection: Error", e);
            showToast("Erreur lors de la configuration de la capture d'écran");
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: Received start command");
        
        try {
            if (intent != null) {
                int resultCode = intent.getIntExtra("result_code", 0);
                Intent data = intent.getParcelableExtra("projection_data");
                targetLanguage = intent.getStringExtra("target_language");
                
                Log.d(TAG, "onStartCommand: resultCode=" + resultCode + ", data=" + (data != null) + ", language=" + targetLanguage);
                
                if (resultCode != 0 && data != null) {
                    setupMediaProjection(resultCode, data);
                } else {
                    Log.e(TAG, "onStartCommand: Invalid data received");
                    stopSelf();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onStartCommand: Error", e);
            showToast("Erreur lors du démarrage du service");
            stopSelf();
        }
        
        return START_NOT_STICKY;
    }

    private void captureAndTranslate() {
        if (isProcessingImage) {
            long currentTime = System.currentTimeMillis();
            if (lastProcessingTime > 0 && currentTime - lastProcessingTime > 5000) {
                Log.d(TAG, "captureAndTranslate: Forcing reset of processing flag");
                isProcessingImage = false;
                lastProcessingTime = 0;
            } else {
                Log.d(TAG, "captureAndTranslate: Already processing image");
                return;
            }
        }
        
        if (imageReader == null || virtualDisplay == null) {
            Log.e(TAG, "captureAndTranslate: ImageReader or VirtualDisplay not ready");
            showToast("Service de capture non initialisé");
            return;
        }

        isProcessingImage = true;
        lastProcessingTime = System.currentTimeMillis();
        
        handler.postDelayed(() -> {
            try {
                Image image = imageReader.acquireLatestImage();
                if (image == null) {
                    Log.e(TAG, "captureAndTranslate: Failed to acquire image");
                    showToast("Erreur lors de la capture d'écran");
                    isProcessingImage = false;
                    lastProcessingTime = 0;
                    return;
                }

                Bitmap originalBitmap = imageToBitmap(image);
                image.close();

                if (originalBitmap == null) {
                    Log.e(TAG, "captureAndTranslate: Failed to convert image to bitmap");
                    showToast("Erreur lors de la conversion de l'image");
                    isProcessingImage = false;
                    lastProcessingTime = 0;
                    return;
                }

                processImage(originalBitmap);
                
            } catch (Exception e) {
                Log.e(TAG, "captureAndTranslate: Error", e);
                showToast("Erreur lors de la capture");
                isProcessingImage = false;
                lastProcessingTime = 0;
            }
        }, 100);
    }

    private Bitmap imageToBitmap(Image image) {
        if (image == null) return null;

        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;

        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        Bitmap bitmap = Bitmap.createBitmap(
            image.getWidth() + rowPadding / pixelStride,
            image.getHeight(),
            Bitmap.Config.ARGB_8888
        );

        bitmap.copyPixelsFromBuffer(buffer);
        return Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
    }

    private void processImage(Bitmap bitmap) {
        if (bitmap == null || textRecognizer == null) {
            Log.e(TAG, "processImage: Invalid bitmap or text recognizer");
            isProcessingImage = false;
            lastProcessingTime = 0;
            return;
        }

        InputImage inputImage = InputImage.fromBitmap(bitmap, 0);
        Log.d(TAG, "processImage: Starting text recognition");
        
        textRecognizer.process(inputImage)
            .addOnSuccessListener(visionText -> {
                boolean hasDetectedText = false;
                Log.d(TAG, "processImage: Text recognition success");
                
                for (Text.TextBlock block : visionText.getTextBlocks()) {
                    Rect blockRect = block.getBoundingBox();
                    String blockText = block.getText();
                    if (blockRect != null && !blockText.isEmpty()) {
                        hasDetectedText = true;
                        Log.d(TAG, "processImage: Found text block: " + blockText + " at position: " + blockRect);
                        translateTextBlock(blockText, blockRect);
                    }
                }
                
                if (!hasDetectedText) {
                    Log.d(TAG, "processImage: No text detected");
                    showToast("Aucun texte détecté");
                }
                
                isProcessingImage = false;
                lastProcessingTime = 0;
                bitmap.recycle();
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "processImage: Text recognition failed", e);
                showToast("Erreur de reconnaissance du texte");
                bitmap.recycle();
                isProcessingImage = false;
                lastProcessingTime = 0;
            });
    }

    private void translateTextBlock(String text, Rect blockRect) {
        if (translator == null) {
            Log.e(TAG, "translateTextBlock: Translator is null");
            setupTranslator("en");  // On essaie de réinitialiser le traducteur
            return;
        }

        Log.d(TAG, "translateTextBlock: Starting translation for text: " + text);
        translator.translate(text)
            .addOnSuccessListener(translatedText -> {
                Log.d(TAG, "translateTextBlock: Translation success: " + translatedText);
                handler.post(() -> {
                    TextView translationView = new TextView(this);
                    translationView.setText(translatedText);
                    translationView.setTextColor(Color.WHITE);
                    translationView.setBackgroundColor(Color.argb(180, 0, 0, 0));
                    translationView.setPadding(10, 5, 10, 5);
                    translationView.setTextSize(16); // Ajout d'une taille de texte plus grande

                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT
                    );

                    params.gravity = Gravity.TOP | Gravity.START;
                    params.x = blockRect.left;
                    params.y = blockRect.top;

                    try {
                        windowManager.addView(translationView, params);
                        Log.d(TAG, "translateTextBlock: Added translation view at position: " + params.x + ", " + params.y);
                        
                        handler.postDelayed(() -> {
                            try {
                                windowManager.removeView(translationView);
                                Log.d(TAG, "translateTextBlock: Removed translation view");
                            } catch (Exception e) {
                                Log.e(TAG, "Error removing translation view", e);
                            }
                        }, 5000);
                    } catch (Exception e) {
                        Log.e(TAG, "Error adding translation view", e);
                    }
                });
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Translation failed", e);
                showToast("Erreur de traduction");
            });
    }

    private void showToast(String message) {
        handler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: Cleaning up resources");
        
        if (windowManager != null) {
            if (bubbleView != null) {
                windowManager.removeView(bubbleView);
            }
            if (overlayView != null) {
                windowManager.removeView(overlayView);
            }
        }
        
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        
        if (mediaProjection != null) {
            mediaProjection.stop();
        }
        
        if (imageReader != null) {
            imageReader.close();
        }
        
        if (textRecognizer != null) {
            textRecognizer.close();
        }
        
        if (translator != null) {
            translator.close();
        }
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

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}