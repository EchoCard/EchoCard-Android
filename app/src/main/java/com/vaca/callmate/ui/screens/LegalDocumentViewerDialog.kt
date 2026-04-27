package com.vaca.callmate.ui.screens

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vaca.callmate.data.Language
import com.vaca.callmate.ui.theme.AppBackground
import com.vaca.callmate.ui.theme.AppPrimary
import com.vaca.callmate.ui.theme.AppSpacing
import com.vaca.callmate.ui.theme.AppTextPrimary
import com.vaca.callmate.ui.theme.CallMateTheme

private fun t(zh: String, en: String, lang: Language) = if (lang == Language.Zh) zh else en

enum class LegalDocumentType {
    UserAgreement,
    PrivacyPolicy
}

@Composable
fun LegalDocumentViewerDialog(
    document: LegalDocumentType,
    language: Language,
    onDismiss: () -> Unit
) {
    val (title, assetName) = when (document) {
        LegalDocumentType.UserAgreement ->
            t("用户协议", "User Agreement", language) to "callmate_user_agreement.html"
        LegalDocumentType.PrivacyPolicy ->
            t("隐私协议", "Privacy Policy", language) to "callmate_privacy_policy.html"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = AppBackground
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(52.dp)
                        .padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = title,
                        modifier = Modifier
                            .align(Alignment.Center),
                        fontSize = 17.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = AppTextPrimary
                    )
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Text(t("关闭", "Close", language))
                    }
                }
                HorizontalDivider(color = AppPrimary.copy(alpha = 0.08f))
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(android.graphics.Color.WHITE)
                            settings.javaScriptEnabled = false
                            webViewClient = WebViewClient()
                            loadUrl("file:///android_asset/$assetName")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun LegalDocumentViewerDialogPreview() {
    com.vaca.callmate.ui.preview.PreviewUnavailablePlaceholder("LegalDocumentViewerDialog")
}
