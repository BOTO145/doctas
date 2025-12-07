# Doctas

**Doctas** is a voice-powered medical data entry assistant designed to streamline the workflow for healthcare professionals. It allows users to speak naturally, transcribes their speech into text, and securely transmits the data to a remote server for processing and storage.

## 🚀 Core Features

*   **Voice-to-Text Transcription**:
    *   Utilizes Android's native `SpeechRecognizer` for accurate speech capture.
    *   Supports continuous listening with automatic restarts for long sentences.
    *   Real-time display of both partial (in-progress) and final transcribed text.
*   **Intuitive User Interface**:
    *   **Interactive Microphone**: Visual feedback with pulsing animations and color changes (Green for listening, Yellow for processing).
    *   **Audio Visualizer**: Real-time sine wave animation reacting to voice volume (RMS dB).
    *   **Status Indicators**: Clear loading spinners and success/error messages for server communication.
*   **Server Communication**:
    *   Seamless integration with a remote backend using **Ktor**.
    *   Sends transcribed text via HTTP POST and parses structured JSON responses.
*   **External Data Access**:
    *   Quick access to external resources (e.g., Google Sheets) via an in-app popup dialog.

## 🛠 Technical Architecture

The application follows modern Android development practices, built entirely in **Kotlin** and **Jetpack Compose**.

### Tech Stack
*   **Language**: Kotlin
*   **UI Framework**: Jetpack Compose (Material 3)
*   **Networking**: Ktor Client (CIO engine)
*   **Serialization**: Kotlinx Serialization
*   **Asynchronous Processing**: Kotlin Coroutines
*   **Compatibility**: Min SDK 24, Target SDK 35

### Key Components
*   **`MainActivity.kt`**: The entry point that initializes the `SpeechRecognizer` and handles runtime permissions (`RECORD_AUDIO`).
*   **`VoiceAssistanceScreen`**: The primary stateful composable managing:
    *   UI State (`idle`, `listening`, `processing`, `success/error`).
    *   Speech recognition callbacks (`onResults`, `onError`).
    *   Audio level visualization logic.
*   **Networking**: A dedicated `sendHealthData` function uses a singleton Ktor `HttpClient` to communicate with the endpoint `api/health-data`.
*   **Custom UI**: Includes a custom canvas-drawn `SineWave` composable for dynamic audio visualization.

## 📦 Dependencies

Key libraries used in this project:

```kotlin
// UI
implementation(platform("androidx.compose:compose-bom:2024.09.00"))
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.material:material-icons-extended-android:1.6.7")

// Networking (Ktor)
implementation("io.ktor:ktor-client-core:2.3.10")
implementation("io.ktor:ktor-client-cio:2.3.10")
implementation("io.ktor:ktor-client-content-negotiation:2.3.10")
implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.10")

// Serialization
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
```

## 🔧 Setup & Installation

1.  Clone the repository:
    ```bash
    git clone https://github.com/yourusername/doctas.git
    ```
2.  Open the project in **Android Studio**.
3.  Sync the project with Gradle files.
4.  Run the application on an emulator or physical device.
    *   *Note: A physical device is recommended for accurate speech recognition testing.*
5.  Grant the **Microphone Permission** when prompted.

## 📱 Usage

1.  **Start Recording**: Tap the central microphone icon. The border will turn green, and the wave visualizer will react to your voice.
2.  **Speak**: Dictate the medical notes or patient data. The text will appear in real-time in the text field.
3.  **Edit**: You can manually edit the transcribed text in the text field if corrections are needed.
4.  **Send**: The app automatically attempts to process the text, or you can use the provided action buttons to send the data to the server.
5.  **View Status**: Watch for the loading spinner and subsequent success/error messages.

## 📄 License

[Choose your license, e.g., MIT License]
