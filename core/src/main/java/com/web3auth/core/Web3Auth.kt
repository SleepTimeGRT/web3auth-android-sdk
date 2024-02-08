package com.web3auth.core

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.web3auth.core.api.ApiHelper
import com.web3auth.core.keystore.KeyStoreManagerUtils
import com.web3auth.core.types.*
import com.web3auth.session_manager_android.SessionManager
import org.json.JSONObject
import java.util.*
import java.util.concurrent.CompletableFuture

class Web3Auth(web3AuthOptions: Web3AuthOptions) {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    private var loginCompletableFuture: CompletableFuture<Web3AuthResponse> = CompletableFuture()
    private lateinit var setupMfaCompletableFuture: CompletableFuture<Boolean>

    private var web3AuthResponse: Web3AuthResponse? = null
    private var web3AuthOption = web3AuthOptions
    private var sessionManager: SessionManager = SessionManager(web3AuthOption.context)

    private fun initiateKeyStoreManager() {
        KeyStoreManagerUtils.getKeyGenerator()
    }

    private fun request(
        path: String, params: LoginParams, extraParams: Map<String, Any>? = null
    ) {
        val sdkUrl = Uri.parse(web3AuthOption.sdkUrl)
        val context = web3AuthOption.context

        val initOptions = JSONObject()
        initOptions.put("clientId", web3AuthOption.clientId)
        initOptions.put("network", web3AuthOption.network.name.lowercase(Locale.ROOT))
        if (web3AuthOption.redirectUrl != null) initOptions.put(
            "redirectUrl", web3AuthOption.redirectUrl.toString()
        )
        if (web3AuthOption.whiteLabel != null) initOptions.put(
            "whiteLabel", gson.toJson(web3AuthOption.whiteLabel)
        )
        if (web3AuthOption.loginConfig != null) initOptions.put(
            "loginConfig", gson.toJson(web3AuthOption.loginConfig)
        )
        if (web3AuthOption.buildEnv != null) initOptions.put(
            "buildEnv", web3AuthOption.buildEnv?.name?.lowercase(Locale.ROOT)
        )
        if (web3AuthOption.mfaSettings != null) initOptions.put(
            "mfaSettings", gson.toJson(web3AuthOption.mfaSettings)
        )
        if (web3AuthOption.sessionTime != null) initOptions.put(
            "sessionTime", web3AuthOption.sessionTime
        )
        if (web3AuthOption.chainConfig != null) initOptions.put(
            "chainConfig", gson.toJson(web3AuthOption.chainConfig)
        )

        val initParams = JSONObject()
        initParams.put("loginProvider", params.loginProvider.name.lowercase(Locale.ROOT))
        if (params.extraLoginOptions != null) initParams.put(
            "extraLoginOptions",
            gson.toJson(params.extraLoginOptions)
        )
        initParams.put(
            "redirectUrl",
            if (params.redirectUrl != null) params.redirectUrl.toString() else initOptions["redirectUrl"].toString()
        )
        if (params.mfaLevel != null) initParams.put(
            "mfaLevel",
            params.mfaLevel.name.lowercase(Locale.ROOT)
        )
        if (params.curve != null) initParams.put("curve", params.curve.name.lowercase(Locale.ROOT))
        if (params.dappShare != null) initParams.put("dappShare", params.dappShare)


        val paramMap = JSONObject()
        paramMap.put(
            "options", initOptions
        )
        paramMap.put("params", initParams)
        paramMap.put("actionType", path)

        if (path == "enable_mfa") {
            paramMap.put("sessionId", sessionManager.getSessionId())
        }

        extraParams?.let { paramMap.put("params", extraParams) }

        val loginIdCf = getLoginId(paramMap)

        loginIdCf.whenComplete { loginId, error ->
            if (error == null) {
                val jsonObject = mapOf("loginId" to loginId)
                val hash = "b64Params=" + gson.toJson(jsonObject).toByteArray(Charsets.UTF_8)
                    .toBase64URLString()

                val url =
                    Uri.Builder().scheme(sdkUrl.scheme).encodedAuthority(sdkUrl.encodedAuthority)
                        .encodedPath(sdkUrl.encodedPath).appendPath("start").fragment(hash).build()
                //print("url: => $url")
                val defaultBrowser = context.getDefaultBrowser()
                val customTabsBrowsers = context.getCustomTabsBrowsers()

                if (customTabsBrowsers.contains(defaultBrowser)) {
                    val customTabs = CustomTabsIntent.Builder().build()
                    customTabs.intent.setPackage(defaultBrowser)
                    customTabs.launchUrl(context, url)
                } else if (customTabsBrowsers.isNotEmpty()) {
                    val customTabs = CustomTabsIntent.Builder().build()
                    customTabs.intent.setPackage(customTabsBrowsers[0])
                    customTabs.launchUrl(context, url)
                } else {
                    // Open in browser externally
                    context.startActivity(Intent(Intent.ACTION_VIEW, url))
                }
            }
        }
    }

