package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1200dp")
class ProviderAuthUiTest {
    @get:Rule val compose = createComposeRule()

    private val starts = mutableListOf<Triple<String, ProviderAuthMethod, Boolean>>()
    private val logouts = mutableListOf<String>()
    private val answers = mutableListOf<Pair<String, String>>()
    private var cancels = 0
    private var done = 0
    private val opened = mutableListOf<String>()

    private val anthropic =
        AuthProvider(
            "anthropic",
            "Anthropic",
            listOf(ProviderAuthMethod.OAUTH, ProviderAuthMethod.API_KEY),
            ProviderStatus(true, ProviderKeySource.STORED, ProviderAuthMethod.OAUTH),
        )
    private val openrouter =
        AuthProvider("openrouter", "OpenRouter", listOf(ProviderAuthMethod.API_KEY), ProviderStatus(false))
    private val bedrock =
        AuthProvider("amazon-bedrock", "Amazon Bedrock", emptyList(), ProviderStatus(true, ProviderKeySource.CONFIG))
    private val loginId = "TG9naW5JZC0wMTIzNDU2IQ"
    private val promptId = "UHJvbXB0SWQtMDEyMzQ1Ng"

    private fun list(other: ActiveLogin? = null, working: Boolean = false, flow: LoginFlow? = null) =
        ProviderAuthState(
            routeId = "host",
            providers = listOf(anthropic, openrouter, bedrock),
            loaded = true,
            other = other,
            working = working,
            flow = flow,
        )

    private fun render(auth: ProviderAuthState) {
        compose.setContent {
            MaterialTheme {
                ProvidersContent(
                    auth = auth,
                    hostName = "Studio Mac",
                    connected = true,
                    available = true,
                    connection = R.string.remote_connected,
                    onBack = {},
                    onRetry = {},
                    onStart = { id, method, replace -> starts += Triple(id, method, replace) },
                    onLogout = { logouts += it },
                    onAnswer = { prompt, value -> answers += prompt to value },
                    onCancelLogin = { cancels++ },
                    onDismissLogin = { done++ },
                    onDismissNotice = {},
                    onOpenLink = { opened += it; true },
                )
            }
        }
    }

    private fun prompt(type: PromptType, options: List<SelectOption> = emptyList()) =
        PendingPrompt(promptId, AuthPrompt(type, "Paste it", null, options), 1L)

    private fun flow(vararg edit: (LoginFlow) -> LoginFlow) =
        edit.fold(LoginFlow(loginId, "anthropic", ProviderAuthMethod.OAUTH, LoginState.AWAITING_INPUT)) { f, e -> e(f) }

    @Test
    fun listShowsStatusAndActionsPerProvider() {
        render(list())
        compose.onNodeWithTag("providerStatus:anthropic").assertIsDisplayed()
        compose.onNodeWithText("Signed in with an account").assertIsDisplayed()
        compose.onNodeWithText("Not signed in").assertIsDisplayed()
        compose.onNodeWithText("Configured in models.json on the Mac").assertIsDisplayed()
        compose.onNodeWithTag("providerSignOut:anthropic").assertIsDisplayed()
        // A key from models.json can be neither replaced nor removed from the phone.
        compose.onNodeWithTag("providerSignIn:amazon-bedrock").assertDoesNotExist()
        compose.onNodeWithTag("providerSignOut:amazon-bedrock").assertDoesNotExist()
        compose.onNodeWithTag("providerSignOut:openrouter").assertDoesNotExist()
    }

    @Test
    fun aProviderWithOneMethodStartsAtOnce() {
        render(list())
        compose.onNodeWithTag("providerSignIn:openrouter").performClick()
        assertEquals(listOf(Triple("openrouter", ProviderAuthMethod.API_KEY, false)), starts)
    }

    @Test
    fun replacingAStoredLoginAsksFirstAndThenSendsReplace() {
        render(list())
        compose.onNodeWithTag("providerSignIn:anthropic").performClick()
        compose.onNodeWithTag("providerMethod:api_key").performClick()
        assertTrue(starts.isEmpty())
        compose.onNodeWithText("Replace the existing sign-in?").assertIsDisplayed()
        compose.onNodeWithTag("providerReplaceConfirm").performClick()
        assertEquals(listOf(Triple("anthropic", ProviderAuthMethod.API_KEY, true)), starts)
    }

