package com.example.teleprompterapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TeleprompterApp()
            }
        }
    }
}

data class CameraScene(
    val originalSceneNumber: Int,
    val readOrder: Int,
    val text: String
)

@Composable
fun TeleprompterApp() {
    val context = LocalContext.current
    var scenes by remember { mutableStateOf<List<CameraScene>>(emptyList()) }
    var currentSceneIndex by remember { mutableStateOf(0) }
    var isTeleprompterRunning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("JSON dosyasını seçerek başlayın.") }

    var wordsPerMinute by remember { mutableStateOf(110f) }
    var eyeLineDp by remember { mutableStateOf(64f) }
    var pauseDetectionEnabled by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            message = "Dosya seçilmedi."
            return@rememberLauncherForActivityResult
        }
        val result = parseCameraScenesFromJson(context, uri)
        if (result.isSuccess) {
            val parsedScenes = result.getOrDefault(emptyList())
            scenes = parsedScenes
            currentSceneIndex = 0
            message = if (parsedScenes.isEmpty()) {
                "Dosyada type: camera olan sahne bulunamadı."
            } else {
                "${parsedScenes.size} kamera sahnesi içe aktarıldı."
            }
        } else {
            scenes = emptyList()
            currentSceneIndex = 0
            message = result.exceptionOrNull()?.message ?: "JSON dosyası okunamadı."
        }
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val cameraGranted = perms[Manifest.permission.CAMERA] == true
        val audioGranted = perms[Manifest.permission.RECORD_AUDIO] == true
        if (cameraGranted && audioGranted) {
            filePickerLauncher.launch(arrayOf("*/*"))
        } else {
            message = "Kamera ve mikrofon izni verilmeden uygulama çalışmaz."
        }
    }

    if (isTeleprompterRunning) {
        TeleprompterScreen(
            scenes = scenes,
            currentIndex = currentSceneIndex,
            wordsPerMinute = wordsPerMinute,
            eyeLineDp = eyeLineDp,
            pauseDetectionEnabled = pauseDetectionEnabled,
            onNextScene = { nextIndex ->
                if (nextIndex >= scenes.size) {
                    isTeleprompterRunning = false
                    currentSceneIndex = 0
                    message = "Tüm kamera sahneleri tamamlandı."
                } else {
                    currentSceneIndex = nextIndex
                }
            },
            onCancel = {
                isTeleprompterRunning = false
                currentSceneIndex = 0
                message = "Kayıt akışı durduruldu."
            }
        )
    } else {
        HomeScreen(
            message = message,
            scenes = scenes,
            wordsPerMinute = wordsPerMinute,
            eyeLineDp = eyeLineDp,
            pauseDetectionEnabled = pauseDetectionEnabled,
            onWordsPerMinuteChange = { wordsPerMinute = it },
            onEyeLineChange = { eyeLineDp = it },
            onPauseDetectionChange = { pauseDetectionEnabled = it },
            onPickFile = {
                val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                val audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (cameraGranted && audioGranted) {
                    filePickerLauncher.launch(arrayOf("*/*"))
                } else {
                    permissionsLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                }
            },
            onStart = {
                if (scenes.isEmpty()) {
                    message = "Önce içinde type: camera sahneleri olan bir JSON dosyası seçin."
                } else {
                    currentSceneIndex = 0
                    isTeleprompterRunning = true
                }
            }
        )
    }
}

@Composable
fun HomeScreen(
    message: String,
    scenes: List<CameraScene>,
    wordsPerMinute: Float,
    eyeLineDp: Float,
    pauseDetectionEnabled: Boolean,
    onWordsPerMinuteChange: (Float) -> Unit,
    onEyeLineChange: (Float) -> Unit,
    onPauseDetectionChange: (Boolean) -> Unit,
    onPickFile: () -> Unit,
    onStart: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Teleprompter", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Text(message)
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
            Text("JSON Dosyası Seç")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Okuma hızı: ${wordsPerMinute.toInt()} kelime/dakika")
                Slider(
                    value = wordsPerMinute,
                    onValueChange = onWordsPerMinuteChange,
                    valueRange = 70f..170f
                )
                Text("Göz hizası: ${eyeLineDp.toInt()} dp")
                Slider(
                    value = eyeLineDp,
                    onValueChange = onEyeLineChange,
                    valueRange = 32f..180f
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Duraksama algılama")
                    Switch(
                        checked = pauseDetectionEnabled,
                        onCheckedChange = onPauseDetectionChange
                    )
                }
                Text(
                    "Not: Duraksama algılama mikrofona ikinci kez eriştiği için bazı telefonlarda sorun çıkarabilir. İlk denemede kapalı bırakmak daha güvenlidir.",
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Kamera sahnesi: ${scenes.size}")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth(), enabled = scenes.isNotEmpty()) {
            Text("Tüm Kamera Sahnelerini Kaydet")
        }
    }
}

fun parseCameraScenesFromJson(context: Context, uri: Uri): Result<List<CameraScene>> {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return Result.failure(IllegalArgumentException("Dosya açılamadı."))
        val reader = BufferedReader(InputStreamReader(inputStream, Charset.forName("UTF-8")))
        val content = reader.use { it.readText() }
        val json = JSONObject(content)
        val scenesArray: JSONArray = json.getJSONArray("scenes")
        val list = mutableListOf<CameraScene>()
        for (i in 0 until scenesArray.length()) {
            val sceneObj = scenesArray.getJSONObject(i)
            val type = sceneObj.optString("type").trim()
            if (type.equals("camera", ignoreCase = true)) {
                val text = sceneObj.optString("voice_text").trim()
                if (text.isNotBlank()) {
                    list.add(
                        CameraScene(
                            originalSceneNumber = sceneObj.optInt("scene_number", i + 1),
                            readOrder = list.size + 1,
                            text = text
                        )
                    )
                }
            }
        }
        Result.success(list)
    } catch (e: Exception) {
        Result.failure(IllegalArgumentException("JSON okunamadı veya beklenen scenes/type/voice_text yapısı yok."))
    }
}

