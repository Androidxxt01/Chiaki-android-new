// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.regist

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class PsnLoginActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ACCOUNT_ID = "psn_account_id"
        private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
        private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
        private const val REDIRECT = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
        private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
        private const val SCOPES = "psn:clientapp referenceDataService:countryConfig.read pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"
        private const val DUID_PREFIX = "0000000700410080"
    }

    private lateinit var webView: WebView
    private var exchanging = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(com.metallic.chiaki.R.string.psn_login_title)
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.webViewClient = object : WebViewClient() {
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = interceptRedirect(url)

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return interceptRedirect(request?.url?.toString())
            }
        }
        val login = Uri.parse("https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize")
            .buildUpon()
            .appendQueryParameter("service_entity", "urn:service-entity:psn")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("request_locale", "en_US")
            .appendQueryParameter("ui", "pr")
            .appendQueryParameter("layout_type", "popup")
            .appendQueryParameter("smcid", "remoteplay")
            .appendQueryParameter("prompt", "always")
            .appendQueryParameter("PlatformPrivacyWs1", "minimal")
            .appendQueryParameter("duid", DUID_PREFIX + UUID.randomUUID().toString().replace("-", ""))
            .build()
        webView.loadUrl(login.toString())
    }

    private fun interceptRedirect(url: String?): Boolean {
        if (url.isNullOrBlank() || !url.startsWith(REDIRECT)) return false
        val code = Uri.parse(url).getQueryParameter("code")
        if (code.isNullOrBlank()) fail("Authorization code is missing") else exchange(code)
        return true
    }

    private fun exchange(code: String) {
        if (exchanging) return
        exchanging = true
        webView.visibility = WebView.GONE
        lifecycleScope.launch {
            try {
                val account = withContext(Dispatchers.IO) { requestTokensAndAccount(code) }
                PsnAccount.save(this@PsnLoginActivity, account.access, account.refresh, account.encodedId)
                setResult(RESULT_OK, intent.putExtra(EXTRA_ACCOUNT_ID, account.encodedId))
                finish()
            } catch (e: Exception) {
                exchanging = false
                webView.visibility = WebView.VISIBLE
                fail(e.message ?: "Unknown error")
            }
        }
    }

    private data class Account(val access: String, val refresh: String, val encodedId: String)

    private fun requestTokensAndAccount(code: String): Account {
        val form = "grant_type=authorization_code&code=${formEncode(code)}&scope=${formEncode(SCOPES)}&redirect_uri=${formEncode(REDIRECT)}"
        val tokenJson = postForm(TOKEN_URL, form)
        val access = tokenJson.getString("access_token")
        val refresh = tokenJson.getString("refresh_token")
        val accountJson = getJson("$TOKEN_URL/$access")
        val userId = accountJson.get("user_id").toString().toLong()
        val bytes = ByteArray(8) { i -> (userId ushr (i * 8)).toByte() }
        return Account(access, refresh, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
    }

    private fun postForm(url: String, form: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), android.util.Base64.NO_WRAP))
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) error("Token request HTTP ${connection.responseCode}: $body")
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), android.util.Base64.NO_WRAP))
            connection.setRequestProperty("Accept", "application/json")
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) error("Account lookup HTTP ${connection.responseCode}: $body")
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

    private fun formEncode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun fail(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(com.metallic.chiaki.R.string.psn_login_title)
            .setMessage(getString(com.metallic.chiaki.R.string.psn_login_error, message))
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