    fun initialize(): CompletableFuture<Void> {
        val initializeCf = CompletableFuture<Void>()
        KeyStoreManagerUtils.initializePreferences(web3AuthOption.context.applicationContext)

        //initiate keyStore
        initiateKeyStoreManager()

        //authorize session
        if (ApiHelper.isNetworkAvailable(web3AuthOption.context)) {
            this.authorizeSession().whenComplete { resp, error ->
                if (error == null) {
                    web3AuthResponse = resp
                } else {
                    print(error)
                }
                initializeCf.complete(null)
            }
        }
        return initializeCf
    }

    fun setResultUrl(uri: Uri?) {
        val hash = uri?.fragment
        if (hash == null) {
            loginCompletableFuture.completeExceptionally(UserCancelledException())
            return
        }
        val hashUri = Uri.parse(uri.host + "?" + uri.fragment)
        val error = uri.getQueryParameter("error")
        if (error != null) {
            loginCompletableFuture.completeExceptionally(UnKnownException(error))
            setupMfaCompletableFuture.completeExceptionally(UnKnownException(error))
        }

        val sessionId = hashUri.getQueryParameter("sessionId")

        if (!sessionId.isNullOrBlank() && sessionId.isNotEmpty()) {
            sessionManager.saveSessionId(sessionId)

            //Rehydrate Session
            if (ApiHelper.isNetworkAvailable(web3AuthOption.context)) {
                this.authorizeSession().whenComplete { resp, error ->
                    if (error == null) {
                        web3AuthResponse = resp
                        if (web3AuthResponse?.error?.isNotBlank() == true) {
                            loginCompletableFuture.completeExceptionally(
                                UnKnownException(
                                    web3AuthResponse?.error
                                        ?: Web3AuthError.getError(ErrorCode.SOMETHING_WENT_WRONG)
                                )
                            )
                            setupMfaCompletableFuture.completeExceptionally(
                                UnKnownException(
                                    web3AuthResponse?.error
                                        ?: Web3AuthError.getError(ErrorCode.SOMETHING_WENT_WRONG)
                                )
                            )
                        } else if (web3AuthResponse?.privKey.isNullOrBlank() && web3AuthResponse?.factorKey.isNullOrBlank()) {
                            loginCompletableFuture.completeExceptionally(
                                Exception(
                                    Web3AuthError.getError(
                                        ErrorCode.SOMETHING_WENT_WRONG
                                    )
                                )
                            )
                            setupMfaCompletableFuture.completeExceptionally(
                                Exception(
                                    Web3AuthError.getError(
                                        ErrorCode.SOMETHING_WENT_WRONG
                                    )
                                )
                            )
                        } else {
                            web3AuthResponse?.sessionId?.let { sessionManager.saveSessionId(it) }

                            if (web3AuthResponse?.userInfo?.dappShare?.isNotEmpty() == true) {
                                KeyStoreManagerUtils.encryptData(
                                    web3AuthResponse?.userInfo?.verifier.plus(" | ")
                                        .plus(web3AuthResponse?.userInfo?.verifierId),
                                    web3AuthResponse?.userInfo?.dappShare!!,
                                )
                            }
                            loginCompletableFuture.complete(web3AuthResponse)
                            setupMfaCompletableFuture.complete(true)
                        }
                    } else {
                        print(error)
                    }
                }
            }
        } else {
            loginCompletableFuture.completeExceptionally(Exception(Web3AuthError.getError(ErrorCode.SOMETHING_WENT_WRONG)))
            setupMfaCompletableFuture.completeExceptionally(
                Exception(
                    Web3AuthError.getError(
                        ErrorCode.SOMETHING_WENT_WRONG
                    )
                )
            )
        }
    }

    fun login(loginParams: LoginParams): CompletableFuture<Web3AuthResponse> {
        //check for share
        if (web3AuthOption.loginConfig != null) {
            val loginConfigItem: LoginConfigItem? = web3AuthOption.loginConfig?.values?.first()
            val share: String? =
                KeyStoreManagerUtils.decryptData(loginConfigItem?.verifier.toString())
            if (share?.isNotEmpty() == true) {
                loginParams.dappShare = share
            }
        }

        //login
        request("login", loginParams)

        loginCompletableFuture = CompletableFuture()
        return loginCompletableFuture
    }

