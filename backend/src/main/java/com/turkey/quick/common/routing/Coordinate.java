package com.turkey.quick.common.routing;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 라우팅 입출력에 쓰는 좌표. <b>항상 (위도, 경도) 순서다.</b>
 *
 * <p>순서를 타입으로 고정하는 것이 이 record 의 존재 이유다. 라우팅 API 는 요청도 응답도
 * {@code [경도, 위도]} 순인데(OSRM 의 GeoJSON 도, 카카오의 {@code vertexes} 도 그렇다), 서울 좌표는
 * (37.5, 127.0) 이라 뒤집어도 <b>둘 다 유효 범위에 들어가</b> 예외가 나지 않는다 — 지도에 엉뚱한
 * 위치가 조용히 찍힐 뿐이다(#423 「알려진 리스크」). 그래서 뒤집는 코드는
 * {@link KakaoMobilityRoutingClient} 안에만 두고, 그 밖의 코드는 이 타입만 주고받는다.
 *
 * <p>{@code double} 이 아니라 {@code BigDecimal} 인 이유는 이 값의 출처가 전부 BigDecimal 이기
 * 때문이다 — {@code delivery_order} 의 픽업·도착 좌표가 {@code DECIMAL(10,7)} 이고
 * {@code location/dto/LocationPayload} 도 BigDecimal 이다. 여기서 double 로 받으면 호출자마다
 * 변환이 흩어진다.
 */
public record Coordinate(BigDecimal latitude, BigDecimal longitude) {

    public Coordinate {
        Objects.requireNonNull(latitude, "latitude");
        Objects.requireNonNull(longitude, "longitude");
    }

    /**
     * 라우팅 API 의 좌표 표기({@code {경도},{위도}}). 카카오 {@code origin}/{@code destination}
     * 파라미터가 이 형식이다(OSRM 의 경로 세그먼트도 같았다).
     *
     * <p>{@code toPlainString()} 을 쓰는 이유는 {@code BigDecimal.toString()} 이 스케일에 따라
     * 지수 표기({@code 1.27E+2})를 낼 수 있고, 그 문자열은 라우팅 서버가 파싱하지 못하기 때문이다.
     */
    String toLongitudeLatitudeParam() {
        return longitude.toPlainString() + "," + latitude.toPlainString();
    }
}
