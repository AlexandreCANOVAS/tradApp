package com.translator;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 100;
    private static final int REQUEST_CODE_OVERLAY_PERMISSION = 101;
    private MediaProjectionManager projectionManager;
    private String selectedLanguage = "en";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION);
        } else {
            showLanguageSelector();
        }
    }

    private void showLanguageSelector() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.language_selector_dialog);
        
        // Définir la largeur du dialogue à 90% de la largeur de l'écran
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(dialog.getWindow().getAttributes());
        lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9);
        dialog.getWindow().setAttributes(lp);
        
        Spinner spinner = dialog.findViewById(R.id.languageSpinner);
        Button confirmButton = dialog.findViewById(R.id.confirmButton);
        
        // Configuration du Spinner
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            new String[]{"Français", "Anglais", "Espagnol", "Allemand", "Italien"}
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        
        confirmButton.setOnClickListener(v -> {
            String selectedLanguage = spinner.getSelectedItem().toString();
            String langCode = getLangCode(selectedLanguage);
            startScreenCapture(langCode);
            dialog.dismiss();
        });
        
        dialog.setCancelable(false);
        dialog.show();
    }
    
    private String getLangCode(String language) {
        switch (language) {
            case "Français": return "fr";
            case "Anglais": return "en";
            case "Espagnol": return "es";
            case "Allemand": return "de";
            case "Italien": return "it";
            default: return "en";
        }
    }

    private void startScreenCapture(String langCode) {
        selectedLanguage = langCode;
        try {
            projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE);
        } catch (Exception e) {
            Log.e(TAG, "startScreenCapture: Error", e);
            showError("Erreur lors du démarrage de la capture d'écran");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CODE_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                showLanguageSelector();
            } else {
                showError("Permission de superposition nécessaire");
                finish();
            }
        } else if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startBubbleService(resultCode, data);
            } else {
                showError("Capture d'écran non autorisée");
            }
        }
    }

    private void startBubbleService(int resultCode, Intent data) {
        Intent intent = new Intent(this, BubbleService.class);
        intent.putExtra("result_code", resultCode);
        intent.putExtra("projection_data", data);
        intent.putExtra("target_language", selectedLanguage);
        startService(intent);
        finish();
    }

    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}