    @Test
    fun signOutNamesTheHostAndTheMissingRevocation() {
        render(list())
        compose.onNodeWithTag("providerSignOut:anthropic").performClick()
        compose.onNodeWithText(
            "This removes the saved sign-in on Studio Mac for all sessions. The token is not revoked at the provider."
        ).assertIsDisplayed()
        assertTrue(logouts.isEmpty())
        compose.onNodeWithTag("providerLogoutConfirm").performClick()
        assertEquals(listOf("anthropic"), logouts)
    }

    @Test
    fun aRunningLoginOfAnotherDeviceDisablesSigningIn() {
        render(list(other = ActiveLogin(loginId, "anthropic", LoginState.RUNNING, false)))
        compose.onNodeWithTag("providerBanner").assertIsDisplayed()
        compose.onNodeWithText("Another device is signing in to Anthropic.").assertIsDisplayed()
        compose.onNodeWithTag("providerSignIn:openrouter").assertIsNotEnabled()
        compose.onNodeWithTag("providerSignOut:anthropic").assertIsNotEnabled()
    }

    @Test
    fun secretPromptHidesTheKeyAndSendsItOnce() {
        render(list(flow = flow({ it.copy(prompt = prompt(PromptType.SECRET)) })))
        compose.onNodeWithTag("loginSend").assertIsNotEnabled()
        compose.onNodeWithTag("loginSecretField").performTextInput(" sk-secret ")
        // The typed text is masked on screen.
        compose.onNodeWithText("sk-secret").assertDoesNotExist()
        compose.onNodeWithTag("loginSend").assertIsEnabled().performClick()
        assertEquals(listOf(promptId to "sk-secret"), answers)
        compose.onNodeWithTag("loginSend").assertIsNotEnabled()
    }

    @Test
    fun manualCodeExplainsTheLocalhostPage() {
        render(
            list(
                flow =
                    flow(
                        { it.copy(prompt = prompt(PromptType.MANUAL_CODE)) },
                        { it.copy(authUrl = AuthEvent.AuthUrl("https://claude.ai/oauth", null)) },
                    )
            )
        )
        compose.onNodeWithText("localhost", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("loginPaste").assertIsDisplayed()
        compose.onNodeWithTag("loginOpenLink").performClick()
        assertEquals(listOf("https://claude.ai/oauth"), opened)
    }

    @Test
    fun selectPromptSendsTheChosenOptionId() {
        val options = listOf(SelectOption("us", "United States", null), SelectOption("eu", "Europe", null))
        render(list(flow = flow({ it.copy(prompt = prompt(PromptType.SELECT, options)) })))
        compose.onNodeWithTag("loginSend").assertIsNotEnabled()
        compose.onNodeWithTag("loginOption:eu").performClick()
        compose.onNodeWithTag("loginSend").assertIsEnabled().performClick()
        assertEquals(listOf(promptId to "eu"), answers)
    }

    @Test
    fun deviceCodeShowsTheCodeLinkAndCountdown() {
        val code = AuthEvent.DeviceCode("ABCD-1234", "https://github.com/login/device", 5, 900)
        render(
            list(
                flow =
                    flow(
                        { it.copy(state = LoginState.RUNNING) },
                        { it.copy(deviceCode = code, deviceCodeAt = System.currentTimeMillis()) },
                    )
            )
        )
        compose.onNodeWithTag("loginCode").assertIsDisplayed()
        compose.onNodeWithText("ABCD-1234").assertIsDisplayed()
        compose.onNodeWithText("Expires in 15:00", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("loginOpenLink").performClick()
        assertEquals(listOf("https://github.com/login/device"), opened)
        compose.onNodeWithTag("loginCancel").performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun successResultCarriesTheModelListCopy() {
        render(list(flow = flow({ it.copy(state = LoginState.SUCCEEDED) })))
        compose.onNodeWithText("Signed in to Anthropic").assertIsDisplayed()
        compose.onNodeWithText(
            "New sessions use this immediately. Sessions that are already open may show an outdated model list until you reopen them."
        ).assertIsDisplayed()
        compose.onNodeWithTag("loginDone").performClick()
        assertEquals(1, done)
    }

    @Test
    fun failureResultShowsTheReasonForTheErrorCode() {
        render(list(flow = flow({ it.copy(state = LoginState.FAILED, error = LoginError.INVALID_CODE) })))
        compose.onNodeWithText("Sign-in failed").assertIsDisplayed()
        compose.onNodeWithText("The pasted address or code was not accepted", substring = true).assertIsDisplayed()
    }

    @Test
    fun aRunningLoginOfThisDeviceCanBeReopenedFromTheList() {
        render(list(flow = flow({ it.copy(state = LoginState.RUNNING) })))
        compose.onNodeWithTag("loginFlow").assertIsDisplayed()
    }
}
