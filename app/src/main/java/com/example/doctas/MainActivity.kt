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
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.andlab.doctas.ui.theme.DoctasTheme
import com.andlab.doctas.ui.theme.Green
import com.andlab.doctas.ui.theme.Red
import com.andlab.doctas.ui.theme.Yellow
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
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Speech recognition is not available on this device.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(16.dp)
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
    // Add this block to configure timeouts
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000L  // 30 seconds for the request to complete
        connectTimeoutMillis = 30_000L  // 30 seconds to establish a connection
        socketTimeoutMillis = 30_000L   // 30 seconds of inactivity between data packets
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

// Replace your existing VoiceAssistanceScreen composable with this improved version

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAssistanceScreen(speechRecognizer: SpeechRecognizer) {
    // --- State Variables ---
    var finalText by rememberSaveable { mutableStateOf("") }
    var partialText by remember { mutableStateOf("") }

    // The "Master Switch": If this is true, we force the recognizer to restart
    var isListeningDesired by remember { mutableStateOf(false) }
    var uiState by rememberSaveable { mutableStateOf(UiState.IDLE) }

    var rmsdB by remember { mutableFloatStateOf(0f) }

    // --- State for Dialog and URL ---
    var showSheetDialog by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val sheetUrl = "https://docs.google.com/spreadsheets/d/1_NlDGWglSz9Z9BuTUktX7mwUfUMnhlhmuA1gnTVcAxA"

    val context = LocalContext.current
    val view = LocalView.current // For haptic feedback
    val coroutineScope = rememberCoroutineScope()

    // --- 1. DEFAULT SETTINGS for Intent ---
    val speechRecognizerIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // Use FREE_FORM for better dictation (vs WEB_SEARCH for commands)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // We use the "DICTATION_MODE" flag as a hint to the OS to listen longer if possible
            putExtra("android.speech.extra.DICTATION_MODE", true)
        }
    }

    // --- Logic Helpers ---
    val startListening = {
        isListeningDesired = true
        uiState = UiState.LISTENING
        speechRecognizer.startListening(speechRecognizerIntent)
    }

    val stopListening = {
        isListeningDesired = false
        uiState = UiState.IDLE
        speechRecognizer.stopListening()
    }

    // --- 2. CONTINUOUS SPEECH LOGIC ---
    val listener = remember {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (isListeningDesired) uiState = UiState.LISTENING
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdBVal: Float) {
                if (isListeningDesired) rmsdB = rmsdBVal
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                // The engine paused (silence). Do NOT stop the UI here.
            }

            override fun onError(error: Int) {
                if (!isListeningDesired) return // We stopped it manually

                // These errors mean "Silence" or "Timeout" -> Restart immediately
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    speechRecognizer.startListening(speechRecognizerIntent)
                } else {
                    // For other errors, try to restart once, but update UI if it fails
                    // (Optional: You could count retries here to avoid infinite loops)
                    speechRecognizer.startListening(speechRecognizerIntent)
                }
            }

            override fun onResults(results: Bundle?) {
                val result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.getOrNull(0) ?: ""

                if (result.isNotBlank()) {
                    finalText = if (finalText.isBlank()) result else "$finalText $result"
                }
                partialText = ""

                // RESTART LOGIC: If we still want to listen, start again instantly
                if (isListeningDesired) {
                    speechRecognizer.startListening(speechRecognizerIntent)
                } else {
                    uiState = UiState.IDLE
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val result = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.getOrNull(0) ?: ""
                if (result.isNotBlank()) {
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

    // --- Animation & Color Logic ---
    val borderColor = when (uiState) {
        UiState.LISTENING -> Green
        UiState.PROCESSING -> Yellow
        UiState.ERROR -> Red
        UiState.SUCCESS -> Green
        else -> MaterialTheme.colorScheme.onSurface
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (uiState == UiState.LISTENING) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Doctas", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { showSheetDialog = true }) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_sheets),
                            contentDescription = "Open Database",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    //1. Text Editing Area (Scrollable & Live Preview)
                    val displayText = if (partialText.isNotBlank()) {
                        "$finalText $partialText"
                    } else {
                        finalText
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f) // Keep this to fill available space
                            .heightIn(min = 300.dp), // <--- ADD THIS LINE
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.Transparent
                        )
                    ) {

                    OutlinedTextField(
                            value = displayText,
                            onValueChange = { newText ->
                                finalText = newText
                            },
                            label = { Text("Recognized Text") },
                            modifier = Modifier.fillMaxSize(),
                            textStyle = MaterialTheme.typography.headlineSmall.copy(
                                lineHeight = MaterialTheme.typography.headlineSmall.lineHeight * 1.5
                            ),
                            singleLine = false,
                            maxLines = Int.MAX_VALUE,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // --- NEW: Test Example Button ---
                    // Placed between Text Area and Mic/Wave
                    TextButton(
                        onClick = {
                            finalText = "Ramesh Kumar, age 50, male, CR-98765, shortness of breath, Glasgow Coma Scale 13, red initial triage, yellow final triage, BP 160/95, pulse rate 95, respiratory rate 22, SpO2 89%, chest X-ray and CT scan advised, radiology completed, report reported, admitted to respiratory medicine"
                            partialText = "" // Clear any partial text
                        }
                    ) {
                        Text("Insert Test Example")
                    }
                    // --------------------------------

                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Status Indicator
                    val statusText = when (uiState) {
                        UiState.IDLE -> "Tap mic to start"
                        UiState.LISTENING -> "Listening..."
                        UiState.PROCESSING -> "Processing Data..."
                        UiState.SUCCESS -> "Data Sent Successfully!"
                        UiState.ERROR -> "Error Sending Data"
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
                        label = { Text(statusText) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            labelColor = statusColor
                        ),
                        border = BorderStroke(1.dp, statusColor)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 2. Sine Wave Visualization
                    // Only show when "Desired" so it doesn't flicker during restart
                    if (isListeningDesired) {
                        SineWave(rmsdB = rmsdB)
                    } else {
                        Spacer(modifier = Modifier.height(100.dp))
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 3. Microphone Button
                    Box(
                        modifier = Modifier
                            .size(150.dp)
                            .graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                            }
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .border(4.dp, borderColor, CircleShape)
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
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
                            tint = borderColor,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(64.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // 4. Action Buttons (Clear & Send)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        OutlinedButton(
                            onClick = {
                                stopListening()
                                finalText = ""
                                partialText = ""
                            },
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
                                        uiState = if (result.isSuccess) UiState.SUCCESS else UiState.ERROR
                                    }
                                }
                            },
                            enabled = finalText.isNotBlank() && uiState != UiState.PROCESSING
                        ) {
                            if (uiState == UiState.PROCESSING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Send to DB")
                            }
                        }
                    }
                }

                // Popup Dialog
                if (showSheetDialog) {
                    AlertDialog(
                        onDismissRequest = { showSheetDialog = false },
                        icon = {
                            Image(
                                painter = painterResource(id = R.drawable.ic_sheets),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        title = { Text("Google Sheet") },
                        text = { Text("Do you want to visit the database?") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showSheetDialog = false
                                    uriHandler.openUri(sheetUrl)
                                }
                            ) {
                                Text("Visit")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSheetDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}

// Fixed SineWave Function
@Composable
fun SineWave(rmsdB: Float) {
    // Smoothly animate the amplitude jumping
    val animatedRms by animateFloatAsState(
        targetValue = rmsdB,
        label = "rms",
        animationSpec = tween(100)
    )

    // We use infiniteTransition for the phase to ensure it loops perfectly forever
    val infiniteTransition = rememberInfiniteTransition(label = "wave_phase")

    // Create 5 different phase animations with slightly different speeds
    val phases = List(5) { index ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2 * PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000 + (index * 500), easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase_$index"
        )
    }

    Canvas(modifier = Modifier
        .fillMaxWidth()
        .height(100.dp)) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        phases.forEachIndexed { index, phaseState ->
            val path = Path()
            path.moveTo(0f, centerY)

            // Map rmsdB (-2 to 10 usually) to an amplitude
            val normalizedRms = ((animatedRms + 2) / 12).coerceIn(0f, 1f)
            val amplitude = normalizedRms * 50.dp.toPx() * (1f - index * 0.15f)

            // Draw the wave
            for (x in 0..width.toInt() step 10) {
                // Use phaseState.value to get the current animated value
                val angle = (x / width) * 2 * PI + phaseState.value + index
                val y = centerY + sin(angle).toFloat() * amplitude
                path.lineTo(x.toFloat(), y)
            }

            drawPath(
                path = path,
                color = Green.copy(alpha = 0.5f - index * 0.1f),
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
}
