package com.gatherin.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatherin.MainViewModel
import com.gatherin.ScanResult
import com.gatherin.data.local.SyncedScanEntity
import com.gatherin.ui.theme.*
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private data class ScanDisplayInfo(
    val bgColor: Color,
    val icon: String,
    val title: String,
    val detail: String
)

@Composable
fun ScannerScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val scanResult by vm.scanResult.collectAsStateWithLifecycle()

    var stationId by remember { mutableStateOf("station-app-1") }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(scanResult) {
        scanResult?.let { result ->
            when (result) {
                is ScanResult.Success -> haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                is ScanResult.Duplicate, is ScanResult.Error -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                is ScanResult.Queued -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            // Auto-dismiss after 3 seconds
            delay(3000L)
            vm.clearScanResult()
        }
    }

    LaunchedEffect(key1 = true) {
        launcher.launch(Manifest.permission.CAMERA)
    }

    // ── System Back Button Hook ──────────────────────────────────────────────
    // When scan result popup is open, system back closes the popup and returns to scanner
    BackHandler(enabled = scanResult != null) {
        vm.clearScanResult()
    }

    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val isSoundEnabled by vm.isSoundEnabled.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingScansCount.collectAsStateWithLifecycle()
    val syncedHistory by vm.syncedScans.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            AppTopBar(
                title   = stationId,
                eyebrow = "DOOR SCANNER STATION",
                onSignOut = { vm.signOut() },
                actions = {
                    IconButton(onClick = { vm.toggleSound() }) {
                        Icon(
                            imageVector = if (isSoundEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = "Toggle Sound",
                            tint = if (isSoundEnabled) BrandBlue else SlateGray
                        )
                    }
                    if (pendingCount > 0) {
                        Surface(
                            color = Color(0xFF2563EB),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                text = pendingCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Icon(
                        imageVector = if (isOnline) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = if (isOnline) "Online" else "Offline",
                        tint = if (isOnline) Color(0xFF10B981) else Color(0xFFEF4444),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Camera Scanner Card ─────────────────────────────────────────
            Card(
                shape     = RoundedCornerShape(20.dp),
                colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(4.dp),
                modifier  = Modifier.fillMaxWidth().height(420.dp)
            ) {
                if (hasCameraPermission) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CameraPreview(
                            isPaused = scanResult != null,
                            onQrCodeDetected = { token ->
                                if (scanResult == null) {
                                    vm.processCheckin(token, stationId)
                                }
                            }
                        )
                        ScanFocusOverlay(modifier = Modifier.fillMaxSize())
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Camera permission required to scan",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SlateGray
                        )
                    }
                }
            }

            // ── Station ID config ───────────────────────────────────────────
            Card(
                shape     = RoundedCornerShape(16.dp),
                colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(1.dp),
                modifier  = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Station ID", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value         = stationId,
                        onValueChange = { stationId = it },
                        singleLine    = true,
                        shape         = RoundedCornerShape(10.dp),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedTextColor     = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor   = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor   = BrandBlue,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            cursorColor          = BrandBlue
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Manual Token Scanner Card ───────────────────────────────────
            var scanInput by remember { mutableStateOf("") }
            Card(
                shape     = RoundedCornerShape(20.dp),
                colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(4.dp),
                modifier  = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text  = "Manual Token Scanner",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value         = scanInput,
                        onValueChange = { scanInput = it },
                        placeholder   = { Text("Paste QR code token", color = SlateGray) },
                        singleLine    = false,
                        minLines      = 3,
                        shape         = RoundedCornerShape(10.dp),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = BrandBlue,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedTextColor     = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor   = MaterialTheme.colorScheme.onSurface,
                            cursorColor          = BrandBlue,
                            focusedContainerColor   = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick  = {
                            if (scanInput.isNotBlank()) {
                                vm.processCheckin(scanInput, stationId)
                                scanInput = ""
                            }
                        },
                        enabled  = scanInput.isNotBlank(),
                        shape    = RoundedCornerShape(10.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Text("Process Check-in →", color = Color.White,
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // ── Live Activity Log ───────────────────────────────────────────
            if (syncedHistory.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RECENT ACTIVITY",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                        color = SlateGray
                    )
                    TextButton(onClick = { vm.clearHistory() }) {
                        Text("Clear", style = MaterialTheme.typography.labelSmall, color = BrandBlue)
                    }
                }
                syncedHistory.forEach { scan ->
                    ActivityLogItem(scan)
                }
            }
        }
    }

    // ── Fullscreen Scan Result Popup Modal ──────────────────────────────────
    if (scanResult != null) {
        val result = scanResult!!
        val info = when (result) {
            is ScanResult.Success -> ScanDisplayInfo(
                bgColor = Color(0xFF059669), // Emerald 600
                icon    = "✅",
                title   = "ENTRY GRANTED",
                detail  = result.message
            )
            is ScanResult.Duplicate -> ScanDisplayInfo(
                bgColor = Color(0xFFD97706), // Amber 600
                icon    = "⛔",
                title   = "ALREADY CHECKED IN",
                detail  = result.message
            )
            is ScanResult.Error -> ScanDisplayInfo(
                bgColor = Color(0xFFDC2626), // Rose 600
                icon    = "❌",
                title   = "SCAN REJECTED",
                detail  = result.message
            )
            is ScanResult.Queued -> ScanDisplayInfo(
                bgColor = Color(0xFF2563EB), // Blue 600
                icon    = "📥",
                title   = "SCAN QUEUED",
                detail  = result.message
            )
        }

        Dialog(
            onDismissRequest = { vm.clearScanResult() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress      = true,
                dismissOnClickOutside   = false
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color    = info.bgColor
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Header close button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = { vm.clearScanResult() }) {
                            Icon(
                                imageVector        = Icons.Default.Close,
                                contentDescription = "Close",
                                tint               = Color.White
                            )
                        }
                    }

                    // Main Result Details
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier            = Modifier.weight(1f)
                    ) {
                        Text(
                            text     = info.icon,
                            fontSize = 80.sp
                        )
                        Spacer(Modifier.height(20.dp))
                        Surface(
                            shape    = RoundedCornerShape(50),
                            color    = Color.Black.copy(alpha = 0.25f),
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            Text(
                                text     = info.title,
                                style    = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight    = FontWeight.Black,
                                    letterSpacing = 2.sp
                                ),
                                color    = Color.White,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text      = info.detail,
                            style     = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color     = Color.White,
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.fillMaxWidth(0.9f)
                        )
                    }

                    // Action Button to clear result and scan next person
                    Button(
                        onClick  = { vm.clearScanResult() },
                        shape    = RoundedCornerShape(16.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor   = info.bgColor
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(
                            text  = "Scan Next Ticket →",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityLogItem(scan: SyncedScanEntity) {
    val (icon, tint) = when (scan.status) {
        "success" -> "✅" to Color(0xFF10B981)
        "duplicate" -> "⛔" to Color(0xFFF59E0B)
        "expired" -> "⚠️" to Color(0xFFEF4444)
        else -> "❌" to Color(0xFFEF4444)
    }

    val timeStr = remember(scan.syncedAt) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(scan.syncedAt))
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.5.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = icon, fontSize = 20.sp, modifier = Modifier.padding(end = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = scan.attendeeName ?: "Guest",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Token: ${scan.token.take(12)}... • $timeStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateGray
                )
            }
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = tint.copy(alpha = 0.1f)
            ) {
                Text(
                    text = scan.status.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = tint,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun CameraPreview(
    isPaused: Boolean,
    onQrCodeDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { previewView ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = BarcodeScanning.getClient(options)

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (isPaused) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            for (barcode in barcodes) {
                                barcode.rawValue?.let { token ->
                                    onQrCodeDetected(token)
                                }
                            }
                        }
                        .addOnFailureListener {
                            // Handle failure
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (_: Exception) {
                // Log or handle error
            }
        }, ContextCompat.getMainExecutor(context))
    }
}

@Composable
fun ScanFocusOverlay(modifier: Modifier = Modifier) {
    val frameColor   = BrandBlue
    val scrimColor   = Color(0x66000000)
    val hint = "Align the QR code within the frame"

    val textMeasurer = rememberTextMeasurer()
    val hintLayout = remember {
        textMeasurer.measure(
            text = hint,
            style = TextStyle(
                color      = Color.White,
                fontSize   = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign  = TextAlign.Center
            )
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val frame      = size.minDimension * 0.72f
        val left       = (size.width - frame) / 2f
        val top        = (size.height - frame) / 2f
        val right      = left + frame
        val bottom     = top + frame
        val cornerLen  = 26.dp.toPx()
        val strokeW    = 4.dp.toPx()

        val width  = size.width
        val height = size.height
        drawRect(scrimColor, topLeft = Offset(0f, 0f),     size = Size(width, top))
        drawRect(scrimColor, topLeft = Offset(0f, bottom), size = Size(width, height - bottom))
        drawRect(scrimColor, topLeft = Offset(0f, top),    size = Size(left, bottom - top))
        drawRect(scrimColor, topLeft = Offset(right, top), size = Size(width - right, bottom - top))

        // Corner brackets
        drawLine(frameColor, Offset(left, top + cornerLen), Offset(left, top), strokeWidth = strokeW)
        drawLine(frameColor, Offset(left, top), Offset(left + cornerLen, top), strokeWidth = strokeW)

        drawLine(frameColor, Offset(right, top), Offset(right - cornerLen, top), strokeWidth = strokeW)
        drawLine(frameColor, Offset(right, top + cornerLen), Offset(right, top), strokeWidth = strokeW)

        drawLine(frameColor, Offset(left, bottom - cornerLen), Offset(left, bottom), strokeWidth = strokeW)
        drawLine(frameColor, Offset(left, bottom), Offset(left + cornerLen, bottom), strokeWidth = strokeW)

        drawLine(frameColor, Offset(right, bottom - cornerLen), Offset(right, bottom), strokeWidth = strokeW)
        drawLine(frameColor, Offset(right, bottom), Offset(right - cornerLen, bottom), strokeWidth = strokeW)

        drawText(
            textLayoutResult = hintLayout,
            topLeft          = Offset((size.width - hintLayout.size.width.toFloat()) / 2f, bottom + 20.dp.toPx())
        )
    }
}
