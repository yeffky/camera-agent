package com.example.cameraagent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AgentApiConfig(
    val provider: String = "openai_compatible",
    val baseUrl: String = "https://api.deepseek.com",
    val chatCompletionsPath: String = "/chat/completions",
    val model: String = "deepseek-flash",
    val temperature: Double = 0.3,
)

class AgentConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val configFile = File(appContext.filesDir, CONFIG_FILE_NAME)
    private val secretPreferences = appContext.getSharedPreferences(SECRET_PREFERENCES, Context.MODE_PRIVATE)

    fun loadConfig(): AgentApiConfig {
        if (!configFile.exists()) {
            appContext.assets.open(EXAMPLE_CONFIG_FILE).use { input ->
                configFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return runCatching {
            val json = JSONObject(configFile.readText())
            val savedBaseUrl = json.optString("base_url", DEFAULT_BASE_URL)
            val savedModel = json.optString("model", DEFAULT_MODEL)
            val isLegacyDefault = savedBaseUrl == LEGACY_DEFAULT_BASE_URL &&
                savedModel == LEGACY_DEFAULT_MODEL
            val config = AgentApiConfig(
                provider = json.optString("provider", "openai_compatible"),
                baseUrl = if (isLegacyDefault) DEFAULT_BASE_URL else savedBaseUrl,
                chatCompletionsPath = json.optString("chat_completions_path", "/chat/completions"),
                model = if (isLegacyDefault) DEFAULT_MODEL else savedModel,
                temperature = json.optDouble("temperature", 0.3).coerceIn(0.0, 2.0),
            )
            if (isLegacyDefault) {
                saveConfig(config)
            }
            config
        }.getOrDefault(AgentApiConfig())
    }

    fun saveConfig(config: AgentApiConfig) {
        val json = JSONObject()
            .put("schema_version", 1)
            .put("provider", config.provider)
            .put("base_url", config.baseUrl.trim())
            .put("chat_completions_path", normalizePath(config.chatCompletionsPath))
            .put("model", config.model.trim())
            .put("temperature", config.temperature.coerceIn(0.0, 2.0))
        val temporaryFile = File(appContext.filesDir, "$CONFIG_FILE_NAME.tmp")
        temporaryFile.writeText(json.toString(2))
        try {
            Files.move(
                temporaryFile.toPath(),
                configFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporaryFile.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun hasApiKey(): Boolean = secretPreferences.contains(ENCRYPTED_API_KEY)

    fun saveApiKey(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(StandardCharsets.UTF_8))
        val encoded = "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(secretPreferences.edit().putString(ENCRYPTED_API_KEY, encoded).commit()) {
            "无法保存加密后的 API Key"
        }
    }

    fun loadApiKey(): String? = runCatching {
        val encoded = secretPreferences.getString(ENCRYPTED_API_KEY, null) ?: return null
        val parts = encoded.split(':', limit = 2)
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }.getOrNull()

    fun clearApiKey() {
        secretPreferences.edit().remove(ENCRYPTED_API_KEY).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim().trimStart('/')
        return "/$trimmed"
    }

    private companion object {
        const val CONFIG_FILE_NAME = "agent_config.json"
        const val EXAMPLE_CONFIG_FILE = "agent_config.example.json"
        const val SECRET_PREFERENCES = "agent_api_secret"
        const val ENCRYPTED_API_KEY = "api_key_aes_gcm"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "camera_agent_api_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-flash"
        const val LEGACY_DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val LEGACY_DEFAULT_MODEL = "gpt-4o-mini"
    }
}
