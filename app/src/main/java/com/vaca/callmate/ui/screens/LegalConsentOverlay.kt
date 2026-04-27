package com.vaca.callmate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.CallMateTheme

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

private val BodyGray = Color(0xFF4B5563)
private val ScrollSurfaceGray = Color(0xFFF9FAFB)
private val AgreeBlue = Color(0xFF0047FF)
private val DisagreeGray = Color(0xFF9CA3AF)
private const val ANN = "LEGAL_LINK"

/**
 * 首次启动法律同意：与 iOS `LegalAgreementViews.swift` 中 `LegalConsentOverlay` 布局一致
 * — 半透明遮罩、居中白卡片、灰底滚动条款、文内链接、不同意在上 / 同意在下（品牌蓝）。
 */
@Composable
fun LegalConsentOverlay(
    language: Language,
    onConfirm: () -> Unit,
    onExit: () -> Unit,
    onOpenUserAgreement: () -> Unit = {},
    onOpenPrivacyPolicy: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val bodyStyle = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = BodyGray
    )
    val linkStyle = SpanStyle(
        color = AppTextPrimary,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .shadow(
                    elevation = 18.dp,
                    shape = RoundedCornerShape(24.dp),
                    spotColor = Color.Black.copy(alpha = 0.15f),
                    ambientColor = Color.Transparent
                )
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .border(0.5.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = t("欢迎使用 EchoCard", "Welcome to EchoCard", language),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ScrollSurfaceGray)
                        .border(0.5.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        .verticalScroll(scroll)
                        .padding(14.dp)
                ) {
                    Text(
                        text = t("欢迎您使用 EchoCard 服务！", "Welcome to EchoCard!", language),
                        style = bodyStyle
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    ClickableText(
                        text = buildParagraph1(language, linkStyle),
                        style = bodyStyle,
                        onClick = { offset ->
                            handleLegalClick(offset, buildParagraph1(language, linkStyle), onOpenUserAgreement, onOpenPrivacyPolicy)
                        }
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    ClickableText(
                        text = buildParagraph2(language, linkStyle),
                        style = bodyStyle,
                        onClick = { offset ->
                            handleLegalClick(offset, buildParagraph2(language, linkStyle), onOpenUserAgreement, onOpenPrivacyPolicy)
                        }
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    ClickableText(
                        text = buildParagraph3(language, linkStyle),
                        style = bodyStyle,
                        onClick = { offset ->
                            handleLegalClick(offset, buildParagraph3(language, linkStyle), onOpenUserAgreement, onOpenPrivacyPolicy)
                        }
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = t(
                            "如您同意以上内容，请点击「同意」，正式开启服务！",
                            "If you agree, tap \"Agree\" to start using the service!",
                            language
                        ),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppTextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TextButton(
                        onClick = onExit,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = t("不同意", "Disagree", language),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = DisagreeGray
                        )
                    }

                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AgreeBlue),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
                    ) {
                        Text(
                            text = t("同意", "Agree", language),
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun LegalConsentOverlayPreview() {
    CallMateTheme {
        LegalConsentOverlay(
            language = Language.Zh,
            onConfirm = {},
            onExit = {}
        )
    }
}

private fun handleLegalClick(
    offset: Int,
    text: androidx.compose.ui.text.AnnotatedString,
    onUser: () -> Unit,
    onPrivacy: () -> Unit
) {
    text.getStringAnnotations(tag = ANN, start = offset, end = offset)
        .firstOrNull()
        ?.let { ann ->
            when (ann.item) {
                "user" -> onUser()
                "privacy" -> onPrivacy()
            }
        }
}

private fun buildParagraph1(language: Language, linkStyle: SpanStyle) = buildAnnotatedString {
    if (language == Language.Zh) {
        append("为了更好地保障您的个人权益，在正式开启使用服务前，请您审慎阅读")
        pushStringAnnotation(tag = ANN, annotation = "user")
        withStyle(linkStyle) { append("《EchoCard 用户协议》") }
        pop()
        append("、")
        pushStringAnnotation(tag = ANN, annotation = "privacy")
        withStyle(linkStyle) { append("《EchoCard 隐私政策》") }
        pop()
        append("，以便了解我们为您提供的服务内容及形式、使用本服务需遵守的规范，同时了解我们如何收集、使用、存储、保存及保护、对外提供您的个人信息以及您如何向我们行使您的法定权利。")
    } else {
        append("To protect your rights, please carefully read ")
        pushStringAnnotation(tag = ANN, annotation = "user")
        withStyle(linkStyle) { append("EchoCard User Agreement") }
        pop()
        append(", ")
        pushStringAnnotation(tag = ANN, annotation = "privacy")
        withStyle(linkStyle) { append("EchoCard Privacy Policy") }
        pop()
        append(" to understand our service terms and how we handle your personal data.")
    }
}

private fun buildParagraph2(language: Language, linkStyle: SpanStyle) = buildAnnotatedString {
    if (language == Language.Zh) {
        append("对于")
        pushStringAnnotation(tag = ANN, annotation = "user")
        withStyle(linkStyle) { append("《EchoCard 用户协议》") }
        pop()
        append("，您点击同意即代表您已阅读并同意相关内容。")
    } else {
        append("For ")
        pushStringAnnotation(tag = ANN, annotation = "user")
        withStyle(linkStyle) { append("EchoCard User Agreement") }
        pop()
        append(", tapping agree means you have read and accepted the terms.")
    }
}

private fun buildParagraph3(language: Language, linkStyle: SpanStyle) = buildAnnotatedString {
    if (language == Language.Zh) {
        append("对于")
        pushStringAnnotation(tag = ANN, annotation = "privacy")
        withStyle(linkStyle) { append("《EchoCard 隐私政策》") }
        pop()
        append("，您点击同意仅代表您已知悉本服务提供的基本功能，并同意我们收集基本功能所需的必要个人信息，并不代表您已同意我们为提供附加功能收集非必要个人信息。对于非必要的个人信息处理，会在您开启具体附加服务前单独征求您的同意。")
    } else {
        append("For ")
        pushStringAnnotation(tag = ANN, annotation = "privacy")
        withStyle(linkStyle) { append("EchoCard Privacy Policy") }
        pop()
        append(", tapping agree only means you acknowledge the basic functions and agree to necessary data collection. Non-essential data processing will require separate consent before enabling additional features.")
    }
}
