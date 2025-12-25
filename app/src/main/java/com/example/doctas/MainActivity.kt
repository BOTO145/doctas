@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.andlab.doctas

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.andlab.doctas.ui.theme.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.*
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            // Handle potential crash on destroy
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

suspend fun sendHealthData(text: String): Result<FormattedData> {
    return try {
        val httpResponse: HttpResponse = client.post("https://f311e11ce25b.ngrok-free.app/api/health-data") {
            contentType(ContentType.Application.Json)
            setBody(HealthDataRequest(text = text))
        }
        val formattedData = httpResponse.body<FormattedData>()
        Result.success(formattedData)
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
    ERROR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAssistanceScreen(speechRecognizer: SpeechRecognizer) {
    // ADD these with other state variables:
    var consecutiveErrors by remember { mutableIntStateOf(0) }
    var lastRecognitionTime by remember { mutableLongStateOf(0L) }
    var finalText by rememberSaveable { mutableStateOf("") }
    var partialText by remember { mutableStateOf("") }
    var isListeningDesired by remember { mutableStateOf(false) }
    var uiState by rememberSaveable { mutableStateOf(UiState.IDLE) }
    var rmsdB by remember { mutableFloatStateOf(0f) }
    var lastExtractedData by remember { mutableStateOf<FormattedData?>(null) }

    var showSheetDialog by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val sheetUrl = "https://docs.google.com/spreadsheets/d/1_NlDGWglSz9Z9BuTUktX7mwUfUMnhlhmuA1gnTVcAxA"

    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    // Local Font setup
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

            // GBOARD-LIKE FORMATTING: Enable automatic punctuation and capitalization
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, true)

            // CONTINUOUS MODE: Helps with longer dictation sessions
            putExtra("android.speech.extra.DICTATION_MODE", true)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)

            // SILENCE THRESHOLDS: Fine-tune for 2025 device standards
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 100000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 100000L)
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
                    // Filter out very quiet noise
                    rmsdB = if (rmsdBVal > -2.0f) rmsdBVal else 0f
                }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (!isListeningDesired) return

                consecutiveErrors++

                if (consecutiveErrors >= 3) {
                    uiState = UiState.ERROR
                    isListeningDesired = false
                    consecutiveErrors = 0
                    return
                }

                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Wait before restarting
                        coroutineScope.launch {
                            kotlinx.coroutines.delay(800)
                            if (isListeningDesired) {
                                speechRecognizer.startListening(speechRecognizerIntent)
                            }
                        }
                    }
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> {
                        uiState = UiState.ERROR
                        isListeningDesired = false
                        consecutiveErrors = 0
                    }
                    else -> {
                        coroutineScope.launch {
                            kotlinx.coroutines.delay(500)
                            if (isListeningDesired) {
                                speechRecognizer.startListening(speechRecognizerIntent)
                            }
                        }
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.getOrNull(0) ?: ""
                if (result.isNotBlank()) {
                    // Reset error count on success
                    consecutiveErrors = 0

                    // Smart punctuation
                    val needsPunctuation = finalText.isNotBlank() &&
                            !finalText.endsWith(".") &&
                            !finalText.endsWith("?") &&
                            !finalText.endsWith("!")

                    val capitalizedResult = if (finalText.isBlank() || finalText.endsWith(".")) {
                        result.replaceFirstChar { it.uppercase() }
                    } else {
                        result
                    }

                    finalText = when {
                        finalText.isBlank() -> capitalizedResult
                        needsPunctuation -> "$finalText. $capitalizedResult"
                        else -> "$finalText $capitalizedResult"
                    }

                    // Haptic feedback on successful capture
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }

                partialText = ""
                lastRecognitionTime = System.currentTimeMillis()

                if (isListeningDesired) {
                    // Delay before restart to avoid cutting off speech
                    coroutineScope.launch {
                        kotlinx.coroutines.delay(400)
                        if (isListeningDesired) {
                            speechRecognizer.startListening(speechRecognizerIntent)
                        }
                    }
                } else {
                    uiState = UiState.IDLE
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val result = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.getOrNull(0) ?: ""
                // Only update if significantly different and not too short
                if (result.isNotBlank() && result.length > 2 && result != partialText) {
                    partialText = result
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
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
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        text = "Doctas", 
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = greatVibesFamily,
                            fontSize = 36.sp
                        ), 
                        color = MaterialTheme.colorScheme.primary 
                    ) 
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    Surface(
                        onClick = { showSheetDialog = true },
                        color = Color.Transparent,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Open G-Sheet",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Image(
                                painter = painterResource(id = R.drawable.ic_sheets),
                                contentDescription = "Open Database",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    val displayText = if (partialText.isNotBlank()) {
                        "$finalText $partialText"
                    } else {
                        finalText
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .heightIn(min = 300.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),

                    ) {
                        OutlinedTextField(
                            value = displayText,
                            onValueChange = { newText -> finalText = newText },
                            placeholder = { Text("Press the mic to Start capturing patient details...", color = MaterialTheme.colorScheme.outlineVariant) },
                            modifier = Modifier.fillMaxSize(),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 18.sp,
                                lineHeight = 28.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
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

                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val statusText = when (uiState) {
                        UiState.IDLE -> "Ready to record"
                        UiState.LISTENING -> "Listening..."
                        UiState.PROCESSING -> "Analyzing medical data..."
                        UiState.SUCCESS -> "Data captured successfully!"
                        UiState.ERROR -> "Failed to process data"
                    }
                    
                    val statusColor = when (uiState) {
                        UiState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                        UiState.LISTENING -> Green
                        UiState.PROCESSING -> Yellow
                        UiState.SUCCESS -> Green
                        UiState.ERROR -> Red
                    }

                    SuggestionChip(
                        onClick = {},
                        label = { Text(statusText, style = MaterialTheme.typography.labelLarge) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            labelColor = statusColor,
                            containerColor = statusColor.copy(alpha = 0.1f)
                        ),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isListeningDesired) {
                        SineWave(rmsdB = rmsdB)
                    } else {
                        Spacer(modifier = Modifier.height(80.dp))
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 3. Microphone Button
                    val isDark = isSystemInDarkTheme()
                    val micShadow = if (isDark) MicShadowDark else MicShadowLight
                    
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                            }
                            .shadow(elevation = 8.dp, shape = CircleShape, ambientColor = micShadow, spotColor = micShadow)
                            .clip(CircleShape)
                            .background(MicBackground)
                            .border(4.dp, borderColor, CircleShape)
                            .clickable {
                                if (isListeningDesired) {
                                    stopListening()
                                } else {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        startListening()
                                    } else {
                                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Mic",
                            tint = MicIcon,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(48.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                stopListening()
                                finalText = ""
                                partialText = ""
                                lastExtractedData = null
                            },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Clear")
                        }

                        Button(
                            onClick = {
                                if (finalText.isNotBlank()) {
                                    stopListening()
                                    uiState = UiState.PROCESSING
                                    coroutineScope.launch {
                                        val result = sendHealthData(finalText)
                                        if (result.isSuccess) {
                                            uiState = UiState.SUCCESS
                                            lastExtractedData = result.getOrNull()
                                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        } else {
                                            uiState = UiState.ERROR
                                            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                                        }
                                    }
                                }
                            },
                            enabled = finalText.isNotBlank() && uiState != UiState.PROCESSING,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                disabledContentColor = MaterialTheme.colorScheme.outlineVariant
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (uiState == UiState.PROCESSING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Send")
                            }
                        }
                    }
                }

                if (showSheetDialog) {
                    AlertDialog(
                        onDismissRequest = { showSheetDialog = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        icon = {
                            Image(
                                painter = painterResource(id = R.drawable.ic_sheets),
                                contentDescription = null,
                                modifier = Modifier.size(50.dp)
                            )
                        },
                        title = { Text("Google Sheet") },
                        text = { Text("Do you want to visit the patient database?") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showSheetDialog = false
                                    uriHandler.openUri(sheetUrl)
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Open")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showSheetDialog = false },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                            ) {
                                Text("Cancel")
                            }
                        }
                    )
                }
                
                // Success Overlay for Extracted Data
                AnimatedVisibility(
                    visible = uiState == UiState.SUCCESS && lastExtractedData != null,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp, start = 16.dp, end = 16.dp)
                ) {
                    lastExtractedData?.let { data ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Extracted Data", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Patient: ${data.name ?: "Unknown"}, HR: ${data.heartRate ?: "--"} bpm, SpO2: ${data.spo2 ?: "--"}%",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                TextButton(
                                    onClick = { lastExtractedData = null; uiState = UiState.IDLE },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SineWave(rmsdB: Float) {
    val animatedRms by animateFloatAsState(
        targetValue = rmsdB,
        label = "rms",
        animationSpec = tween(100)
    )

    val infiniteTransition = rememberInfiniteTransition(label = "wave_phase")

    val phases = List(3) { index ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2 * PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800 + (index * 300), easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase_$index"
        )
    }

    Canvas(modifier = Modifier
        .fillMaxWidth()
        .height(80.dp)) {
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

            drawPath(
                path = path,
                color = PrimaryMainLight.copy(alpha = 0.6f - index * 0.2f),
                style = Stroke(width = (3 - index).dp.toPx())
            )
        }
    }
}
