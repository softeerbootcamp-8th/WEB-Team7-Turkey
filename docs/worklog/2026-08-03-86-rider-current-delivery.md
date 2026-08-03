# 진행 중 배송 조회 및 화면 복구 작업 기록

- 이슈: [#86](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/86)
- 브랜치: `feature/86-rider-current-delivery`
- 범위: fullstack
- 작성일: 2026-08-03

## 무엇을 만들었나

라이더의 세션과 DB에 남은 진행 배송을 함께 검증해, 새로고침·재로그인 뒤에도 현재 배송 단계를 복구할 수 있게 했다.
응답의 현재 상태를 다음 가능한 행동으로 변환하고, 프론트는 로컬 미리보기 상태 대신 이 응답을 정본으로 사용한다.
라이더 상태와 진행 주문이 어긋나면 화면을 임의로 복구하지 않고 409로 중단하며 운영 로그를 남긴다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| GET | `/api/rider/deliveries/current` | 현재 라이더의 진행 배송 상세·현재 상태·다음 행동 조회 | 401 미인증, 409 라이더 상태와 배송 정합성 불일치 |

### 화면

- `/rider/delivery`: `useGetCurrentRiderDelivery`로 새로고침 시 진행 배송을 복구한다.
- `ASSIGNED`·`MOVING_TO_PICKUP`·`PICKED_UP`·`DELIVERING`에 따라 안내·목적지·다음 행동을 표시한다.
- 앞의 세 단계는 기존 전이 API에 연결하고, 지도·카카오맵 길안내·배정 후 상세 주소와 연락처를 실제 응답으로 표시한다.

### 스키마 변경

해당 없음.

## 사람이 고른 선택

해당 없음. 기존 `RiderDeliveryApi.getCurrentDelivery` 계약과 이슈 본문에 경로 외 정책이 충분히 확정되어 있어 별도 선택을 요청하지 않았다.

## 스스로 판단한 것

- **현재 배송 조회를 별도 API로 유지**: `operating-status.currentDeliveryId`는 복귀 여부 판정에는 충분하지만 단계·주소·연락처·다음 행동을 제공하지 않으며, 수락 전 상세 API는 WAITING 전용이라 재사용할 수 없다.
- **다음 행동을 응답에서 계산**: 프론트가 상태 전이 규칙을 추측하지 않도록 `RiderDeliveryNextAction`을 서버 계약에 포함했다. 완료는 별도 인증 API이므로 `COMPLETE`로 구분했다.
- **다건을 서비스에서 명시적으로 검사**: DB의 `active_rider_id` UNIQUE 제약으로 정상 데이터에서는 한 건뿐이지만, 이슈의 정합성 오류 조건을 코드와 로그로 드러내기 위해 목록 조회 후 개수를 검사한다.
- **상태 변경도 불일치 시 제한**: 진행 주문이 있는데 BUSY가 아닌 상태에서 GO_ONLINE/GO_OFFLINE으로 덮어 문제를 숨기지 않도록 409로 차단한다.
- **Orval 전체 재생성 결과 유지**: 현재 dev OpenAPI와 생성물이 어긋나 있던 부분도 공식 생성 절차의 결과대로 동기화하고, 변경된 운행 상태 훅 이름을 소비 화면에 반영했다.

## 일부러 하지 않은 것

- **배송 완료 인증 입력 화면**: #86은 현재 단계와 다음 행동을 복구하는 범위이며, 완료 인증자료 수집 UX는 후속 프론트 화면 작업으로 남기고 안내만 표시했다. 백엔드 완료 API 자체는 기존 구현을 유지한다.
- **다건 데이터 통합 테스트**: DB UNIQUE 제약이 두 번째 진행 배송 저장 자체를 차단하므로 정상 마이그레이션 상태에서 만들 수 없다. 서비스 분기와 운영 로그는 방어 코드로 유지한다.
- **기존 시안 컴포넌트 삭제**: 새 복구 화면에서는 사용하지 않지만, 별도 화면 명세 작업과 겹칠 수 있어 이번 이슈에서 대량 삭제하지 않았다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `RiderDeliveryNextActionTest` | 진행 상태별 다음 행동과 비진행 상태 거부 |
| 단위 | `RiderOperatingStatusChangeServiceTest` | 진행 배송·라이더 상태 불일치 시 일반 상태 변경 차단 |
| 통합 | `RiderDeliveryServiceIntegrationTest` | 네 배송 단계 복구, 상세정보 반환, 상태 불일치 409, 진행 배송 없음 |
| E2E | `RiderDeliveryE2ETest` | 재로그인 후 현재 단계 복구, 상세 응답, 쿠키 없는 401, BUSY/배송 없음 409 |
| 프론트 단위 | `-delivery.test.ts` | 상태별 화면·목적지, 표시값, 카카오맵 길안내 URL |

실행 결과:

```text
./gradlew test --tests '*RiderDeliveryNextActionTest' --tests '*RiderOperatingStatusChangeServiceTest' → 성공
./gradlew test --tests '*RiderDeliveryServiceIntegrationTest' --tests '*RiderDeliveryE2ETest' → 성공
전체 ./gradlew test → 실행 권한 미승인으로 미실행
vitest run → 13개 파일, 111개 테스트 성공
tsc -b --noEmit → 성공
vite build → 성공
```

### 검증하지 못한 것

- 브라우저 E2E 러너가 없어 실제 새로고침 시나리오는 백엔드 E2E와 프론트 순수 함수·빌드로 나눠 검증했다.
- 전체 백엔드 테스트는 실행 승인을 받지 못해 이번 이슈 관련 단위·통합·E2E만 실행했다.

## 새로 생긴 미결 사항

해당 없음.
