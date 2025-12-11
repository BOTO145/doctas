@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.andlab.doctas

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.foundation.Image // Needed for JPGs
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.layout.ContentScale


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
    var isListeningDesired by remember { mutableStateOf(false) }
    var uiState by rememberSaveable { mutableStateOf(UiState.IDLE) }
    var rmsdB by remember { mutableFloatStateOf(0f) }

    // NEW: Track last speech time for smarter silence handling
    var lastSpeechTime by remember { mutableLongStateOf(0L) }
    var hasSpokenRecently by remember { mutableStateOf(false) }

    // NEW: Debounce final results to prevent duplicates
    var lastProcessedResult by remember { mutableStateOf("") }

    var showSheetDialog by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val sheetUrl = "https://docs.google.com/spreadsheets/d/1_NlDGWglSz9Z9BuTUktX7mwUfUMnhlhmuA1gnTVcAxA"

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // --- Improved Speech Recognizer Intent Setup ---
    val speechRecognizerIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3) // Get more alternatives for better accuracy

            // IMPROVED: More Gboard-like timing
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)

            // NEW: Enable more aggressive recognition
            putExtra("android.speech.extra.DICTATION_MODE", true) // Continuous dictation mode
        }
    }

    // --- Logic Helpers ---
    val startListening = {
        isListeningDesired = true
        uiState = UiState.LISTENING
        lastSpeechTime = System.currentTimeMillis()
        hasSpokenRecently = false
        speechRecognizer.startListening(speechRecognizerIntent)
    }

    val stopListening = {
        isListeningDesired = false
        uiState = UiState.IDLE
        hasSpokenRecently = false
        speechRecognizer.stopListening()
    }

    val listener = remember {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (isListeningDesired) uiState = UiState.LISTENING
            }

            override fun onBeginningOfSpeech() {
                lastSpeechTime = System.currentTimeMillis()
                hasSpokenRecently = true
            }

            override fun onRmsChanged(rmsdBVal: Float) {
                if (isListeningDesired) {
                    rmsdB = rmsdBVal
                    // Update speech activity based on volume
                    if (rmsdBVal > 0) {
                        lastSpeechTime = System.currentTimeMillis()
                        hasSpokenRecently = true
                    }
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                // Don't change UI state - let onResults handle it
            }

            override fun onError(error: Int) {
                if (!isListeningDesired) return

                val currentTime = System.currentTimeMillis()
                val timeSinceLastSpeech = currentTime - lastSpeechTime

                // IMPROVED: Smart restart logic based on context
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        // If user spoke recently, this might be mid-sentence - restart immediately
                        if (hasSpokenRecently && timeSinceLastSpeech < 3000) {
                            speechRecognizer.startListening(speechRecognizerIntent)
                        } else {
                            // Long silence, restart normally
                            hasSpokenRecently = false
                            speechRecognizer.startListening(speechRecognizerIntent)
                        }
                    }

                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Restart immediately - this is just silence
                        speechRecognizer.startListening(speechRecognizerIntent)
                    }

                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        // Network issue - try to recreate recognizer
                        try {
                            speechRecognizer.destroy()
                            val newRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                            newRecognizer.setRecognitionListener(this)
                            newRecognizer.startListening(speechRecognizerIntent)
                        } catch (e: Exception) {
                            uiState = UiState.ERROR
                            isListeningDesired = false
                        }
                    }

                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // Wait a bit then restart
                        coroutineScope.launch {
                            kotlinx.coroutines.delay(100)
                            if (isListeningDesired) {
                                speechRecognizer.startListening(speechRecognizerIntent)
                            }
                        }
                    }

                    SpeechRecognizer.ERROR_AUDIO,
                    SpeechRecognizer.ERROR_CLIENT,
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        // Fatal errors
                        uiState = UiState.ERROR
                        isListeningDesired = false
                    }

                    else -> {
                        // Unknown error, try to restart once
                        speechRecognizer.startListening(speechRecognizerIntent)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val result = matches?.getOrNull(0) ?: ""

                // NEW: Debounce duplicate results
                if (result.isNotBlank() && result != lastProcessedResult) {
                    lastProcessedResult = result

                    // Smart text appending with punctuation awareness
                    finalText = when {
                        finalText.isBlank() -> result
                        // If last char is punctuation, capitalize next word
                        finalText.last() in listOf('.', '!', '?') -> {
                            "$finalText ${result.replaceFirstChar { it.uppercase() }}"
                        }
                        // Normal append with space
                        else -> "$finalText $result"
                    }
                }

                partialText = ""
                lastSpeechTime = System.currentTimeMillis()

                // IMPROVED: Faster restart for continuous feel
                if (isListeningDesired) {
                    // Immediate restart without delay
                    speechRecognizer.startListening(speechRecognizerIntent)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val result = matches?.getOrNull(0) ?: ""

                if (result.isNotBlank()) {
                    // NEW: Only update if it's actually different
                    if (result != partialText) {
                        partialText = result
                        lastSpeechTime = System.currentTimeMillis()
                    }
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
        onDispose {
            speechRecognizer.setRecognitionListener(null)
        }
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

    Scaffold { paddingValues ->
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
                    // 1. Text Editing Area with live preview
                    val displayText = if (partialText.isNotBlank()) {
                        if (finalText.isBlank()) {
                            partialText
                        } else {
                            "$finalText $partialText"
                        }
                    } else {
                        finalText
                    }

                    OutlinedTextField(
                        value = displayText,
                        onValueChange = { newText ->
                            finalText = newText
                            partialText = ""
                        },
                        label = { Text("Recognized Text") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        textStyle = MaterialTheme.typography.headlineSmall,
                        singleLine = false,
                        maxLines = Int.MAX_VALUE
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 2. Sine Wave Visualization
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
                                if (isListeningDesired) {
                                    stopListening()
                                } else {
                                    if (ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                    ) {
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

                    // 4. Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = {
                                stopListening()
                                finalText = ""
                                partialText = ""
                                lastProcessedResult = ""
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text("Clear")
                        }

                        Button(
                            onClick = {
                                if (finalText.isNotBlank()) {
                                    stopListening()
                                    uiState = UiState.PROCESSING
                                    coroutineScope.launch {
                                        val result = sendHealthData(finalText)
                                        uiState = if (result.isSuccess) {
                                            UiState.SUCCESS
                                        } else {
                                            UiState.ERROR
                                        }
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
                                Text("Send to Server")
                            }
                        }
                    }
                }

                // Top Right Icon
                IconButton(
                    onClick = { showSheetDialog = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_sheets),
                        contentDescription = "Open Database",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                    )
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




@Composable
fun SineWave(rmsdB: Float) {
    // Smoothly animate the amplitude jumping
    val animatedRms by animateFloatAsState(
        targetValue = rmsdB,
        label = "rms",
        animationSpec = tween(100) // fast reaction
    )

    // We use infiniteTransition for the phase to ensure it loops perfectly forever
    val infiniteTransition = rememberInfiniteTransition(label = "wave_phase")

    // Create 5 different phase animations with slightly different speeds
    val phases = List(5) { index ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2 * PI.toFloat(),
            animationSpec = infiniteRepeatable(
                // Different durations create the "drifting" layered effect
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
