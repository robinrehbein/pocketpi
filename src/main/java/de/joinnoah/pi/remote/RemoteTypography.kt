package de.joinnoah.pi.remote

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

// Samsung's current system family is "sec". Older firmware uses "sec-roboto-light".
// Optional local fonts fall back to Android's default when absent, including on AOSP emulators.
private val samsungFamily =
    FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
            .flatMap { weight ->
                listOf(FontStyle.Normal, FontStyle.Italic).flatMap { style ->
                    listOf("sec", "sec-roboto-light").map { name ->
                        Font(DeviceFontFamilyName(name), weight = weight, style = style)
                    }
                }
            }
    )

internal val RemoteTypography: Typography =
    Typography().let { base ->
        Typography(
            displayLarge = base.displayLarge.copy(fontFamily = samsungFamily),
            displayMedium = base.displayMedium.copy(fontFamily = samsungFamily),
            displaySmall = base.displaySmall.copy(fontFamily = samsungFamily),
            headlineLarge = base.headlineLarge.copy(fontFamily = samsungFamily),
            headlineMedium = base.headlineMedium.copy(fontFamily = samsungFamily),
            headlineSmall = base.headlineSmall.copy(fontFamily = samsungFamily),
            titleLarge = base.titleLarge.copy(fontFamily = samsungFamily),
            titleMedium = base.titleMedium.copy(fontFamily = samsungFamily),
            titleSmall = base.titleSmall.copy(fontFamily = samsungFamily),
            bodyLarge = base.bodyLarge.copy(fontFamily = samsungFamily),
            bodyMedium = base.bodyMedium.copy(fontFamily = samsungFamily),
            bodySmall = base.bodySmall.copy(fontFamily = samsungFamily),
            labelLarge = base.labelLarge.copy(fontFamily = samsungFamily),
            labelMedium = base.labelMedium.copy(fontFamily = samsungFamily),
            labelSmall = base.labelSmall.copy(fontFamily = samsungFamily),
        )
    }
