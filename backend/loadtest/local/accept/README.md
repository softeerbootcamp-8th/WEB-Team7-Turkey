# 배차 수락(accept) 동시성 부하 하네스

`POST /api/rider/requests/{deliveryId}/accept`(조건부 UPDATE / CAS)의 **정합성·동시성·포화**를 재는
하네스. 위치 전달 하네스(`../local`·`../remote`)와 목적이 달라 별도 디렉터리로 둔다.

## 구성

| 파일 | 역할 |
|---|---|
| `com.turkey.quick.loadtest.AcceptLoadSeeder` (백엔드) | `@Profile("loadtest-seed")` 시더. 주문·라이더·**Redis 세션**을 대량 선적재하고 `seed/pairs.json` 생성. 모드: `contention` / `throughput` / `reset` / `clean` |
| `accept-contention.js` | 한 주문에 여러 라이더가 경쟁(shared-iterations). **정합성 게이트**(승자 == 주문 수, 이중 배차 0)를 `handleSummary`가 자동 판정 |
| `victim-under-storm.js` | accept 폭주(constant-arrival-rate) 중 **무관한 요청**(`GET /api/rider/session`)의 지연을 측정 — 폭주가 다른 화면에 주는 영향 |
| `lib/common.js` | 공용 지표(won/lost/error·지연)와 accept 헬퍼 |

## 왜 별도 Java 시더인가

accept는 성공할 때마다 WAITING 주문 1건 + AVAILABLE 라이더 1명을 **소비**하므로, 측정 전에 대량
선적재가 필요하다. 수천 명 로그인 폭주를 피하려 **세션을 Redis에 직접 주입**한다(인터셉터가 그대로
인증 통과). WAITING 주문마다 별도 고객 + ESTIMATE 요금 스냅샷이 필요하다 — 상세 규약은 `AcceptLoadSeeder`
주석 참조. (위치 하네스 #373의 순수 API 시딩과 목적이 달라 공존한다.)

## 실행

배포 사양을 근사한 **제한 컨테이너**(앱 2 vCPU / 1 GB)에서 잰다. 앱·MySQL·Redis를 같은 도커 네트워크에
두어야 한다 — 호스트 게이트웨이(`host.docker.internal`)로 붙이면 네트워크 오버헤드로 절대 수치가 부풀려진다
(상위 `../README.md` 「측정 이력」과 동일 교훈).

```bash
cd backend
# 1) 시드 (경쟁 B=20, 주문 500). 랜덤 포트로 띄워 운영 앱과 충돌 회피, 데이터만 넣고 종료.
./gradlew bootRun --args='--spring.profiles.active=local,loadtest-seed \
  --server.port=0 --management.server.port=0 \
  --seed.mode=contention --seed.orders=500 --seed.ridersPerOrder=20'

# 2) 부하 (앱이 떠 있는 상태)
BASE_URL=http://localhost:8080 k6 run loadtest/local/accept/accept-contention.js
#   피해자-under-폭주: STORM_RATE=300 STORM_DUR=30s k6 run loadtest/local/accept/victim-under-storm.js

# 3) 반복 측정 리셋 / 데이터 정리
./gradlew bootRun --args='--spring.profiles.active=local,loadtest-seed --server.port=0 --management.server.port=0 --seed.mode=reset'
./gradlew bootRun --args='--spring.profiles.active=local,loadtest-seed --server.port=0 --management.server.port=0 --seed.mode=clean'
```

`seed/pairs.json`·`contention-summary.json` 등 실행 산출물은 매 실행 재생성되므로 커밋하지 않는다(`.gitignore`).

## 결과 / 결정

**조건부 UPDATE(CAS) 유지.** 몰림에서도 정합성 유지(이중 배차 0), 패자(경쟁 패배)는 값싸서 자원을
거의 안 쓰고, 경합이 스스로를 악화시키지 않는다. 다른 요청 지연은 몰림이 아니라 **요청 rate가 용량에
근접할 때(포화)** 나타나며 그때 병목은 CPU다.

- 결정 근거(대안 비교 포함): **[ADR‐012: 배차 동시성 조건부 UPDATE(CAS) 유지](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR%E2%80%90012:-배차-동시성-조건부-UPDATE(CAS)-유지-부하테스트-검증)**
- 실측 데이터·재현: **[Discussion #491](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/491)**

> 절대 처리량(rps)은 테스트 rig의 앱↔DB 네트워크 경로에 좌우되므로 신뢰하지 않는다. 정성적 결론(정합성·포화·CPU 병목)만 결정 근거로 쓴다. 절대 용량은 실제 EC2에서 별도 측정이 필요하다.
