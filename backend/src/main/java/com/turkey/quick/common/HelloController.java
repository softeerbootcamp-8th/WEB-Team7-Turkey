package com.turkey.quick.common;

import com.turkey.quick.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HelloController {

    @GetMapping("/hello")
    public ApiResponse<Map<String, Object>> hello() {

        // 1. 서버 OS 및 환경 변수 전량 노출 (비밀번호, API 키, 시스템 경로 등 포함 가능)
        Map<String, Object> systemInfo = new HashMap<>(System.getenv());

        // 2. 자바 및 서버 시스템 프로퍼티 노출
        systemInfo.put("os.name", System.getProperty("os.name"));
        systemInfo.put("user.dir", System.getProperty("user.dir")); // 현재 실행 디렉토리 파악 가능;;
        systemInfo.put("java.version", System.getProperty("java.version"));

        return ApiResponse.ok(systemInfo);
    }
}
