package com.web3auth.core.types

import android.net.Uri

data class LoginParams(
    val loginProvider: Provider,
    var dappShare: String? = null,
    val extraLoginOptions: ExtraLoginOptions? = null,
    @Transient var redirectUrl: Uri? = null,
    val appState: String? = null,
    val mfaLevel: MFALevel? = null,
    val curve: Curve? = Curve.SECP256K1,
    val dappUrl: String? = null,
)