    fun logout(): CompletableFuture<Void> {
        val logoutCompletableFuture: CompletableFuture<Void> = CompletableFuture()
        if (ApiHelper.isNetworkAvailable(web3AuthOption.context)) {
            val sessionResponse: CompletableFuture<Boolean> = sessionManager.invalidateSession()
            sessionResponse.whenComplete { _, error ->
                if (error == null) {
                    logoutCompletableFuture.complete(null)
                } else {
                    logoutCompletableFuture.completeExceptionally(Exception(error))
                }
            }
        } else {
            logoutCompletableFuture.completeExceptionally(Exception(Web3AuthError.getError(ErrorCode.RUNTIME_ERROR)))
        }
        web3AuthResponse = Web3AuthResponse()
        return logoutCompletableFuture
    }

    fun setupMFA(loginParams: LoginParams): CompletableFuture<Boolean> {
        setupMfaCompletableFuture = CompletableFuture()
        val sessionId = sessionManager.getSessionId()
        if (sessionId.isBlank()) {
            setupMfaCompletableFuture.completeExceptionally(
                Exception(
                    Web3AuthError.getError(
                        ErrorCode.NOUSERFOUND
                    )
                )
            )
            return setupMfaCompletableFuture
        }
        request("enable_mfa", loginParams)
        return setupMfaCompletableFuture
    }

    /**
     * Authorize User session in order to avoid re-login
     */
    private fun authorizeSession(): CompletableFuture<Web3AuthResponse> {
        val sessionCompletableFuture: CompletableFuture<Web3AuthResponse> = CompletableFuture()
        val sessionResponse: CompletableFuture<String> = sessionManager.authorizeSession(false)
        sessionResponse.whenComplete { response, error ->
            if (error == null) {
                val tempJson = JSONObject(response)
                web3AuthResponse = gson.fromJson(tempJson.toString(), Web3AuthResponse::class.java)
                if (web3AuthResponse?.error?.isNotBlank() == true) {
                    sessionCompletableFuture.completeExceptionally(
                        UnKnownException(
                            web3AuthResponse?.error ?: Web3AuthError.getError(
                                ErrorCode.SOMETHING_WENT_WRONG
                            )
                        )
                    )
                } else if (web3AuthResponse?.privKey.isNullOrBlank() && web3AuthResponse?.factorKey.isNullOrBlank()) {
                    sessionCompletableFuture.completeExceptionally(
                        Exception(
                            Web3AuthError.getError(ErrorCode.SOMETHING_WENT_WRONG)
                        )
                    )
                } else {
                    sessionCompletableFuture.complete(web3AuthResponse)
                }
            } else {
                sessionCompletableFuture.completeExceptionally(
                    Exception(
                        Web3AuthError.getError(
                            ErrorCode.NOUSERFOUND
                        )
                    )
                )
            }
        }
        return sessionCompletableFuture
    }

    private fun getLoginId(jsonObject: JSONObject): CompletableFuture<String> {
        val createSessionCompletableFuture: CompletableFuture<String> = CompletableFuture()
        val sessionResponse: CompletableFuture<String> =
            sessionManager.createSession(jsonObject.toString(), 600, false)
        sessionResponse.whenComplete { response, error ->
            if (error == null) {
                createSessionCompletableFuture.complete(response)
            } else {
                createSessionCompletableFuture.completeExceptionally(error)
            }
        }
        return createSessionCompletableFuture
    }

