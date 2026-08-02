# 라이더 운행 상태 관리(변경) 작업 기록

- 이슈: [#54](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/54) [RIDE-STATUS-001] 운행 상태 관리
- 브랜치: `feature/54-rider-operating-status-change` (**`feature/53` 위에 스택** — #53 의 PATCH 스텁을 채우기 때문)
- 범위: backend
- 작성일: 2026-07-31

## 무엇을 만들었나

라이더가 콜 받기/운행 종료로 운행 상태를 직접 바꾸는 API 를 구현했다. #53 이 남겨 둔
`changeOperatingStatus` `return null` 스텁을 실제 로직으로 채운 것이다. 이슈 처리 흐름
①세션 ②현재 상태 ③진행 중 배송 ④전이 검증 ⑤갱신 ⑥배차 후보 반영에 대응한다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| PATCH | `/api/rider/operating-status` | 콜 받기(GO_ONLINE)/운행 종료(GO_OFFLINE) | 409(BUSY 직접 변경), 401(미로그인) |

요청 `RiderOperatingStatusUpdateRequest { action }`, 응답 `RiderOperatingStatusResponse`(상태·변경 시각).

### 스키마 변경

해당 없음. `rider_profile.operating_status`/`status_changed_at` 기존 컬럼으로 충분하다.

## 사람이 고른 선택

이번 이슈는 계약이 기존 코드·`CLAUDE.md` 로 대부분 확정되어 별도 게이트 질문 없이 진행했다(사람 확인).
확정 근거는 아래 「스스로 판단한 것」에 남긴다.

## 스스로 판단한 것

- **요청은 목표 상태가 아니라 행위(GO_ONLINE/GO_OFFLINE)로 받는다**: 이슈 입력은
  "AVAILABLE/UNAVAILABLE"이지만, 팀 규칙(상태는 요청 값으로 덮어쓰지 않고 현재 상태 + 수행 행위로
  검증)과 이미 존재하던 `RiderOperatingStatusUpdateRequest`/`RiderOperatingAction`(그 enum 문서가
  행위 기반 이유를 명시)을 따랐다. #53 의 PATCH 스텁도 이 DTO 를 이미 쓰고 있었다.
- **BUSY 는 전이 시도 전에 409 로 거부**: 도메인 `goOnline/goOffline` 은 출발 상태가 아니면
  `IllegalStateException`(핸들러에서 400)을 던지므로, BUSY 판정과 멱등(같은 상태 재요청)은 도메인까지
  보내지 않고 서비스에서 흡수한다. `RiderLogoutService`(#51)의 BUSY→409 패턴과 같다.
- **운행 종료 시 Redis 최신 위치를 delete**: 별도 GEO 후보 스토어가 아직 없어(#83 미구현) 배차 후보
  판정은 "DB 상태 = AVAILABLE"이고, 상태 전이만으로도 후보에서 빠진다. 다만 TTL 이 남은 좌표가 위치
  기반 검색에 잡히는 틈(#81)을 닫기 위해 `RiderLocationStore.delete` 를 추가하고 GO_OFFLINE 에서
  호출했다(`CLAUDE.md` 가 "운행 상태 변경 API 구현 시 함께 추가"라고 예고해 둔 항목).
- **위치 delete 는 트랜잭션 커밋 전에 수행**: `RiderLogoutService` 는 세션 삭제를 커밋 후(컨트롤러)에
  하지만, 여기서는 서비스 안에서 상태 전이 직후에 지운다. 커밋이 실패하면 상태는 AVAILABLE 로 롤백되고
  위치만 없는데, 그 라이더는 다음 위치 전송 전까지 위치 기반 검색에 안 잡히므로 "종료 의도"와 어긋나지
  않는다(컨트롤러를 얇게 유지하는 쪽을 택함). 트레이드오프는 미결 항목에 남긴다.
- **인터페이스 PATCH 에 `operationId=changeRiderOperatingStatus` 명시**(#194 회귀 방지) + 인증 주체
  `AuthenticatedRider` 파라미터 추가. payment 컨벤션대로 매핑·바인딩은 구현체에 둔다(#53 과 동일).

## 일부러 하지 않은 것

- **실제 GEO 배차 후보 스토어 / 검색**: — 후속: #83 [RIDE-LOC-003]. 지금은 상태 전이 + 위치 delete 로
  충분하다.
- **로그아웃 시 GEO 후보 제거 연결**: — 후속: #240. 로그아웃 경로는 이 이슈에서 건드리지 않았다.
- **GO_ONLINE 시 후보 등록**: 등록할 GEO 셋이 없다. 라이더가 온라인 후 위치를 보내기 시작하면
  위치 저장으로 자연히 후보가 된다.
- **프론트 콜 받기/운행 종료 화면**: — 후속: #213 [FE-RIDE-HOME-001].

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `rider/service/RiderOperatingStatusChangeServiceTest` | 전이·멱등·BUSY 409·위치 delete 호출/미호출(Mockito verify) |
| 통합 | `rider/service/RiderOperatingStatusChangeServiceIntegrationTest` | 상태 DB 영속(재조회 유지), BUSY 롤백, 실제 Redis 위치 삭제 |
| E2E | `rider/controller/RiderOperatingStatusChangeE2ETest` | 실제 HTTP: GO_ONLINE/OFFLINE→재조회 유지·위치 제외, BUSY→409, 멱등→200, 쿠키 없음→401 |

실행 결과:

```text
./gradlew test → 신규 14개(단위 5 + 통합 4 + E2E 5) 전체 통과.
전체 스위트의 #78 SSE 팬아웃(2인스턴스) 테스트는 타이밍 간헐 실패(다른 담당, 이 이슈와 무관).
```

- E2E 의 PATCH 는 `TestRestTemplate` 기본 팩토리(JDK `HttpURLConnection`)가 지원하지 않아 JDK 21
  `java.net.http.HttpClient` 로 보냈다(`SseTestClient` 선례, 새 의존성 없음). 로그인·GET 은
  `TestRestTemplate`.

### 검증하지 못한 것

- 실제 Redis TTL 만료 자체는 검증하지 않았다(delete 는 즉시 삭제라 무관). 인메모리·실 Redis 모두에서
  delete 후 `find` 가 빈 결과인 것까지만 확인했다.

## 새로 생긴 미결 사항

- **위치 delete 의 트랜잭션 경계**: 커밋 전 삭제로 두었는데(위 근거), 외부(Redis) 호출을 DB 트랜잭션
  안에 두는 것이라 커넥션을 그동안 잡는다. 부하 테스트에서 문제되면 커밋 후 삭제(`RiderLogoutService`
  방식)로 옮길지 재검토.
- **배차 후보의 정본**: 현재는 "DB 상태 = AVAILABLE + Redis 최신 위치 존재"의 조합으로만 후보가
  결정된다. 실제 GEO 검색(#83)이 붙으면 GO_ONLINE/OFFLINE 이 GEO 셋에 add/remove 해야 하는지
  그때 확정한다.
