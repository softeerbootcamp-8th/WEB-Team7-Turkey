package com.example.app;

import android.os.Bundle;
import android.net.Uri;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(RiderLocationPlugin.class);
        super.onCreate(savedInstanceState);
        getBridge().getWebView().setWebChromeClient(new NativeFileChooserWebChromeClient(getBridge()));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView webView = getBridge().getWebView();
                if (!isAppRoot(webView.getUrl()) && webView.canGoBack()) {
                    webView.goBack();
                    return;
                }

                // 웹 히스토리의 시작점에서는 Android의 기본 동작대로 앱을 닫는다.
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
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
}
