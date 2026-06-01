package com.example.doubaoVoiceLauncher;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private RadioGroup rgRecognizeMode;
    private EditText etApiUrl;
    private EditText etApiKey;
    private EditText etModelId;
    private Button btnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("设置");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        rgRecognizeMode = findViewById(R.id.rgRecognizeMode);
        etApiUrl = findViewById(R.id.etApiUrl);
        etApiKey = findViewById(R.id.etApiKey);
        etModelId = findViewById(R.id.etModelId);
        btnSave = findViewById(R.id.btnSave);

        loadConfig();

        btnSave.setOnClickListener(v -> saveConfig());
    }

    private void loadConfig() {
        SharedPreferences prefs = getSharedPreferences("doubao_voice_config", MODE_PRIVATE);
        int mode = prefs.getInt("recognize_mode", 1);
        String apiUrl = prefs.getString("ai_api_url", "https://ark.cn-beijing.volces.com/api/v3/chat/completions");
        String apiKey = prefs.getString("ai_api_key", "");
        String modelId = prefs.getString("ai_model_id", "doubao-vision-pro-32k");

        switch (mode) {
            case 0: rgRecognizeMode.check(R.id.rbModeText); break;
            case 1: rgRecognizeMode.check(R.id.rbModeTemplate); break;
            case 2: rgRecognizeMode.check(R.id.rbModeAI); break;
        }

        etApiUrl.setText(apiUrl);
        etApiKey.setText(apiKey);
        etModelId.setText(modelId);
    }

    private void saveConfig() {
        SharedPreferences prefs = getSharedPreferences("doubao_voice_config", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        int mode;
        int checkedId = rgRecognizeMode.getCheckedRadioButtonId();
        if (checkedId == R.id.rbModeText) {
            mode = 0;
        } else if (checkedId == R.id.rbModeTemplate) {
            mode = 1;
        } else {
            mode = 2;
        }

        editor.putInt("recognize_mode", mode);
        editor.putString("ai_api_url", etApiUrl.getText().toString().trim());
        editor.putString("ai_api_key", etApiKey.getText().toString().trim());
        editor.putString("ai_model_id", etModelId.getText().toString().trim());
        editor.apply();

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
