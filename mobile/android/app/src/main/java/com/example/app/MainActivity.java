package com.example.app;

import android.os.Bundle;
import android.net.Uri;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(RiderLocationPlugin.class);
        super.onCreate(savedInstanceState);
        WebView webView = getBridge().getWebView();
        webView.setWebChromeClient(new NativeFileChooserWebChromeClient(getBridge()));
        disableAlgorithmicDarkening(webView);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView webView = getBridge().getWebView();
                webView.evaluateJavascript(
                        "Boolean(window.__turkeyCloseOverlay?.())",
                        result -> {
                            if ("true".equals(result)) {
                                return;
                            }
                            handleWebBack(webView);
                        });
            }
        });
    }

    private void handleWebBack(WebView webView) {
        if (!isAppRoot(webView.getUrl()) && webView.canGoBack()) {
            webView.goBack();
            return;
        }

        // 웹 히스토리의 시작점에서는 Android의 기본 동작대로 앱을 닫는다.
        finish();
    }

    /** 인증 전 기록이 남아 있어도 앱의 최상위 화면에서는 뒤로가기로 종료한다. */
    private boolean isAppRoot(String url) {
        if (url == null) {
            return true;
        }

        String path = Uri.parse(url).getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            return true;
        }

        // 라이더는 운행 중 홈 진입 시 현재 배송 화면으로 replace 된다.
        return path.equals("/customer")
                || path.equals("/customer/")
                || path.equals("/rider")
                || path.equals("/rider/")
                || path.equals("/rider/delivery")
                || path.equals("/rider/delivery/");
    }

    /**
     * 화면은 아직 라이트 팔레트로만 디자인됐다(#531). 이 선언 없이 시스템이 다크모드면
     * Android WebView가 알고리즘적으로 색을 반전·조정해 텍스트 대비가 무너진다 — 실제 다크
     * 테마가 생기기 전까지는 끈다.
     */
    private void disableAlgorithmicDarkening(WebView webView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.getSettings(), false);
        }
    }
}
