@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.andlab.doctas

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import com.andlab.doctas.ui.theme.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setContent {
                DoctasTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Speech recognition is not available on this device.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        setContent {
            DoctasTheme {
                speechRecognizer?.let { VoiceAssistanceScreen(it) }
            }
        }
    }
}

@Serializable
data class HealthDataRequest(val text: String)

@Serializable
data class FormattedData(
    @SerialName("name") val name: String? = null,
    @SerialName("age") val age: String? = null,
    @SerialName("gender") val gender: String? = null,
    @SerialName("heart_rate") val heartRate: String? = null,
    @SerialName("SpO2") val spo2: String? = null
)

private val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
        })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000L
        connectTimeoutMillis = 30_000L
        socketTimeoutMillis = 30_000L
    }
}

const val BASE_URL = "https://andlab.ratfish-miaplacidus.ts.net"

// Helper to resize bitmap to speed up processing and upload
fun Bitmap.resizeToLimit(maxDimension: Int): Bitmap {
    val width = width
    val height = height
    val ratio = width.toFloat() / height.toFloat()
    
    var newWidth = width
    var newHeight = height
    
    if (width > height) {
        if (width > maxDimension) {
            newWidth = maxDimension
            newHeight = (newWidth / ratio).toInt()
        }
    } else {
        if (height > maxDimension) {
            newHeight = maxDimension
            newWidth = (newHeight * ratio).toInt()
        }
    }
    
    return if (newWidth == width && newHeight == height) this 
    else Bitmap.createScaledBitmap(this, newWidth, newHeight, true)
}

suspend fun submitPatientData(
    text: String,
    abgBitmap: Bitmap?,
    ecgBitmap: Bitmap?
): Result<String> = withContext(Dispatchers.Default) {
    try {
        // Parallel processing of images (Resizing and Compressing)
        val abgDataDeferred = async {
            abgBitmap?.let {
                val resized = it.resizeToLimit(1280) // Limit to 720p/1080p-ish range
                val stream = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, 50, stream) // 50% is plenty for text/ECG lines
                stream.toByteArray()
            }
        }

        val ecgDataDeferred = async {
            ecgBitmap?.let {
                val resized = it.resizeToLimit(1280)
                val stream = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, 50, stream)
                stream.toByteArray()
            }
        }

        val abgBytes = abgDataDeferred.await()
        val ecgBytes = ecgDataDeferred.await()

        val formData = formData {
            append("text", text)
            abgBytes?.let {
                append("abg", it, Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"abg.jpg\"")
                })
            }
            ecgBytes?.let {
                append("ecg", it, Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"ecg.jpg\"")
                })
            }
        }

        val response: HttpResponse = client.submitFormWithBinaryData(
            url = "$BASE_URL/api/health-data",
            formData = formData
        )

        if (response.status.isSuccess()) Result.success("Submitted")
        else Result.failure(Exception("Submit failed: ${response.status}"))

    } catch (e: Exception) {
        e.printStackTrace()
        Result.failure(e)
    }
}

enum class UiState {
    IDLE,
    LISTENING,
    PROCESSING,
    SUCCESS,
    ERROR,
    QUEUED // New state for offline storage
}

