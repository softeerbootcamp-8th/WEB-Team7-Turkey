package com.example.app;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
        name = "RiderLocation",
        permissions = @Permission(
                alias = RiderLocationPlugin.LOCATION_PERMISSION,
                strings = {
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                }))
public class RiderLocationPlugin extends Plugin {

    static final String LOCATION_PERMISSION = "location";

    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState(LOCATION_PERMISSION) != PermissionState.GRANTED) {
            requestPermissionForAlias(LOCATION_PERMISSION, call, "locationPermissionCallback");
            return;
        }
        startService(call);
    }

    @PermissionCallback
    private void locationPermissionCallback(PluginCall call) {
        if (getPermissionState(LOCATION_PERMISSION) != PermissionState.GRANTED) {
            call.reject("위치 권한이 필요합니다.");
            return;
        }
        startService(call);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent intent = new Intent(getContext(), RiderLocationService.class)
                .setAction(RiderLocationService.ACTION_STOP);
        getContext().startService(intent);
        call.resolve();
    }

    /**
     * BUSY만 시작을 허용한다. AVAILABLE 상태의 위치 전송 진입점은 아직 없다(#338 방향 —
     * 서버가 더 이상 AVAILABLE 라이더 위치를 필요로 하지 않는 쪽으로 정리 중이라 별도 설계가
     * 필요하다). 여기서 조용히 통과시키지 않고 거부해, 그 설계가 나올 때까지 이 메서드가
     * "AVAILABLE도 지원한다"는 착각을 주지 않게 한다.
     */
    private void startService(PluginCall call) {
        String operatingStatus = call.getString("operatingStatus");
        String apiBaseUrl = call.getString("apiBaseUrl");

        if (!"BUSY".equals(operatingStatus)) {
            call.reject("지원하지 않는 운행 상태입니다.");
            return;
        }
        if (apiBaseUrl == null || !(apiBaseUrl.startsWith("http://") || apiBaseUrl.startsWith("https://"))) {
            call.reject("올바른 API 주소가 필요합니다.");
            return;
        }

        Intent intent = new Intent(getContext(), RiderLocationService.class)
                .setAction(RiderLocationService.ACTION_START)
                .putExtra(RiderLocationService.EXTRA_OPERATING_STATUS, operatingStatus)
                .putExtra(RiderLocationService.EXTRA_API_BASE_URL, apiBaseUrl);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }
        call.resolve();
    }
}