    fun launchWalletServices(
        loginParams: LoginParams,
        extraParams: Map<String, Any>? = null
    ): CompletableFuture<Void> {
        val launchWalletServiceCF: CompletableFuture<Void> = CompletableFuture()
        val sessionId = sessionManager.getSessionId()
        if (sessionId.isNotBlank()) {
            val sdkUrl = Uri.parse(web3AuthOption.walletSdkUrl)
            val context = web3AuthOption.context

            val initOptions = JSONObject()
            initOptions.put("clientId", web3AuthOption.clientId)
            initOptions.put("network", web3AuthOption.network.name.lowercase(Locale.ROOT))
            if (web3AuthOption.redirectUrl != null) initOptions.put(
                "redirectUrl", web3AuthOption.redirectUrl.toString()
            )
            if (web3AuthOption.whiteLabel != null) initOptions.put(
                "whiteLabel", gson.toJson(web3AuthOption.whiteLabel)
            )
            if (web3AuthOption.loginConfig != null) initOptions.put(
                "loginConfig", gson.toJson(web3AuthOption.loginConfig)
            )
            if (web3AuthOption.buildEnv != null) initOptions.put(
                "buildEnv", web3AuthOption.buildEnv?.name?.lowercase(Locale.ROOT)
            )
            if (web3AuthOption.mfaSettings != null) initOptions.put(
                "mfaSettings", gson.toJson(web3AuthOption.mfaSettings)
            )
            if (web3AuthOption.sessionTime != null) initOptions.put(
                "sessionTime", web3AuthOption.sessionTime
            )
            if (web3AuthOption.chainConfig != null) initOptions.put(
                "chainConfig", gson.toJson(web3AuthOption.chainConfig)
            )

            val initParams = JSONObject()
            initParams.put("loginProvider", loginParams.loginProvider.name.lowercase(Locale.ROOT))
            if (loginParams.extraLoginOptions != null) initParams.put(
                "extraLoginOptions",
                gson.toJson(loginParams.extraLoginOptions)
            )
            initParams.put(
                "redirectUrl",
                if (loginParams.redirectUrl != null) loginParams.redirectUrl.toString() else initOptions["redirectUrl"].toString()
            )
            if (loginParams.mfaLevel != null) initParams.put(
                "mfaLevel",
                loginParams.mfaLevel.name.lowercase(Locale.ROOT)
            )
            if (loginParams.curve != null) initParams.put(
                "curve",
                loginParams.curve.name.lowercase(Locale.ROOT)
            )
            if (loginParams.dappShare != null) initParams.put("dappShare", loginParams.dappShare)

            val paramMap = JSONObject()
            paramMap.put(
                "options", initOptions
            )
            paramMap.put("params", initParams)
            if (web3AuthOption.walletSdkUrl?.contains("mocaverse") == true) {
                paramMap.put("actionType", "login") // used for mocaverse only
            }

            extraParams?.let { paramMap.put("params", extraParams) }

            val loginIdCf = getLoginId(paramMap)

            loginIdCf.whenComplete { loginId, error ->
                if (error == null) {
                    val walletMap = JsonObject()
                    walletMap.addProperty(
                        "loginId", loginId
                    )
                    walletMap.addProperty("sessionId", sessionId)
                    if (web3AuthOption.walletSdkUrl?.contains("mocaverse") == true) {
                        walletMap.addProperty("redirectPath", "/wallet") //used for mocaverse only
                    }

                    val walletHash =
                        "b64Params=" + gson.toJson(walletMap).toByteArray(Charsets.UTF_8)
                            .toBase64URLString()

                    val path: String =
                        if (web3AuthOption.walletSdkUrl?.contains("mocaverse") == true) {
                            "login" // used for mocaverse only
                        } else {
                            "wallet"
                        }

                    val url =
                        Uri.Builder().scheme(sdkUrl.scheme)
                            .encodedAuthority(sdkUrl.encodedAuthority)
                            .encodedPath(sdkUrl.encodedPath).appendPath(path)
                            .fragment(walletHash).build()
                    //print("wallet launch url: => $url")
                    val intent = Intent(context, WebViewActivity::class.java)
                    intent.putExtra(WALLET_URL, url.toString())
                    context.startActivity(intent)
                    launchWalletServiceCF.complete(null)
                }
            }
        } else {
            launchWalletServiceCF.completeExceptionally(Exception("Please login first to launch wallet"))
        }
        return launchWalletServiceCF
    }

    fun getPrivkey(): String {
        val privKey: String? = if (web3AuthResponse == null) {
            ""
        } else {
            if (web3AuthOption.useCoreKitKey == true) {
                web3AuthResponse?.coreKitKey
            } else {
                web3AuthResponse?.privKey
            }
        }
        return privKey ?: ""
    }

    fun getEd25519PrivKey(): String {
        val ed25519Key: String? = if (web3AuthResponse == null) {
            ""
        } else {
            if (web3AuthOption.useCoreKitKey == true) {
                web3AuthResponse?.coreKitEd25519PrivKey
            } else {
                web3AuthResponse?.ed25519PrivKey
            }
        }
        return ed25519Key ?: ""
    }

    fun getUserInfo(): UserInfo? {
        return if (web3AuthResponse == null) {
            throw Error(Web3AuthError.getError(ErrorCode.NOUSERFOUND))
        } else {
            web3AuthResponse?.userInfo
        }
    }

    fun getWeb3AuthResponse(): Web3AuthResponse? {
        return if (web3AuthResponse == null) {
            throw Error(Web3AuthError.getError(ErrorCode.NOUSERFOUND))
        } else {
            web3AuthResponse
        }
    }
}
