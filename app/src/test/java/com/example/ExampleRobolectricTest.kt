package com.example

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    assertEquals("Doxon AI", appName)
  }

  @Test
  fun `launch MainActivity successfully`() {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        assertNotNull(activity)
      }
    }
  }

  @Test
  fun `test theme switching state changes in ViewModel`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val viewModel = com.example.ui.viewmodel.DoxonViewModel(application)
    
    // Switch to light mode
    viewModel.setThemeMode("light")
    assertEquals("light", viewModel.themeMode.value)
    
    // Switch to dark mode
    viewModel.setThemeMode("dark")
    assertEquals("dark", viewModel.themeMode.value)
  }

  @Test
  fun `test SecureSyncHelper encryption and decryption with correct credentials`() {
    val plainText = "Confidential chat memories with danishkhan.s.pathan@gmail.com"
    val passphrase = "ultra_secure_access_token_123!"

    // Perform AES-GCM-256 authenticated encryption
    val encryptedPack = com.example.data.sync.SecureSyncHelper.encrypt(plainText, passphrase)
    assertNotNull(encryptedPack)
    org.junit.Assert.assertTrue(encryptedPack.contains(":"))

    // Decrypt back with identical credentials
    val decryptedText = com.example.data.sync.SecureSyncHelper.decrypt(encryptedPack, passphrase)
    assertEquals(plainText, decryptedText)
  }

  @Test(expected = Exception::class)
  fun `test SecureSyncHelper decryption failure with invalid credentials`() {
    val plainText = "Confidential chat"
    val passphrase = "correct_password"
    val wrongPassphrase = "hacked_password"

    val encryptedPack = com.example.data.sync.SecureSyncHelper.encrypt(plainText, passphrase)
    // Decryption with wrong password must crash or fail with cryptographic integrity exception
    com.example.data.sync.SecureSyncHelper.decrypt(encryptedPack, wrongPassphrase)
  }

  @Test
  fun `test CloudSyncManager local simulation password hashing`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val db = com.example.data.database.DoxonDatabase.getDatabase(application)
    val syncManager = com.example.data.sync.CloudSyncManager(application, db.doxonDao())

    val password = "my_private_security_passphrase"
    val hash1 = syncManager.hashPassword(password)
    val hash2 = syncManager.hashPassword(password)

    // Hash must be fully deterministic on identical strings
    assertEquals(hash1, hash2)
    org.junit.Assert.assertNotEquals(password, hash1) // Never store plain password
  }

  @Test
  fun `test smart offline response engine resolvers`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val db = com.example.data.database.DoxonDatabase.getDatabase(application)
    val chatRepo = com.example.data.repository.ChatRepository(application, db.doxonDao())
    
    // 1. Math solving
    val mMethod = chatRepo.javaClass.getDeclaredMethod("trySolveMath", String::class.java)
    mMethod.isAccessible = true
    val mathResult = mMethod.invoke(chatRepo, "solve 15 * 8") as? String
    assertNotNull(mathResult)
    org.junit.Assert.assertTrue(mathResult!!.contains("120"))

    // 2. Clock solving
    val cMethod = chatRepo.javaClass.getDeclaredMethod("trySolveClock", String::class.java)
    cMethod.isAccessible = true
    val clockResult = cMethod.invoke(chatRepo, "what is the current time?") as? String
    assertNotNull(clockResult)
    org.junit.Assert.assertTrue(clockResult!!.contains("Doxon Local Clock Module"))

    // 3. General Subject / Query fallback analyzing
    val gMethod = chatRepo.javaClass.getDeclaredMethod("trySolveGeneralSubject", String::class.java)
    gMethod.isAccessible = true
    val fallbackResult = gMethod.invoke(chatRepo, "explain theoretical physics and atomic gravity") as? String
    assertNotNull(fallbackResult)
    org.junit.Assert.assertTrue(fallbackResult!!.contains("Theoretical Physics"))
    org.junit.Assert.assertTrue(fallbackResult.contains("Gemini API Key"))
  }

  @Test
  fun `test custom AI profile settings and preferences in ViewModel`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val viewModel = com.example.ui.viewmodel.DoxonViewModel(application)
    
    // Set response length preference
    viewModel.setAiResponseLength("concise")
    assertEquals("concise", viewModel.aiResponseLength.value)
    
    // Set creativity option
    viewModel.setAiCreativity("creative")
    assertEquals("creative", viewModel.aiCreativity.value)
    
    // Set custom system prompt instructions
    val prompt = "Act as an expert computer security agent."
    viewModel.setCustomAiPersona(prompt)
    assertEquals(prompt, viewModel.customAiPersona.value)
  }
}
