package com.example.myapplication.feedback;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * 掃描聲音＋震動回饋（全盤與抽盤共用），讓掃描員在吵雜環境或不看螢幕時也能辨識結果。
 *
 * <ul>
 *   <li>{@link #success()} 相符：一短嗶＋短震。</li>
 *   <li>{@link #unmatched()} 不相符：兩短嗶（{@code TONE_PROP_BEEP2}）＋可辨識的雙段震動。</li>
 * </ul>
 *
 * 嗶聲走媒體音量（{@link AudioManager#STREAM_MUSIC}），隨系統媒體音量／靜音；
 * 用完請呼叫 {@link #release()}。
 */
public class ScanFeedback {

    private final Vibrator vibrator;
    private ToneGenerator tone;   // 建立失敗時為 null（不影響震動）

    public ScanFeedback(Context context) {
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
        } catch (RuntimeException e) {
            tone = null;   // 某些裝置/狀態下 ToneGenerator 會拋例外
        }
    }

    /** 相符：一短嗶＋短震。 */
    public void success() {
        if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
        vibrate(new long[]{0, 60});
    }

    /** 不相符：兩短嗶＋雙段震動。 */
    public void unmatched() {
        if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300);
        vibrate(new long[]{0, 50, 90, 50});
    }

    public void release() {
        if (tone != null) {
            tone.release();
            tone = null;
        }
    }

    private void vibrate(long[] pattern) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }
}