data class StagedData(
    val text: String,
    val abgBitmap: Bitmap?,
    val ecgBitmap: Bitmap?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAssistanceScreen(speechRecognizer: SpeechRecognizer) {
    var consecutiveErrors by remember { mutableIntStateOf(0) }
    var lastRecognitionTime by remember { mutableLongStateOf(0L) }
    var finalText by rememberSaveable { mutableStateOf("") }
    var partialText by remember { mutableStateOf("") }
    var isListeningDesired by remember { mutableStateOf(false) }
    var uiState by rememberSaveable { mutableStateOf(UiState.IDLE) }
    var rmsdB by remember { mutableFloatStateOf(0f) }
    var lastExtractedData by remember { mutableStateOf<FormattedData?>(null) }
    
    // Camera and Report states
    var isCameraOpen by remember { mutableStateOf(false) }
    var ecgBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var abgBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var capturingType by remember { mutableStateOf("") } // "ecg" or "abg"

    // Offline Queue
    var stagedData by remember { mutableStateOf<StagedData?>(null) }

    var showSheetDialog by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val sheetUrl = "https://docs.google.com/spreadsheets/d/1_NlDGWglSz9Z9BuTUktX7mwUfUMnhlhmuA1gnTVcAxA"

    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    // Monitor Connectivity
    var isOnline by remember { mutableStateOf(true) }
    DisposableEffect(context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isOnline = true
            }
            override fun onLost(network: Network) {
                isOnline = false
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
        onDispose { connectivityManager.unregisterNetworkCallback(callback) }
    }

    // Auto-retry when online
    LaunchedEffect(isOnline, stagedData) {
        if (isOnline && stagedData != null) {
            uiState = UiState.PROCESSING
            val result = submitPatientData(stagedData!!.text, stagedData!!.abgBitmap, stagedData!!.ecgBitmap)
            if (result.isSuccess) {
                uiState = UiState.SUCCESS
                stagedData = null
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                uiState = UiState.QUEUED
            }
        }
    }

    val greatVibesFamily = remember {
        FontFamily(
            Font(resId = R.font.great_vibes_regular, weight = FontWeight.Normal)
        )
    }

    val speechRecognizerIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
    }

    val startListening = {
        isListeningDesired = true
        uiState = UiState.LISTENING
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        speechRecognizer.startListening(speechRecognizerIntent)
    }

    val stopListening = {
        isListeningDesired = false
        uiState = UiState.IDLE
        speechRecognizer.stopListening()
    }

    val listener = remember {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (isListeningDesired) uiState = UiState.LISTENING
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdBVal: Float) {
                if (isListeningDesired) {
                    rmsdB = if (rmsdBVal > -2.0f) rmsdBVal else 0f
                }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (!isListeningDesired) return
                if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) {
                    speechRecognizer.startListening(speechRecognizerIntent)
                }
            }
            override fun onResults(results: Bundle?) {
                val result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.getOrNull(0) ?: ""
                if (result.isNotBlank()) {
                    consecutiveErrors = 0
                    val needsPunctuation = finalText.isNotBlank() && !finalText.endsWith(".") && !finalText.endsWith("?") && !finalText.endsWith("!")
                    val capitalizedResult = if (finalText.isBlank() || finalText.endsWith(".")) result.replaceFirstChar { it.uppercase() } else result
                    finalText = when {
                        finalText.isBlank() -> capitalizedResult
                        needsPunctuation -> "$finalText. $capitalizedResult"
                        else -> "$finalText $capitalizedResult"
                    }
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                partialText = ""
                lastRecognitionTime = System.currentTimeMillis()
                if (isListeningDesired) speechRecognizer.startListening(speechRecognizerIntent) else uiState = UiState.IDLE
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val result = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.getOrNull(0) ?: ""
                if (result.isNotBlank() && result.length > 2 && result != partialText) partialText = result
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) isCameraOpen = true
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startListening()
    }

    DisposableEffect(speechRecognizer) {
        speechRecognizer.setRecognitionListener(listener)
        onDispose { speechRecognizer.setRecognitionListener(null) }
    }

    val borderColor by animateColorAsState(
        targetValue = when (uiState) {
            UiState.LISTENING -> Green
            UiState.PROCESSING -> Yellow
            UiState.QUEUED -> PrimarySoftGlowDark
            UiState.ERROR -> Red
            UiState.SUCCESS -> Green
            else -> MaterialTheme.colorScheme.outline
        },
        label = "border_color"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (uiState == UiState.LISTENING) 1.15f else 1f,
        animationSpec = infiniteRepeatable(animation = tween(1000), repeatMode = RepeatMode.Reverse),
        label = "pulse_scale"
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Doctas", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = greatVibesFamily, fontSize = 36.sp), color = MaterialTheme.colorScheme.primary) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    Surface(onClick = { showSheetDialog = true }, color = Color.Transparent, modifier = Modifier.padding(end = 8.dp) ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("Open G-Sheet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Image(painter = painterResource(id = R.drawable.ic_sheets), contentDescription = "Open Database", contentScale = ContentScale.Fit, modifier = Modifier.size(50.dp).clip(CircleShape))
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Surface(modifier = Modifier.fillMaxSize().padding(paddingValues), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                    val displayText = if (partialText.isNotBlank()) "$finalText $partialText" else finalText

                    Card(modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 300.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        OutlinedTextField(
                            value = displayText,
                            onValueChange = { newText -> finalText = newText },
                            placeholder = { Text("Press the mic to Start capturing patient details...", color = MaterialTheme.colorScheme.outlineVariant) },
                            modifier = Modifier.fillMaxSize(),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 28.sp, color = MaterialTheme.colorScheme.onSurface),
                            singleLine = false,
                            maxLines = Int.MAX_VALUE,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedBorderColor = PrimaryMainLight,
                                unfocusedBorderColor = PrimaryPressedLight,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Invisible Bar with Status and Upload Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val statusText = when (uiState) {
                            UiState.IDLE -> if (isOnline) "Ready" else "Offline"
                            UiState.LISTENING -> "Listening"
                            UiState.PROCESSING -> "Sending..."
                            UiState.QUEUED -> "Queued (Offline)"
                            UiState.SUCCESS -> "Done"
                            UiState.ERROR -> "Error"
                        }
                        val statusColor = when (uiState) {
                            UiState.IDLE -> if (isOnline) MaterialTheme.colorScheme.onSurfaceVariant else Yellow
                            UiState.LISTENING -> Green
                            UiState.PROCESSING -> Yellow
                            UiState.QUEUED -> MaterialTheme.colorScheme.primary
                            UiState.SUCCESS -> Green
                            UiState.ERROR -> Red
                        }

                        SuggestionChip(
                            onClick = {},
                            label = { Text(statusText, style = MaterialTheme.typography.labelSmall) },
                            colors = SuggestionChipDefaults.suggestionChipColors(labelColor = statusColor, containerColor = statusColor.copy(alpha = 0.1f)),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(0.6f)
                        )

                        // ECG Button
                        Button(
                            onClick = {
                                capturingType = "ecg"
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    isCameraOpen = true
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (ecgBitmap != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (ecgBitmap != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(if (ecgBitmap != null) Icons.Default.CheckCircle else Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("ECG", style = MaterialTheme.typography.labelSmall)
                        }

                        // ABG Button
                        Button(
                            onClick = {
                                capturingType = "abg"
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    isCameraOpen = true
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (abgBitmap != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (abgBitmap != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(if (abgBitmap != null) Icons.Default.CheckCircle else Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("ABG", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isListeningDesired) SineWave(rmsdB = rmsdB) else Spacer(modifier = Modifier.height(80.dp))

                    Spacer(modifier = Modifier.height(16.dp))

                    // Microphone Button
                    val isDark = isSystemInDarkTheme()
                    val micShadow = if (isDark) MicShadowDark else MicShadowLight
                    Box(modifier = Modifier.size(120.dp).graphicsLayer { scaleX = pulseScale; scaleY = pulseScale }.shadow(elevation = 8.dp, shape = CircleShape, ambientColor = micShadow, spotColor = micShadow).clip(CircleShape).background(MicBackground).border(4.dp, borderColor, CircleShape).clickable {
                        if (isListeningDesired) stopListening() else { if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening() else recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    }) {
                        Icon(imageVector = Icons.Default.Mic, contentDescription = "Mic", tint = MicIcon, modifier = Modifier.align(Alignment.Center).size(48.dp))
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(onClick = { stopListening(); finalText = ""; partialText = ""; lastExtractedData = null; ecgBitmap = null; abgBitmap = null; stagedData = null }, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Clear")
                        }
                        Button(onClick = {
                            if (finalText.isNotBlank()) {
                                stopListening()
                                uiState = UiState.PROCESSING
                                coroutineScope.launch {
                                    if (isOnline) {
                                        val result = submitPatientData(finalText, abgBitmap, ecgBitmap)
                                        if (result.isSuccess) {
                                            uiState = UiState.SUCCESS
                                            finalText = ""
                                            partialText = ""
                                            lastExtractedData = null
                                            ecgBitmap = null
                                            abgBitmap = null
                                        } else {
                                            // Failed due to error (maybe timeout) -> Queue it
                                            stagedData = StagedData(finalText, abgBitmap, ecgBitmap)
                                            uiState = UiState.QUEUED
                                            finalText = ""
                                            partialText = ""
                                            ecgBitmap = null
                                            abgBitmap = null
                                        }
                                    } else {
                                        // Offline -> Queue it
                                        stagedData = StagedData(finalText, abgBitmap, ecgBitmap)
                                        uiState = UiState.QUEUED
                                        finalText = ""
                                        partialText = ""
                                        ecgBitmap = null
                                        abgBitmap = null
                                    }
                                }
                            }
                        }, enabled = finalText.isNotBlank() && uiState != UiState.PROCESSING, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant, disabledContentColor = MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(12.dp)) {
                            if (uiState == UiState.PROCESSING) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp) else { Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Send") }
                        }
                    }
                }

                // Camera Preview Popup
                if (isCameraOpen) {
                    Popup(
                        alignment = Alignment.Center,
                        onDismissRequest = { isCameraOpen = false },
                        properties = PopupProperties(focusable = true, dismissOnClickOutside = false)
                    ) {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            CameraCaptureView(
                                onImageCaptured = { bitmap ->
                                    if (capturingType == "ecg") ecgBitmap = bitmap
                                    else if (capturingType == "abg") abgBitmap = bitmap
                                    isCameraOpen = false
                                },
                                onCancel = { isCameraOpen = false }
                            )
                        }
                    }
                }

                if (showSheetDialog) {
                    AlertDialog(onDismissRequest = { showSheetDialog = false }, containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface, textContentColor = MaterialTheme.colorScheme.onSurfaceVariant, icon = { Image(painter = painterResource(id = R.drawable.ic_sheets), contentDescription = null, modifier = Modifier.size(50.dp)) }, title = { Text("Google Sheet") }, text = { Text("Do you want to visit the patient database?") }, confirmButton = { TextButton(onClick = { showSheetDialog = false; uriHandler.openUri(sheetUrl) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)) { Text("Open") } }, dismissButton = { TextButton(onClick = { showSheetDialog = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text("Cancel") } })
                }
            }
        }
    }
}

@Composable
fun CameraCaptureView(onImageCaptured: (Bitmap) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView({ previewView }, modifier = Modifier.fillMaxSize())

        IconButton(onClick = onCancel, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(32.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 120.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                onClick = {
                    imageCapture.takePicture(
                        cameraExecutor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                onImageCaptured(bitmap)
                                image.close()
                            }
                            override fun onError(exception: ImageCaptureException) {
                                exception.printStackTrace()
                            }
                        }
                    )
                },
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                border = BorderStroke(4.dp, Color.LightGray)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.size(32.dp).background(Color.Red.copy(alpha = 0.2f), RoundedCornerShape(8.dp)))
                }
            }
        }
    }
}

@Composable
fun SineWave(rmsdB: Float) {
    val animatedRms by animateFloatAsState(targetValue = rmsdB, label = "rms", animationSpec = tween(100))
    val infiniteTransition = rememberInfiniteTransition(label = "wave_phase")
    val phases = List(3) { index -> infiniteTransition.animateFloat(initialValue = 0f, targetValue = 2 * PI.toFloat(), animationSpec = infiniteRepeatable(animation = tween(durationMillis = 800 + (index * 300), easing = LinearEasing), repeatMode = RepeatMode.Restart), label = "phase_$index") }
    Canvas(modifier = Modifier.fillMaxWidth().height(80.dp)) {
        val width = size.width
        val height = size.height
        val centerY = height / 2
        phases.forEachIndexed { index, phaseState ->
            val path = Path()
            path.moveTo(0f, centerY)
            val normalizedRms = ((animatedRms + 2) / 12).coerceIn(0f, 1f)
            val amplitude = normalizedRms * 40.dp.toPx() * (1f - index * 0.2f)
            for (x in 0..width.toInt() step 5) {
                val angle = (x / width) * 2 * PI * 1.5 + phaseState.value + index
                val y = centerY + sin(angle).toFloat() * amplitude
                path.lineTo(x.toFloat(), y)
            }
            drawPath(path = path, color = PrimaryMainLight.copy(alpha = 0.6f - index * 0.2f), style = Stroke(width = (3 - index).dp.toPx()))
        }
    }
}