@Composable
fun TeleprompterScreen(
    scenes: List<CameraScene>,
    currentIndex: Int,
    wordsPerMinute: Float,
    eyeLineDp: Float,
    pauseDetectionEnabled: Boolean,
    onNextScene: (Int) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scene = scenes.getOrNull(currentIndex)
    val sceneText = scene?.text.orEmpty()

    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var hasStartedSequence by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(3) }
    var isPaused by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Hazır") }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(previewView) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HD,
                    androidx.camera.video.FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                )
            )
            .build()
        val capture = VideoCapture.withOutput(recorder)
        cameraProvider.unbindAll()
        val lifecycleOwner = context as ComponentActivity
        try {
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                capture
            )
        } catch (_: Exception) {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture
            )
        }
        videoCapture = capture
    }

    LaunchedEffect(isRecording, pauseDetectionEnabled) {
        if (isRecording && pauseDetectionEnabled) {
            monitorSpeechPauses(
                onPauseChanged = { paused -> isPaused = paused }
            ) { isRecording && pauseDetectionEnabled }
        } else {
            isPaused = false
        }
    }

    LaunchedEffect(hasStartedSequence, currentIndex, videoCapture) {
        val capture = videoCapture
        if (!hasStartedSequence || capture == null || scene == null || isRecording) return@LaunchedEffect

        listState.scrollToItem(0)
        isPaused = false
        countdown = 3
        statusText = "Sahne ${scene.readOrder}/${scenes.size} için geri sayım"
        for (i in 3 downTo 1) {
            countdown = i
            delay(1000)
        }
        countdown = 0

        val outputOptions = createOutputOptions(context, "scene_${scene.readOrder}.mp4")
        val recording = capture.output
            .prepareRecording(context, outputOptions)
            .apply {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> statusText = "Kayıt başladı: scene_${scene.readOrder}.mp4"
                    is VideoRecordEvent.Finalize -> {
                        statusText = if (event.hasError()) {
                            "Kayıt hatası: ${event.error}"
                        } else {
                            "Kayıt tamamlandı: scene_${scene.readOrder}.mp4"
                        }
                    }
                }
            }
        activeRecording = recording
        isRecording = true

        val durationMs = calculateDurationMs(sceneText, wordsPerMinute)
        val stepMs = 100L
        val scrollStepPx = 34f
        var elapsed = 0L
        while (elapsed < durationMs) {
            if (!isPaused) {
                listState.animateScrollBy(scrollStepPx)
                elapsed += stepMs
            }
            delay(stepMs)
        }

        activeRecording?.stop()
        activeRecording = null
        isRecording = false
        delay(800)
        onNextScene(currentIndex + 1)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(eyeLineDp.dp))

            Text(
                text = "Sahne ${(scene?.readOrder ?: 0)}/${scenes.size}",
                color = Color.White,
                fontSize = 16.sp
            )
            Text(
                text = statusText,
                color = if (isPaused) Color.Yellow else Color.White,
                fontSize = 14.sp
            )

            if (countdown > 0 && hasStartedSequence) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(countdown.toString(), color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Bold)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    item {
                        Text(
                            text = sceneText,
                            style = TextStyle(color = Color.White, fontSize = 32.sp, lineHeight = 42.sp)
                        )
                    }
                }
            }

            if (!hasStartedSequence) {
                Button(onClick = { hasStartedSequence = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Kayda Başla")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Button(onClick = {
                scope.launch {
                    activeRecording?.stop()
                    activeRecording = null
                    isRecording = false
                    onCancel()
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Durdur ve Çık")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

fun calculateDurationMs(text: String, wordsPerMinute: Float): Long {
    val words = text.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }.size.coerceAtLeast(1)
    return (words / wordsPerMinute.toDouble() * 60_000).toLong().coerceAtLeast(4_000L)
}

fun createOutputOptions(context: Context, fileName: String): MediaStoreOutputOptions {
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TeleprompterApp")
        }
    }
    return MediaStoreOutputOptions.Builder(
        context.contentResolver,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    ).setContentValues(contentValues).build()
}

@SuppressLint("MissingPermission")
suspend fun monitorSpeechPauses(
    onPauseChanged: (Boolean) -> Unit,
    shouldContinue: () -> Boolean
) {
    val sampleRate = 44100
    val minBuffer = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    if (minBuffer <= 0) return

    val bufferSize = minBuffer.coerceAtLeast(4096)
    val audioRecord = try {
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
    } catch (_: Exception) {
        onPauseChanged(false)
        return
    }
    val buffer = ShortArray(bufferSize)
    var silenceDurationMs = 0L
    val silenceThreshold = 1200.0

    try {
        audioRecord.startRecording()
        while (shouldContinue()) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read > 0) {
                var sum = 0.0
                for (i in 0 until read) {
                    val sample = buffer[i].toDouble()
                    sum += sample * sample
                }
                val rms = sqrt(sum / read)
                if (rms < silenceThreshold) {
                    silenceDurationMs += 200
                } else {
                    silenceDurationMs = 0
                }
                onPauseChanged(silenceDurationMs >= 1800)
            }
            delay(200)
        }
    } catch (_: Exception) {
        onPauseChanged(false)
    } finally {
        try {
            audioRecord.stop()
        } catch (_: Exception) {
        }
        audioRecord.release()
    }
}
