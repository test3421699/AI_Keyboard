package com.example
 
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
 
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {
 
  @get:Rule val composeTestRule = createComposeRule()
 
  @Test
  fun settings_screen_screenshot() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val settingsManager = SettingsManager(context)
    composeTestRule.setContent { 
      MyApplicationTheme { 
        SettingsScreen(settingsManager = settingsManager) 
      } 
    } 
 
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/settings.png")
  }
}
