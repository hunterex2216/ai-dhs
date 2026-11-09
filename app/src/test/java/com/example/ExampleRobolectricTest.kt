package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.AgentTtsModule
import com.example.service.BuiltInMusicEngine
import com.example.service.DeviceControlExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Archer AI", appName)
    }

    @Test
    fun `legacy tts is permanently disabled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ttsModule = AgentTtsModule(context)
        assertFalse(ttsModule.state.value.isAvailable)
    }

    @Test
    fun `parseContactAndCleanMessage separates recipient and message cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executor = DeviceControlExecutor(context, BuiltInMusicEngine())
        val (contact, message) = executor.parseContactAndCleanMessage(
            rawRecipient = "",
            rawMessage = "হোয়াটসঅ্যাপে করিম ভাইকে মেসেজ দাও আমি ৫ মিনিটে আসছি"
        )
        assertEquals("করিম ভাই", contact)
        assertEquals("আমি ৫ মিনিটে আসছি", message)
    }
}
