package com.translator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 100;
    private static final int REQUEST_CODE_OVERLAY_PERMISSION = 101;
    private static final int REQUEST_CODE_LANGUAGE_SELECTOR = 102;
    private MediaProjectionManager projectionManager;
    private String selectedLanguage = "en";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        Log.d(TAG, "onCreate: Starting activity");
        
        // Vérifier et demander la permission d'overlay
        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "onCreate: Requesting overlay permission");
            Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION);
            return;
        }
        
        // Si on a déjà la permission d'overlay, on affiche le sélecteur de langue
        showLanguageSelector();
    }

    private void showLanguageSelector() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.language_selector, null);
        
        Spinner spinner = view.findViewById(R.id.language_spinner);
        Button confirmButton = view.findViewById(R.id.confirm_button);

        // Préparer la liste des langues
        Map<String, String> languages = new LinkedHashMap<>();
        languages.put("fr", "Français");
        languages.put("en", "Anglais");
        languages.put("es", "Espagnol");
        languages.put("de", "Allemand");
        languages.put("it", "Italien");
        languages.put("pt", "Portugais");
        languages.put("ru", "Russe");
        languages.put("ja", "Japonais");
        languages.put("ko", "Coréen");
        languages.put("zh", "Chinois");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this, 
            android.R.layout.simple_spinner_item, 
            new ArrayList<>(languages.values())
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        AlertDialog dialog = builder.setView(view).setCancelable(false).create();

        confirmButton.setOnClickListener(v -> {
            int position = spinner.getSelectedItemPosition();
            selectedLanguage = new ArrayList<>(languages.keySet()).get(position);
            dialog.dismiss();
            startScreenCapture();
        });

        dialog.show();
    }

    private void startScreenCapture() {
        try {
            Log.d(TAG, "startScreenCapture: Setting up projection manager");
            projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (projectionManager == null) {
                throw new Exception("Could not get MediaProjectionManager");
            }
            
            Intent captureIntent = projectionManager.createScreenCaptureIntent();
            Log.d(TAG, "startScreenCapture: Starting capture intent");
            startActivityForResult(captureIntent, REQUEST_CODE_SCREEN_CAPTURE);
        } catch (Exception e) {
            Log.e(TAG, "startScreenCapture: Error", e);
            showError("Erreur lors du démarrage de la capture d'écran");
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode + ", data=" + (data != null));
        
        if (requestCode == REQUEST_CODE_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                Log.d(TAG, "onActivityResult: Overlay permission granted");
                showLanguageSelector();
            } else {
                Log.e(TAG, "onActivityResult: Overlay permission denied");
                showError("Permission d'affichage nécessaire");
                finish();
            }
        }
        else if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d(TAG, "onActivityResult: Screen capture permission granted");
                startBubbleService(resultCode, data);
            } else {
                Log.e(TAG, "onActivityResult: Screen capture permission denied");
                showError("Permission de capture d'écran nécessaire");
                finish();
            }
        }
    }

    private void startBubbleService(int resultCode, Intent data) {
        try {
            Log.d(TAG, "startBubbleService: Creating service intent");
            Toast.makeText(this, "Démarrage du service...", Toast.LENGTH_SHORT).show();
            
            Intent serviceIntent = new Intent(this, BubbleService.class);
            serviceIntent.putExtra("result_code", resultCode);
            serviceIntent.putExtra("projection_data", data);
            serviceIntent.putExtra("target_language", selectedLanguage);
            
            Log.d(TAG, "startBubbleService: Starting foreground service");
            startForegroundService(serviceIntent);
            
            // On attend un peu pour s'assurer que le service démarre
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Log.e(TAG, "startBubbleService: Sleep interrupted", e);
            }
            
            // On vérifie si le service est en cours d'exécution
            boolean isServiceRunning = false;
            android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (BubbleService.class.getName().equals(service.service.getClassName())) {
                    isServiceRunning = true;
                    break;
                }
            }
            
            if (!isServiceRunning) {
                String error = "Le service n'a pas démarré correctement. Vérifiez les permissions.";
                Log.e(TAG, "startBubbleService: Service not running");
                Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                return;
            }
            
            Log.d(TAG, "startBubbleService: Service started successfully");
            Toast.makeText(this, "Service démarré avec succès", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            String error = "Erreur lors du démarrage du service: " + e.getMessage();
            Log.e(TAG, "startBubbleService: Error", e);
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        }
    }

    private void showError(String message) {
        Log.e(TAG, "showError: " + message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}