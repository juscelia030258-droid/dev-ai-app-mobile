package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposeNavigationRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `compose ui navigates across all six core screens`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Servidor API Local (OpenAI)").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_agents").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Coding").assertIsDisplayed()
        composeRule.onNodeWithText("Configurar Parâmetros (Ctx/Temp/Threads)").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("agent_max_tokens_slider_coding").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("agent_top_p_slider_coding").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("agent_top_k_slider_coding").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Salvar Prompt").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Vision").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reasoning").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_models").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Gerenciador de Modelos Locais (ARM64)").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_multiagent").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sistema Multiagente Coordenado").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_api").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Base URL OpenAI-Compatível").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Limites de RAM & Estabilidade").assertIsDisplayed()
        composeRule.onNodeWithTag("auto_unload_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_lazy_column").performScrollToNode(hasTestTag("thermal_guard_switch"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("thermal_guard_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_lazy_column").performScrollToNode(hasTestTag("settings_port_input"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_port_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_port_apply_button").assertIsEnabled()
        composeRule.onNodeWithTag("settings_port_input").performTextReplacement("80")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_port_apply_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("settings_lazy_column").performScrollToNode(hasTestTag("auto_start_server_switch"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("auto_start_server_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_lazy_column").performScrollToNode(hasTestTag("default_max_tokens_slider"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("default_max_tokens_slider").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_lazy_column").performScrollToNode(hasTestTag("reset_defaults_button"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("clear_cache_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("reset_defaults_button").performScrollTo().assertIsDisplayed()
    }
}
