// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.regist

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal object PsnAccount {
    private const val PREFS = "psn_account_secure"
    private const val ACCESS = "access_token"
    private const val REFRESH = "refresh_token"
    private const val ACCOUNT_ID = "account_id"

    private fun preferences(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun accountId(context: Context): String? = preferences(context).getString(ACCOUNT_ID, null)

    fun save(context: Context, access: String, refresh: String, accountId: String) {
        preferences(context).edit().putString(ACCESS, access).putString(REFRESH, refresh)
            .putString(ACCOUNT_ID, accountId).apply()
    }

    fun clear(context: Context) = preferences(context).edit().clear().apply()
}
