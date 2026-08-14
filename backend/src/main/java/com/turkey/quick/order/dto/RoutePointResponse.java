package com.turkey.quick.order.dto;

import com.turkey.quick.common.routing.Coordinate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * 경로 좌표 한 점(#532). 이름 붙은 객체로 위도·경도를 실어야 한다 — GeoJSON {@code [경도, 위도]}
 * 배열을 그대로 노출하면 카카오맵과 순서가 반대인데 서울 좌표는 뒤집어도 유효 범위라 예외 없이
 * 조용히 엉뚱한 곳에 그려진다(#423, CLAUDE.md 경로 탐색·ETA).
 */
@Schema(description = "경로 좌표 한 점")
public record RoutePointResponse(

        @Schema(description = "위도", example = "37.4979000")
        BigDecimal latitude,

        @Schema(description = "경도", example = "127.0276000")
        BigDecimal longitude
) {

    public static RoutePointResponse from(Coordinate coordinate) {
        return new RoutePointResponse(coordinate.latitude(), coordinate.longitude());
    }
}
