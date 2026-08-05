# Orval 생성 클라이언트 stale 정정 (고객 취소 훅) 작업 기록

- 이슈: [#379](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/379)
- 브랜치: `fix/fe/379-orval-cancel-hook-stale` (dev 기준)
- 범위: frontend
- 작성일: 2026-08-05

## 무엇을 만들었나

dev에 커밋돼 있던 Orval 생성 클라이언트가 백엔드 operationId와 어긋난 것을 바로잡았다.
배송 취소 operationId는 `cancelCustomerDelivery`(→ 훅 `useCancelCustomerDelivery`)인데, 커밋된
생성 클라이언트와 고객 취소 화면 2개는 낡은 `useCancelDelivery`를 쓰고 있었다. 둘이 서로만 일치해
빌드는 통과했지만 클라이언트가 실제 백엔드와 불일치했고, 규정된 재생성을 돌리는 순간 화면이 깨졌다.

- dev 백엔드로 `pnpm generate:api` 재생성 → `customer-delivery.ts`의 취소 훅이 `useCancelCustomerDelivery`로 정정.
- 고객 취소 화면 2개(`deliveries/$deliveryId/index.tsx`·`tracking.tsx`)의 import·사용을 정정된 훅명으로 변경.
- `schemas.ts`는 `DeliveryDetailResponse.proofValue`의 JSDoc 설명 1줄이 최신 백엔드 `@Schema`로 갱신됨(문서 드리프트 정정).

### 화면

- `customer/_authed/deliveries/$deliveryId/index.tsx`, `.../tracking.tsx` — `useCancelDelivery` → `useCancelCustomerDelivery`.
  (그 외 로직 변경 없음. 훅 이름만 실제 백엔드에 맞춤.)

### 스키마 변경

해당 없음(백엔드 무변경). 생성물만 실제 스펙에 맞춰 갱신.

## 사람이 고른 선택

해당 없음. 정정 방향이 하나로 확정된 버그 수정이라 게이트 질문 없이 진행(사람이 PR 생성을 지시).

## 스스로 판단한 것

- **dev 기준 브랜치로 격리**: #218(feature/218) 위에서 이미 재생성돼 있었지만, 그 브랜치엔 #71 상세 훅이
  섞여 있어 #379 PR에 관계없는 산출물이 딸려 온다. 그래서 dev 백엔드(상세 없음)로 다시 생성해 **취소
  훅 정정만** 담았다. 재생성 diff가 `customer-delivery.ts`(취소 훅) + `schemas.ts`(proofValue 문서 1줄)로
  좁혀진 것을 확인했다.
- **TS7006(implicit any)은 별도 수정 불필요**: 화면의 `onSuccess/onError` 파라미터 implicit-any 오류는
  `useCancelDelivery` export 부재에서 파생된 연쇄 오류였고, 훅명을 바로잡자 타입 추론이 복구돼 사라졌다.

## 일부러 하지 않은 것

- **프론트 CI 게이트 추가**: 근본 원인(CI가 프론트 typecheck/build 미실행이라 stale 산출물이 조용히 병합)의
  예방책이지만 이 PR 범위 밖이다. #379 이슈 코멘트/논의로 남긴다. — 후속: #379에서 결정.
- **다른 stale 훅 전수 점검**: 이번 재생성 diff가 취소 훅·문서 1줄로 좁아 다른 drift는 없었다. 광범위
  점검은 CI 게이트가 생기면 자동으로 덮인다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| — | (신규 테스트 없음) | 훅명 정정이라 로직 무변경. 기존 스위트로 회귀만 확인 |

실행 결과:

```text
pnpm generate:api → customer-delivery.ts(취소 훅) + schemas.ts(문서 1줄)만 변경
pnpm typecheck    → 통과 (이전 red 였던 고객 취소 화면 해소)
pnpm test         → 19 files, 140 tests passed
pnpm build        → 통과
```

## 새로 생긴 미결 사항

- **프론트 CI 게이트 부재**: 이 버그가 조용히 병합된 근본 원인. `deploy.yml`이 백엔드 `-x test`이고 프론트
  `typecheck`/`build` 게이트가 없어, 백엔드와 어긋난 생성 클라이언트가 걸러지지 않는다. 게이트(최소
  `pnpm typecheck`, 가능하면 재생성 후 `git diff --exit-code`) 도입 여부를 #379에서 논의한다.
