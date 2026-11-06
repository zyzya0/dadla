package com.narezany.doodlejump;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Простая копия Doodle Jump: человечек сам прыгает по платформам,
 * управление наклоном телефона или пальцем.
 */
public class GameView extends View implements Runnable, SensorEventListener {

    private static final int STATE_READY = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_DEAD = 2;

    // физика, в dp
    private static final float GRAVITY = 2500f;
    private static final float JUMP_SPEED = 1150f;
    private static final float MAX_FALL_SPEED = 1800f;
    private static final float MAX_SIDE_SPEED = 950f;
    private static final float MIN_GAP = 80f;
    private static final float MAX_GAP = 145f;
    private static final float MAX_GAP_HARD = 168f;

    // размеры, в dp
    private static final float PLAYER_SIZE = 46f;
    private static final float PLATFORM_HEIGHT = 13f;
    private static final float PLATFORM_WIDTH = 74f;

    private static final int COLOR_BG_TOP = 0xFFFFFFFF;
    private static final int COLOR_BG_BOTTOM = 0xFFDDEEFF;
    private static final int COLOR_PLATFORM = 0xFF7ED957;
    private static final int COLOR_PLATFORM_DARK = 0xFF4E9A2A;
    private static final int COLOR_PLAYER = 0xFF8CE05A;
    private static final int COLOR_INK = 0xFF2B3A55;

    private static class Platform {
        float x, y, w;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private long lastFrameNanos;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Random random = new Random();
    private final List<Platform> platforms = new ArrayList<>();

    private final SensorManager sensorManager;
    private Sensor accelerometer;

    private SharedPreferences prefs;
    private float density = 1f;
    private int viewWidth;
    private int viewHeight;
    private boolean sized;

    private int state = STATE_READY;

    private float playerX, playerY, playerVx, playerVy;
    private float prevBottom;
    private float cameraY;
    private float highestPlatformY;
    private int score;
    private int best;
    private boolean newRecord;
    private float deadTimer;

    private float tiltRaw;
    private float tilt;
    private float touchX;
    private boolean touching;

    public GameView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        prefs = context.getSharedPreferences("doodle_jump", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);

        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setColor(COLOR_INK);

        setFocusable(true);
        setKeepScreenOn(true);
    }

