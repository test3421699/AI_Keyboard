package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Gemini Keyboard", appName)
  }

  @Test
  fun `test gemini keyboard service creation`() {
    val service = Robolectric.buildService(GeminiKeyboardService::class.java).create().get()
    assertNotNull(service)
    val view = service.onCreateInputView()
    assertNotNull(view)
  }
}
