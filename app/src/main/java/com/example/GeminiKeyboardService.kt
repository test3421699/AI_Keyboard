package com.example

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.*
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GeminiKeyboardService : InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    // --- Compose Service Lifecycle Boilerplate ---
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var settingsManager: SettingsManager

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        settingsManager = SettingsManager(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        serviceJob.cancel()
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    // --- Custom Keyboard Layout ---
    override fun onCreateInputView(): View {
        val composeView = ComposeView(this)
        composeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(this@GeminiKeyboardService)
            setViewTreeViewModelStoreOwner(this@GeminiKeyboardService)
            setViewTreeSavedStateRegistryOwner(this@GeminiKeyboardService)
            setContent {
                MyApplicationTheme {
                    KeyboardScreen(
                        onKeyPress = { handleKeyPress(it) },
                        onSendToGemini = { triggerGeminiAnalysis() },
                        settingsManager = settingsManager
                    )
                }
            }
        }
        return composeView
    }

    // Key Press Handler
    private fun handleKeyPress(key: KeyboardKey) {
        val ic = currentInputConnection ?: return
        playKeyFeedback()

        when (key) {
            is KeyboardKey.Character -> {
                ic.commitText(key.char, 1)
            }
            KeyboardKey.Backspace -> {
                val selected = ic.getSelectedText(0)
                if (selected != null && selected.isNotEmpty()) {
                    ic.commitText("", 1)
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
            }
            KeyboardKey.Space -> {
                ic.commitText(" ", 1)
            }
            KeyboardKey.Enter -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
    }

    // Play Click Sound and Vibration Feedback
    private fun playKeyFeedback() {
        try {
            if (settingsManager.isSoundEnabled) {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
            }
            if (settingsManager.isVibrationEnabled) {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vibratorManager?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                vibrator?.let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        it.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        it.vibrate(15)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Grab context text from surrounding editor space
    private fun grabCurrentText(): String {
        val ic = currentInputConnection ?: return ""
        val selected = ic.getSelectedText(0)
        if (selected != null && selected.isNotEmpty()) {
            return selected.toString()
        }
        val before = ic.getTextBeforeCursor(2000, 0) ?: ""
        val after = ic.getTextAfterCursor(2000, 0) ?: ""
        return (before.toString() + after.toString()).trim()
    }

    // Replace old text completely in input connection
    private fun replaceTextInEditor(newText: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        // Select an extremely wide range to safely replace everything in standard text inputs
        ic.setSelection(0, 100000)
        ic.commitText(newText, 1)
        ic.endBatchEdit()
    }

    // Trigger background process for grammar review & fact audit
    private var isAnalyzing = mutableStateOf(false)
    private var analysisResult = mutableStateOf<GeminiAnalysisResult?>(null)
    private var errorMessage = mutableStateOf<String?>(null)

    private fun triggerGeminiAnalysis() {
        val input = grabCurrentText()
        if (input.isEmpty()) {
            errorMessage.value = "Type some text in the field first!"
            analysisResult.value = null
            return
        }

        val apiKey = settingsManager.getActiveApiKey()
        if (apiKey.isEmpty()) {
            errorMessage.value = "Gemini API key is not configured! Please open the app and set your key."
            analysisResult.value = null
            return
        }

        errorMessage.value = null
        isAnalyzing.value = true
        analysisResult.value = null

        serviceScope.launch {
            try {
                val prompt = """
                    You are an intelligent real-time keyboard helper. 
                    Your job is to:
                    1. Re-write the following text for perfect grammar, spelling, punctuation, clarity, and tone, while strictly preserving the user's core intent.
                    2. Audit and fact-check any historical, scientific, mathematical, or factual assertions made in the text.
                    
                    Text to check: "$input"
                    
                    You MUST return a JSON object with this exact schema:
                    {
                      "correctedText": "<entire corrected paragraph string. If text was flawless and claims are correct, return the exact original text>",
                      "hasCorrections": <true if you made grammar/spelling corrections; otherwise false>,
                      "correctionsExplanation": "<brief 1-sentence note of corrections made, or 'Grammar and spelling look excellent!' if no changes>",
                      "factCheckResult": "<concise summary of any factual audit. If claims are verified, say 'All claims verified.'. If claims are incorrect, state why in 1-2 sentences. If no factual claims exist, say 'No factual claims to audit.'>"
                    }
                    
                    Return ONLY the raw JSON. Do not include markdown wraps (like ```json), outer descriptions, or prefixes.
                """.trimIndent()

                val request = GenerateContentRequest(
                    contents = listOf(Content(parts = listOf(Part(text = prompt)))),
                    generationConfig = GenerationConfig(responseMimeType = "application/json", temperature = 0.2f)
                )

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.service.generateContent(apiKey, request)
                }

                val jsonResponse = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (jsonResponse != null) {
                    val cleanJson = cleanJsonResponse(jsonResponse)
                    val adapter = RetrofitClient.moshi.adapter(GeminiAnalysisResult::class.java)
                    val result = adapter.fromJson(cleanJson)
                    
                    if (result != null) {
                        analysisResult.value = result
                    } else {
                        throw Exception("Failed to parse Gemini response.")
                    }
                } else {
                    throw Exception("No response received from Gemini API.")
                }
            } catch (e: Exception) {
                errorMessage.value = e.message ?: "An unexpected error occurred during analysis."
            } finally {
                isAnalyzing.value = false
            }
        }
    }

    private fun cleanJsonResponse(raw: String): String {
        val startIndex = raw.indexOf("{")
        val endIndex = raw.lastIndexOf("}")
        return if (startIndex in 0 until endIndex) {
            raw.substring(startIndex, endIndex + 1)
        } else {
            raw
        }
    }

    // Custom Compose Interface for the Custom Keyboard
    @Composable
    fun KeyboardScreen(
        onKeyPress: (KeyboardKey) -> Unit,
        onSendToGemini: () -> Unit,
        settingsManager: SettingsManager
    ) {
        var isSymbolsMode by remember { mutableStateOf(false) }
        var isShiftOn by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFEEF0F6)) // High Density Keyboard Background
                .padding(bottom = 8.dp)
        ) {
            // Top Toolbar (Gemini Spark button, quick diagnostics, settings link)
            KeyboardHeader(
                onSparkClick = onSendToGemini,
                isAnalyzing = isAnalyzing.value,
                hasIssueResult = analysisResult.value != null || errorMessage.value != null,
                onCloseResult = {
                    analysisResult.value = null
                    errorMessage.value = null
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
            ) {
                // Main key grid
                if (analysisResult.value == null && errorMessage.value == null && !isAnalyzing.value) {
                    KeysGrid(
                        isSymbolsMode = isSymbolsMode,
                        isShiftOn = isShiftOn,
                        onKeyClicked = { key ->
                            onKeyPress(key)
                            // Auto-reset single characters shift
                            if (isShiftOn && key is KeyboardKey.Character) {
                                isShiftOn = false
                            }
                        },
                        onShiftToggle = { isShiftOn = !isShiftOn },
                        onSymbolsToggle = { isSymbolsMode = !isSymbolsMode }
                    )
                }

                // Assistant card (Slide up when analyzing or results available)
                androidx.compose.animation.AnimatedVisibility(
                    visible = isAnalyzing.value || analysisResult.value != null || errorMessage.value != null,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    AssistantPanel(
                        isAnalyzing = isAnalyzing.value,
                        result = analysisResult.value,
                        error = errorMessage.value,
                        onApplyCorrection = { corrected ->
                            replaceTextInEditor(corrected)
                            analysisResult.value = null
                            errorMessage.value = null
                        },
                        onClose = {
                            analysisResult.value = null
                            errorMessage.value = null
                        }
                    )
                }
            }
        }
    }

    @Composable
    fun KeyboardHeader(
        onSparkClick: () -> Unit,
        isAnalyzing: Boolean,
        hasIssueResult: Boolean,
        onCloseResult: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFEEF0F6)) // High Density Suggestion Bar Background
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Gemini",
                    tint = Color(0xFF005AC1), // Vibrant cobalt brand accent
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Gemini Assistant",
                    color = Color(0xFF1C1B1F), // High contrast dark text
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (hasIssueResult || isAnalyzing) {
                IconButton(
                    onClick = onCloseResult,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = "Show Keys",
                        tint = Color(0xFF1C1B1F),
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                Button(
                    onClick = onSparkClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF005AC1), // Accent cobalt blue
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Check Icon",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Check Grammar & Facts",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    @Composable
    fun AssistantPanel(
        isAnalyzing: Boolean,
        result: GeminiAnalysisResult?,
        error: String?,
        onApplyCorrection: (String) -> Unit,
        onClose: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFEEF0F6))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isAnalyzing) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF005AC1),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Gemini is reviewing your input...",
                        color = Color(0xFF1C1B1F),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Checking grammar & facts",
                        color = Color(0xFF74777F),
                        fontSize = 11.sp
                    )
                }
            } else if (error != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Error",
                        tint = Color(0xFFD32F2F),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = Color(0xFFC62828),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE1E2EC), contentColor = Color(0xFF1C1B1F)),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Back to typing", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (result != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E2EC)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "GEMINI CORRECTED TEXT",
                                    color = Color(0xFF005AC1),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = result.correctedText,
                                    color = Color(0xFF1C1B1F),
                                    fontSize = 13.sp
                                )

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = if (result.hasCorrections) "Changes: ${result.correctionsExplanation}" else "Flawless spelling and grammar!",
                                    color = if (result.hasCorrections) Color(0xFFE65100) else Color(0xFF00897B),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E2EC)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Fact Auditing",
                                        tint = Color(0xFF74777F),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "FACT-CHECK COMPLIANCE REPORT",
                                        color = Color(0xFF44474E),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = result.factCheckResult,
                                    color = Color(0xFF44474E),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onApplyCorrection(result.correctedText) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF005AC1)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Replace Text", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }

                            Button(
                                onClick = onClose,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE1E2EC), contentColor = Color(0xFF1C1B1F)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Dismiss", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    sealed class KeyboardKey {
        data class Character(val char: String) : KeyboardKey()
        object Space : KeyboardKey()
        object Backspace : KeyboardKey()
        object Enter : KeyboardKey()
    }

    @Composable
    fun KeysGrid(
        isSymbolsMode: Boolean,
        isShiftOn: Boolean,
        onKeyClicked: (KeyboardKey) -> Unit,
        onShiftToggle: () -> Unit,
        onSymbolsToggle: () -> Unit
    ) {
        val row1 = if (isSymbolsMode) {
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        } else {
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        }

        val row2 = if (isSymbolsMode) {
            listOf("@", "#", "$", "%", "&", "*", "-", "+", "(", ")")
        } else {
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        }

        val row3 = if (isSymbolsMode) {
            listOf("/", "\\", "=", "<", ">", "?", "!", ",", ".")
        } else {
            listOf("z", "x", "c", "v", "b", "n", "m")
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Row 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row1.forEach { char ->
                    val keyChar = if (isShiftOn && !isSymbolsMode) char.uppercase() else char
                    KeyboardKeyButton(
                        text = keyChar,
                        modifier = Modifier.weight(1f),
                        onClick = { onKeyClicked(KeyboardKey.Character(keyChar)) }
                    )
                }
            }

            // Row 2
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Spacer(modifier = Modifier.weight(0.5f))
                row2.forEach { char ->
                    val keyChar = if (isShiftOn && !isSymbolsMode) char.uppercase() else char
                    KeyboardKeyButton(
                        text = keyChar,
                        modifier = Modifier.weight(1f),
                        onClick = { onKeyClicked(KeyboardKey.Character(keyChar)) }
                    )
                }
                Spacer(modifier = Modifier.weight(0.5f))
            }

            // Row 3
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shift key
                IconButtonKey(
                    icon = if (isShiftOn) Icons.Default.ArrowCircleUp else Icons.Default.ArrowUpward,
                    modifier = Modifier.weight(1.5f),
                    onClick = onShiftToggle,
                    tint = if (isShiftOn) Color(0xFF005AC1) else Color(0xFF1C1B1F),
                    bgColor = if (isShiftOn) Color(0xFFD8E2FF) else Color(0xFFC4C6D0)
                )

                row3.forEach { char ->
                    val keyChar = if (isShiftOn && !isSymbolsMode) char.uppercase() else char
                    KeyboardKeyButton(
                        text = keyChar,
                        modifier = Modifier.weight(1f),
                        onClick = { onKeyClicked(KeyboardKey.Character(keyChar)) }
                    )
                }

                // Backspace
                IconButtonKey(
                    icon = Icons.Default.Backspace,
                    modifier = Modifier.weight(1.5f),
                    onClick = { onKeyClicked(KeyboardKey.Backspace) },
                    bgColor = Color(0xFFC4C6D0),
                    tint = Color(0xFF1C1B1F)
                )
            }

            // Bottom row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Symbols / ABC toggle
                TextButtonKey(
                    text = if (isSymbolsMode) "ABC" else "?123",
                    modifier = Modifier.weight(1.5f),
                    onClick = onSymbolsToggle,
                    bgColor = Color(0xFFC4C6D0),
                    textColor = Color(0xFF1C1B1F)
                )

                // Comma
                KeyboardKeyButton(
                    text = ",",
                    modifier = Modifier.weight(1f),
                    onClick = { onKeyClicked(KeyboardKey.Character(",")) }
                )

                // Space bar
                TextButtonKey(
                    text = "Space",
                    modifier = Modifier.weight(4f),
                    onClick = { onKeyClicked(KeyboardKey.Space) },
                    bgColor = Color.White,
                    textColor = Color(0xFF1C1B1F)
                )

                // Period
                KeyboardKeyButton(
                    text = ".",
                    modifier = Modifier.weight(1f),
                    onClick = { onKeyClicked(KeyboardKey.Character(".")) }
                )

                // Enter Key
                IconButtonKey(
                    icon = Icons.Default.KeyboardReturn,
                    modifier = Modifier.weight(1.5f),
                    onClick = { onKeyClicked(KeyboardKey.Enter) },
                    bgColor = Color(0xFF005AC1),
                    tint = Color.White
                )
            }
        }
    }

    @Composable
    fun KeyboardKeyButton(
        text: String,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Box(
            modifier = modifier
                .shadow(1.dp, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White) // Crisp white background for main characters
                .clickable { onClick() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = Color(0xFF1C1B1F), // Dark high-contrast symbols
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }

    @Composable
    fun IconButtonKey(
        icon: ImageVector,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
        tint: Color = Color(0xFF1C1B1F),
        bgColor: Color = Color(0xFFC4C6D0)
    ) {
        Box(
            modifier = modifier
                .shadow(1.dp, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .background(bgColor)
                .clickable { onClick() }
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "Key Icon",
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    @Composable
    fun TextButtonKey(
        text: String,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
        bgColor: Color = Color(0xFFC4C6D0),
        textColor: Color = Color(0xFF1C1B1F)
    ) {
        Box(
            modifier = modifier
                .shadow(1.dp, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .background(bgColor)
                .clickable { onClick() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
