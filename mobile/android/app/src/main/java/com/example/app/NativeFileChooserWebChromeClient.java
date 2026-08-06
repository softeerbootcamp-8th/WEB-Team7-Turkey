package com.example.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebChromeClient;
import java.io.File;
import java.io.IOException;

/** Adds the system camera app to image file chooser options. */
public class NativeFileChooserWebChromeClient extends BridgeWebChromeClient {
    private final Bridge bridge;
    private final ActivityResultLauncher<Intent> chooserLauncher;
    private ValueCallback<Uri[]> pendingCallback;
    private Uri pendingCameraUri;

    public NativeFileChooserWebChromeClient(Bridge bridge) {
        super(bridge);
        this.bridge = bridge;
        this.chooserLauncher = bridge.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::handleChooserResult
        );
    }

    @Override
    public boolean onShowFileChooser(
        WebView webView,
        ValueCallback<Uri[]> filePathCallback,
        FileChooserParams fileChooserParams
    ) {
        if (!acceptsImages(fileChooserParams.getAcceptTypes())) {
            return super.onShowFileChooser(webView, filePathCallback, fileChooserParams);
        }

        if (pendingCallback != null) {
            pendingCallback.onReceiveValue(null);
        }

        Intent fileIntent = fileChooserParams.createIntent();
        Intent cameraIntent = createCameraIntent();
        if (cameraIntent == null) {
            return super.onShowFileChooser(webView, filePathCallback, fileChooserParams);
        }

        pendingCallback = filePathCallback;
        Intent chooserIntent = Intent.createChooser(fileIntent, "사진 촬영 또는 선택");
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { cameraIntent });
        chooserLauncher.launch(chooserIntent);
        return true;
    }

    private boolean acceptsImages(String[] acceptTypes) {
        if (acceptTypes == null || acceptTypes.length == 0) {
            return false;
        }
        for (String acceptType : acceptTypes) {
            if (acceptType != null && acceptType.startsWith("image/")) {
                return true;
            }
        }
        return false;
    }

    private Intent createCameraIntent() {
        try {
            File photoFile = File.createTempFile("delivery_photo_", ".jpg", bridge.getActivity().getCacheDir());
            pendingCameraUri = FileProvider.getUriForFile(
                bridge.getActivity(),
                bridge.getContext().getPackageName() + ".fileprovider",
                photoFile
            );

            Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
            cameraIntent.setClipData(ClipData.newRawUri("delivery_photo", pendingCameraUri));
            cameraIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return cameraIntent;
        } catch (IOException | IllegalArgumentException exception) {
            pendingCameraUri = null;
            return null;
        }
    }

    private void handleChooserResult(ActivityResult result) {
        if (pendingCallback == null) {
            return;
        }

        Uri[] selectedUris = null;
        if (result.getResultCode() == Activity.RESULT_OK) {
            Intent resultData = result.getData();
            if (resultData == null) {
                selectedUris = pendingCameraUri == null ? null : new Uri[] { pendingCameraUri };
            } else {
                selectedUris = WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), resultData);
            }
        }

        pendingCallback.onReceiveValue(selectedUris);
        pendingCallback = null;
        pendingCameraUri = null;
    }
}
