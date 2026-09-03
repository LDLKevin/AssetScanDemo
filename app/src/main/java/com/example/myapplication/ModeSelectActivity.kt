package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 模式選擇頁 —— Jetpack Compose 版（原本的 activity_mode_select.xml + findViewById 版已移除）。
 *
 * 與 XML 版的差異（這就是你想看的「介面框架差異」）：
 * - 沒有 XML layout：整個畫面由 @Composable 函式「宣告」出來（宣告式 UI）。
 * - 沒有 findViewById：不再需要抓 View 再設值；資料/事件直接以參數 (lambda) 傳進 Composable。
 * - 沿用既有色彩資源：colorResource(R.color.xxx) 讓 Compose 直接讀 res/values/colors.xml。
 * - safeDrawingPadding() 一行取代原本手動的 WindowInsets 監聽。
 * - @Preview：可在 Android Studio 直接預覽畫面，免跑模擬器（Compose 的一大優勢）。
 * - 基類改用 ComponentActivity（Compose 慣用），主題 Theme.Material3.DayNight.NoActionBar 相容。
 */
class ModeSelectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                ModeSelectScreen(
                    onFull = { startActivity(Intent(this, MainActivity::class.java)) },
                    onSampling = { startActivity(Intent(this, SamplingActivity::class.java)) },
                )
            }
        }
    }
}

@Composable
private fun ModeSelectScreen(onFull: () -> Unit, onSampling: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.bg_main))
            .safeDrawingPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "EVERGREEN",
            color = colorResource(R.color.evergreen_primary),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "盤點作業系統",
            color = colorResource(R.color.text_primary),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .width(48.dp)
                .height(3.dp)
                .background(colorResource(R.color.evergreen_accent)),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "請選擇作業流程",
            color = colorResource(R.color.text_secondary),
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(56.dp))

        ModeButton(
            text = "全盤",
            container = colorResource(R.color.evergreen_primary),
            content = colorResource(R.color.text_on_primary),
            onClick = onFull,
        )
        Spacer(Modifier.height(16.dp))
        ModeButton(
            text = "抽盤",
            container = colorResource(R.color.evergreen_secondary),
            content = colorResource(R.color.text_on_primary),
            onClick = onSampling,
        )
    }
}

@Composable
private fun ModeButton(
    text: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(vertical = 28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Text(text = text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Preview(showBackground = true)
@Composable
private fun ModeSelectPreview() {
    MaterialTheme {
        ModeSelectScreen(onFull = {}, onSampling = {})
    }
}
