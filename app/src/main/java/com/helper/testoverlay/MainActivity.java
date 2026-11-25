package com.helper.testoverlay;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.style.UnderlineSpan;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_SCREEN = 1002;
    private static final String PREFS_NAME = "TestHelperPrefs";
    private static final String KEY_PREF = "saved_api_key";

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private WindowManager windowManager;
    private LinearLayout overlayView;
    private TextView answerTextView;
    private TextView statusTextView;

    private EditText keyInput;
    private String apiKey = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean isRunning = false;
    private boolean isScanning = false;

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        apiKey = prefs.getString(KEY_PREF, "");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F172A"));
        root.setPadding(48, 64, 48, 48);

        TextView title = new TextView(this);
        title.setText("AI Помічник (Авто-скріншот 6 с)");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        keyInput = new EditText(this);
        keyInput.setHint("Вставте ваш Groq API Key (gsk_...)");
        keyInput.setHintTextColor(Color.parseColor("#64748B"));
        keyInput.setText(apiKey);
        keyInput.setTextColor(Color.WHITE);
        keyInput.setBackgroundColor(Color.parseColor("#1E293B"));
        keyInput.setPadding(24, 24, 24, 24);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 32, 0, 32);
        keyInput.setLayoutParams(lp);
        root.addView(keyInput);

        Button startBtn = new Button(this);
        startBtn.setText("1. Увімкнути плаваюче вікно та авто-режим");
        startBtn.setBackgroundColor(Color.parseColor("#EF4444"));
        startBtn.setTextColor(Color.WHITE);
        startBtn.setOnClickListener(v -> {
            apiKey = keyInput.getText().toString().trim();
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Спочатку вставте API-ключ!", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString(KEY_PREF, apiKey).apply();
            checkPermissionsAndStart();
        });
        root.addView(startBtn);

        Button stopBtn = new Button(this);
        stopBtn.setText("Зупинити");
        stopBtn.setBackgroundColor(Color.parseColor("#334155"));
        stopBtn.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.setMargins(0, 24, 0, 0);
        stopBtn.setLayoutParams(lp2);
        stopBtn.setOnClickListener(v -> stopAll());
        root.addView(stopBtn);

        setContentView(root);
    }

    private void checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            Toast.makeText(this, "Дозвольте відображення поверх інших вікон", Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_SCREEN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_SCREEN);
            }
        } else if (requestCode == REQ_SCREEN && resultCode == RESULT_OK && data != null) {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            setupVirtualDisplay();
            showOverlayWindow();
            isRunning = true;
            startAutoLoop();
            moveTaskToBack(true);
        }
    }

    private void setupVirtualDisplay() {
        int w = screenWidth / 2;
        int h = screenHeight / 2;
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture", w, h, screenDensity / 2,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null
        );
    }

    private void showOverlayWindow() {
        if (overlayView != null) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.y = 80;

        overlayView = new LinearLayout(this);
        overlayView.setOrientation(LinearLayout.VERTICAL);
        overlayView.setBackgroundColor(Color.parseColor("#E60F172A"));
        overlayView.setPadding(28, 20, 28, 20);

        statusTextView = new TextView(this);
        statusTextView.setText("Авто-скан кожні 6 с (перетягніть плашку за потреби)");
        statusTextView.setTextColor(Color.parseColor("#94A3B8"));
        statusTextView.setTextSize(11f);
        overlayView.addView(statusTextView);

        answerTextView = new TextView(this);
        answerTextView.setText("Очікування кадру...");
        answerTextView.setTextColor(Color.parseColor("#FF4D4D"));
        answerTextView.setTextSize(16f);
        answerTextView.setTypeface(null, Typeface.BOLD);
        overlayView.addView(answerTextView);

        overlayView.setOnTouchListener(new View.OnTouchListener() {
            private int initialY;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialY = params.y;
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(overlayView, params);
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(overlayView, params);
    }

    private void startAutoLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                captureAndSolve();
                handler.postDelayed(this, 6000);
            }
        }, 1500);
    }

    private void captureAndSolve() {
        if (isScanning || imageReader == null) return;
        Image image = imageReader.acquireLatestImage();
        if (image == null) return;

        isScanning = true;
        handler.post(() -> statusTextView.setText("ШІ аналізує екран..."));

        try {
            Image.Plane[] planes = image.getPlanes();
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
            image.close();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos);
            String base64Img = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            bitmap.recycle();

            executor.execute(() -> {
                try {
                    String answer = askGroqWithFallback(base64Img);

                    handler.post(() -> {
                        SpannableString content = new SpannableString("Відповідь: " + answer);
                        content.setSpan(new UnderlineSpan(), 0, content.length(), 0);
                        answerTextView.setText(content);
                        statusTextView.setText("Оновлено (наступне через 6 с)");
                    });
                } catch (Exception e) {
                    handler.post(() -> statusTextView.setText("Помилка: " + e.getMessage()));
                } finally {
                    isScanning = false;
                }
            });
        } catch (Exception e) {
            image.close();
            isScanning = false;
        }
    }

    private String askGroqWithFallback(String base64Jpeg) throws Exception {
        String[] models = {
                "llama-3.2-11b-vision-preview",
                "llama-3.2-90b-vision-preview"
        };
        Exception lastEx = null;
        for (String model : models) {
            try {
                return callGroqModel(base64Jpeg, model);
            } catch (Exception e) {
                lastEx = e;
            }
        }
        throw lastEx != null ? lastEx : new Exception("Не вдалося отримати відповідь від Groq");
    }

    private String callGroqModel(String base64Jpeg, String modelName) throws Exception {
        URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);

        JSONObject textContent = new JSONObject();
        textContent.put("type", "text");
        textContent.put("text", "На скріншоті відкрито онлайн-тест (На Урок або Всеосвіта). Напиши ТІЛЬКИ точний текст правильної відповіді (або кількох правильних варіантів через кому). Без зайвих слів.");

        JSONObject imageUrlObj = new JSONObject();
        imageUrlObj.put("url", "data:image/jpeg;base64," + base64Jpeg);

        JSONObject imageContent = new JSONObject();
        imageContent.put("type", "image_url");
        imageContent.put("image_url", imageUrlObj);

        JSONArray contentArr = new JSONArray();
        contentArr.put(textContent);
        contentArr.put(imageContent);

        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", contentArr);

        JSONArray messages = new JSONArray();
        messages.put(message);

        JSONObject body = new JSONObject();
        body.put("model", modelName);
        body.put("messages", messages);
        body.put("temperature", 0.1);
        body.put("max_tokens", 200);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes("UTF-8"));
        }

        int code = conn.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"
        ));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + sb.toString());
        }

        JSONObject resp = new JSONObject(sb.toString());
        return resp.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim();
    }

    private void stopAll() {
        isRunning = false;
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
        }
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
    }
}
