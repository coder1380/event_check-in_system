package com.gatherin.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatherin.ui.theme.BrandBlue
import com.gatherin.ui.theme.OrangeWarning
import com.gatherin.ui.theme.SlateGray
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private fun generateQrBitmap(content: String, sizePx: Int, darkColor: Int, lightColor: Int): Bitmap? {
    return try {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) darkColor else lightColor)
            }
        }
        bmp
    } catch (e: Exception) { null }
}

private fun parseExpiry(expiresAt: String?): Date? {
    if (expiresAt == null) return null
    val formats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    )
    for (fmt in formats) {
        try {
            val sdf = SimpleDateFormat(fmt, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return sdf.parse(expiresAt)
        } catch (_: Exception) {}
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrPassBottomSheet(
    token:              String,
    expiresAt:          String?,
    refreshCount:       Int = 0,
    refreshesRemaining: Int = 0,
    onDismiss:          () -> Unit,
) {
    val maxRefreshes = 3
    val totalRounds  = maxRefreshes + 1  // 1 initial + 3 refreshes

    // Generate QR bitmap off main thread
    val qrBitmap = remember(token) { mutableStateOf<Bitmap?>(null) }
    val qrSizePx  = 800
    val darkArgb  = Color(0xFF0F172A).toArgb()
    val lightArgb = Color(0xFFFFFFFF).toArgb()
    LaunchedEffect(token) {
        qrBitmap.value = withContext(Dispatchers.Default) {
            generateQrBitmap(token, qrSizePx, darkArgb, lightArgb)
        }
    }

    // Countdown timer
    val expiryDate  = remember(expiresAt) { parseExpiry(expiresAt) }
    var secondsLeft by remember { mutableIntStateOf(60) }
    val expired     = secondsLeft <= 0

    LaunchedEffect(expiresAt) {
        if (expiryDate == null) return@LaunchedEffect
        while (true) {
            val remaining = ((expiryDate.time - System.currentTimeMillis()) / 1000).toInt()
            secondsLeft = remaining
            if (remaining <= 0) break
            kotlinx.coroutines.delay(1000L)
        }
    }

    // Pulse animation for the last 10 seconds
    val pulseAlpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue  = if (secondsLeft in 1..10) 0.35f else 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val countColor = when {
        expired          -> OrangeWarning
        secondsLeft <= 10 -> OrangeWarning
        secondsLeft <= 20 -> Color(0xFFF59E0B)
        else              -> Color(0xFF10B981)
    }

    val isLastRound = refreshesRemaining == 0

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = MaterialTheme.colorScheme.surface,
        shape            = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Header ─────────────────────────────────────────────────────
            Text(
                text  = "Your Entry Pass",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = "Show this QR code at the door",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))

            // ── Round indicator dots ────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(totalRounds) { i ->
                    val filled = i <= refreshCount
                    Box(
                        modifier = Modifier
                            .size(if (i == refreshCount) 10.dp else 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (filled) BrandBlue else BrandBlue.copy(alpha = 0.2f)
                            )
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text  = if (isLastRound) "Last refresh — close and reopen to continue"
                        else "Auto-refresh ${refreshesRemaining} more time${if (refreshesRemaining == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = if (isLastRound) OrangeWarning else SlateGray
            )

            Spacer(Modifier.height(16.dp))

            // ── QR code card ────────────────────────────────────────────────
            Surface(
                shape    = RoundedCornerShape(20.dp),
                color    = Color.White,
                modifier = Modifier
                    .size(260.dp)
                    .border(
                        width = if (expired) 2.dp else 1.dp,
                        color = if (expired) OrangeWarning.copy(alpha = pulseAlpha)
                                else BrandBlue.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(20.dp)
                    )
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    when {
                        expired -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector        = Icons.Default.QrCode,
                                    contentDescription = null,
                                    tint               = OrangeWarning,
                                    modifier           = Modifier.size(56.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text       = if (isLastRound) "Session Ended" else "Refreshing…",
                                    style      = MaterialTheme.typography.titleMedium,
                                    color      = OrangeWarning,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isLastRound) {
                                    Text(
                                        text  = "Tap below to open a new pass",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SlateGray
                                    )
                                }
                            }
                        }
                        qrBitmap.value != null -> {
                            Image(
                                bitmap             = qrBitmap.value!!.asImageBitmap(),
                                contentDescription = "Entry QR code",
                                modifier           = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                        else -> {
                            CircularProgressIndicator(color = BrandBlue, strokeWidth = 2.dp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Countdown ───────────────────────────────────────────────────
            if (expiryDate != null && !expired) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(countColor.copy(alpha = 0.12f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("⏱", fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text  = if (isLastRound) "Expires in ${secondsLeft}s — last pass"
                                else "Refreshing in ${secondsLeft}s",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = countColor
                    )
                }
            } else if (expired && isLastRound) {
                Text(
                    text      = "Session complete — close and tap 'Show QR Pass' again",
                    style     = MaterialTheme.typography.bodySmall,
                    color     = OrangeWarning,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(24.dp))

            OutlinedButton(
                onClick  = onDismiss,
                shape    = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Close Pass", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