    // ---------------------------------------------------------------- размеры

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewWidth = w;
        viewHeight = h;
        bgPaint.setShader(new LinearGradient(0, 0, 0, h,
                COLOR_BG_TOP, COLOR_BG_BOTTOM, Shader.TileMode.CLAMP));
        if (!sized) {
            sized = true;
            newGame();
        }
    }

    private float dp(float value) {
        return value * density;
    }

    // ---------------------------------------------------------------- игра

    private void newGame() {
        platforms.clear();
        playerX = viewWidth / 2f - dp(PLAYER_SIZE) / 2f;
        playerY = viewHeight * 0.55f;
        playerVx = 0f;
        playerVy = 0f;
        prevBottom = playerY + dp(PLAYER_SIZE);
        cameraY = 0f;
        score = 0;
        newRecord = false;
        deadTimer = 0f;
        touching = false;

        // широкая стартовая платформа прямо под человечком
        float groundWidth = dp(PLATFORM_WIDTH) * 2f;
        Platform ground = new Platform();
        ground.x = viewWidth / 2f - groundWidth / 2f;
        ground.y = playerY + dp(PLAYER_SIZE) + dp(2);
        ground.w = groundWidth;
        platforms.add(ground);
        highestPlatformY = ground.y;

        // заполняем экран платформами вверх
        float limit = cameraY - viewHeight * 1.2f;
        while (highestPlatformY > limit) {
            Platform p = new Platform();
            p.y = highestPlatformY - dp(MIN_GAP + random.nextFloat() * (MAX_GAP - MIN_GAP));
            p.w = dp(PLATFORM_WIDTH);
            p.x = random.nextFloat() * Math.max(1f, viewWidth - p.w);
            platforms.add(p);
            highestPlatformY = p.y;
        }

        state = STATE_READY;
        invalidate();
    }

    private void startGame() {
        state = STATE_PLAYING;
        playerVy = -dp(JUMP_SPEED) * 0.35f;
        lastFrameNanos = System.nanoTime();
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private void update(float dt) {
        if (!sized) {
            return;
        }

        // плавный наклон
        tilt += (tiltRaw - tilt) * Math.min(1f, dt * 10f);

        if (state == STATE_DEAD) {
            deadTimer += dt;
            // человечек падает вниз, пока не улетит за экран
            playerVy += dp(GRAVITY) * dt;
            playerY += playerVy * dt;
            return;
        }

        if (state != STATE_PLAYING) {
            return;
        }

        // ---- горизонтальное движение
        float maxSide = dp(MAX_SIDE_SPEED);
        if (touching) {
            float target = touchX - dp(PLAYER_SIZE) / 2f;
            playerVx = clamp((target - playerX) * 10f, -maxSide * 1.25f, maxSide * 1.25f);
        } else {
            playerVx = clamp(tilt / 4.5f, -1f, 1f) * maxSide;
        }

        // ---- вертикальное движение
        playerVy += dp(GRAVITY) * dt;
        if (playerVy > dp(MAX_FALL_SPEED)) {
            playerVy = dp(MAX_FALL_SPEED);
        }

        float size = dp(PLAYER_SIZE);
        prevBottom = playerY + size;
        playerY += playerVy * dt;
        playerX += playerVx * dt;

        // телепорт через края экрана
        if (playerX > viewWidth) {
            playerX = -size;
        } else if (playerX + size < 0) {
            playerX = viewWidth;
        }

        // ---- приземление на платформу
        if (playerVy > 0f) {
            float bottom = playerY + size;
            for (int i = 0; i < platforms.size(); i++) {
                Platform p = platforms.get(i);
                if (prevBottom <= p.y && bottom >= p.y
                        && playerX + size > p.x + dp(3)
                        && playerX < p.x + p.w - dp(3)) {
                    playerY = p.y - size;
                    playerVy = -dp(JUMP_SPEED);
                    break;
                }
            }
        }

        // ---- камера
        float anchor = viewHeight * 0.38f;
        if (playerY - cameraY < anchor) {
            cameraY = playerY - anchor;
        }

        int climbed = (int) (-cameraY / dp(1f) / 12f);
        if (climbed > score) {
            score = climbed;
        }

        // ---- переработка платформ
        float maxGap = Math.min(MAX_GAP_HARD, MIN_GAP + 8f + score * 0.12f);
        for (int i = 0; i < platforms.size(); i++) {
            Platform p = platforms.get(i);
            if (p.y - cameraY > viewHeight + dp(160)) {
                p.y = highestPlatformY - dp(MIN_GAP + random.nextFloat() * (maxGap - MIN_GAP));
                p.w = dp(PLATFORM_WIDTH);
                p.x = random.nextFloat() * Math.max(1f, viewWidth - p.w);
                highestPlatformY = p.y;
            }
        }

        // ---- падение вниз = конец игры
        if (playerY - cameraY > viewHeight + dp(120)) {
            state = STATE_DEAD;
            deadTimer = 0f;
            if (score > best) {
                best = score;
                newRecord = true;
                prefs.edit().putInt("best", best).apply();
            }
        }
    }

    // ---------------------------------------------------------------- отрисовка

    @Override
    protected void onDraw(Canvas canvas) {
        if (!sized) {
            return;
        }

        canvas.drawRect(0, 0, viewWidth, viewHeight, bgPaint);
        drawBackgroundDoodles(canvas);
        drawPlatforms(canvas);
        drawPlayer(canvas);
        drawHud(canvas);

        if (state == STATE_READY) {
            drawReadyOverlay(canvas);
        } else if (state == STATE_DEAD) {
            drawGameOver(canvas);
        }
    }

    private void drawBackgroundDoodles(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(0x2A5FA8D8);
        float span = viewHeight + dp(200);
        float offset = (-cameraY * 0.3f) % span;
        if (offset < 0) {
            offset += span;
        }
        for (int i = 0; i < 12; i++) {
            float fx = ((i * 0.37f) % 1f) * viewWidth;
            float fy = ((i * 0.61f) % 1f) * span + offset;
            if (fy > span) {
                fy -= span;
            }
            float y = fy - dp(100);
            float r = dp(26f + (i % 3) * 14f);
            canvas.drawCircle(fx, y, r, paint);
            if (i % 3 == 0) {
                canvas.drawCircle(fx, y, r * 0.55f, paint);
            }
        }
    }

    private void drawPlatforms(Canvas canvas) {
        float h = dp(PLATFORM_HEIGHT);
        for (int i = 0; i < platforms.size(); i++) {
            Platform p = platforms.get(i);
            float y = p.y - cameraY;
            if (y < -h * 2 || y > viewHeight + h * 2) {
                continue;
            }
            // мягкая тень
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x14000000);
            rect.set(p.x + dp(2), y + dp(3), p.x + p.w + dp(2), y + h + dp(3));
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);

            // тело платформы
            paint.setColor(COLOR_PLATFORM);
            rect.set(p.x, y, p.x + p.w, y + h);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);

            // обводка
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2f));
            paint.setColor(COLOR_PLATFORM_DARK);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);

            // блик сверху
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2f));
            paint.setColor(0xB3FFFFFF);
            canvas.drawLine(p.x + h * 0.8f, y + h * 0.32f,
                    p.x + p.w - h * 0.8f, y + h * 0.32f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void drawPlayer(Canvas canvas) {
        float size = dp(PLAYER_SIZE);
        float cx = playerX + size / 2f;
        float cy = playerY - cameraY + size / 2f;
        if (cy < -size * 2 || cy > viewHeight + size * 2) {
            return;
        }

        float maxSide = dp(MAX_SIDE_SPEED);
        float lean = clamp(playerVx / maxSide, -1f, 1f) * 9f;

        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(lean);

        float half = size / 2f;

        // тело
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_PLAYER);
        rect.set(-half, -half, half, half);
        canvas.drawRoundRect(rect, size * 0.42f, size * 0.42f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.2f));
        paint.setColor(COLOR_PLATFORM_DARK);
        canvas.drawRoundRect(rect, size * 0.42f, size * 0.42f, paint);

        // лапки
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_PLATFORM_DARK);
        canvas.drawCircle(-half * 0.45f, half * 0.92f, size * 0.13f, paint);
        canvas.drawCircle(half * 0.45f, half * 0.92f, size * 0.13f, paint);

        // глаза смотрят по направлению движения
        float look = clamp(playerVx / maxSide, -1f, 1f) * size * 0.09f;
        paint.setColor(Color.WHITE);
        canvas.drawCircle(-half * 0.34f + look, -half * 0.18f, size * 0.19f, paint);
        canvas.drawCircle(half * 0.34f + look, -half * 0.18f, size * 0.19f, paint);

        paint.setColor(COLOR_INK);
        canvas.drawCircle(-half * 0.30f + look, -half * 0.18f, size * 0.09f, paint);
        canvas.drawCircle(half * 0.38f + look, -half * 0.18f, size * 0.09f, paint);

        // улыбка
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(COLOR_INK);
        rect.set(-half * 0.3f, half * 0.18f, half * 0.3f, half * 0.55f);
        canvas.drawArc(rect, 20f, 140f, false, paint);
        paint.setStyle(Paint.Style.FILL);

        canvas.restore();
    }

    private void drawHud(Canvas canvas) {
        textPaint.setTextSize(dp(30f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(COLOR_INK);
        canvas.drawText(String.valueOf(score), dp(16f) + dp(1.5f), dp(44f) + dp(1.5f), textPaint);
        textPaint.setColor(Color.WHITE);
        canvas.drawText(String.valueOf(score), dp(16f), dp(44f), textPaint);

        textPaint.setTextSize(dp(14f));
        textPaint.setColor(0x992B3A55);
        canvas.drawText("рекорд " + best, dp(17f), dp(64f), textPaint);
    }

    private void drawReadyOverlay(Canvas canvas) {
        paint.setColor(0x33000000);
        canvas.drawRect(0, 0, viewWidth, viewHeight, paint);

        float cx = viewWidth / 2f;
        float cy = viewHeight * 0.5f;

        textPaint.setTextAlign(Paint.Align.CENTER);

        textPaint.setTextSize(dp(40f));
        textPaint.setColor(COLOR_PLATFORM_DARK);
        canvas.drawText("Doodle", cx + dp(2f), cy - dp(28f), textPaint);
        canvas.drawText("Jump", cx + dp(2f), cy + dp(22f), textPaint);
        textPaint.setColor(Color.WHITE);
        canvas.drawText("Doodle", cx, cy - dp(30f), textPaint);
        canvas.drawText("Jump", cx, cy + dp(20f), textPaint);

        textPaint.setTextSize(dp(17f));
        textPaint.setColor(Color.WHITE);
        canvas.drawText("Тапните, чтобы начать", cx, cy + dp(80f), textPaint);

        textPaint.setTextSize(dp(13f));
        textPaint.setColor(0xCCFFFFFF);
        canvas.drawText("Наклоняйте телефон или ведите пальцем", cx, cy + dp(108f), textPaint);

        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawGameOver(Canvas canvas) {
        paint.setColor(0xB30E1B2E);
        canvas.drawRect(0, 0, viewWidth, viewHeight, paint);

        float cx = viewWidth / 2f;
        float cy = viewHeight * 0.45f;

        textPaint.setTextAlign(Paint.Align.CENTER);

        textPaint.setTextSize(dp(32f));
        textPaint.setColor(Color.WHITE);
        canvas.drawText("Игра окончена", cx, cy, textPaint);

        textPaint.setTextSize(dp(22f));
        textPaint.setColor(0xFFBFE86A);
        canvas.drawText("Счёт: " + score, cx, cy + dp(46f), textPaint);

        textPaint.setTextSize(dp(15f));
        textPaint.setColor(0xCCFFFFFF);
        canvas.drawText("Рекорд: " + best, cx, cy + dp(74f), textPaint);

        if (newRecord) {
            textPaint.setTextSize(dp(16f));
            textPaint.setColor(0xFFFFD54F);
            canvas.drawText("Новый рекорд!", cx, cy + dp(100f), textPaint);
        }

        if (deadTimer > 0.3f) {
            float alpha = (float) (0.55f + 0.45f * Math.sin(deadTimer * 5f));
            textPaint.setTextSize(dp(17f));
            textPaint.setColor(Color.WHITE);
            textPaint.setAlpha((int) (255 * alpha));
            canvas.drawText("Тапните, чтобы сыграть снова", cx, cy + dp(142f), textPaint);
            textPaint.setAlpha(255);
        }

        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    // ---------------------------------------------------------------- ввод

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (state == STATE_READY) {
                    touching = true;
                    touchX = event.getX();
                    startGame();
                } else if (state == STATE_DEAD) {
                    if (deadTimer > 0.3f) {
                        newGame();
                        touchX = event.getX();
                        touching = true;
                        startGame();
                    }
                } else {
                    touching = true;
                    touchX = event.getX();
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                touchX = event.getX();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            tiltRaw = event.values[0];
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // не используется
    }

    // ---------------------------------------------------------------- цикл

    public void resumeGame() {
        if (running) {
            return;
        }
        running = true;
        lastFrameNanos = System.nanoTime();
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
        handler.post(this);
    }

    public void pauseGame() {
        running = false;
        handler.removeCallbacks(this);
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void run() {
        if (!running) {
            return;
        }
        long now = System.nanoTime();
        float dt = (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        if (dt < 0f) {
            dt = 0f;
        }
        if (dt > 0.05f) {
            dt = 0.05f;
        }
        update(dt);
        invalidate();
        handler.postDelayed(this, 16);
    }
}
