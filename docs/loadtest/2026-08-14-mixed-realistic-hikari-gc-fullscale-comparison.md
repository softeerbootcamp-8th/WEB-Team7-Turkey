## 목적

확장된 시나리오(`2026-08-14-mixed-realistic-fullscale-newscenario.md` 참고 — BUSY 라이더가
실제로 배송을 완료시키고, AVAILABLE 라이더가 닫힌 모델로 콜 목록을 두드림, VU 2,100)를
기준으로 Hikari 10 vs 30, G1 vs SerialGC를 비교한다. 이전(구식 시나리오, VU 1700~2000)에서는
Hikari 대기가 거의 0, GC 정지 비율이 1.5% 이하라 어떤 설정을 바꿔도 차이가 안 보였는데, 이번
시나리오는 GC 정지 비율이 4.7~10.1%까지 올라가 **처음으로 실제 신호가 나왔다.**

절차는 웜업(180s) → 재시드 → 측정(180s) 그대로(`.claude/skills/loadtest/SKILL.md` 「원칙 3」),
힙은 `-Xms=-Xmx=512m` 고정, 세 설정 모두 BUSY 800·AVAILABLE 500(VU 2,100)·0.5초 간격.

## 결과 — k6 자체 집계(정본)

| 항목 | G1·Hikari10(기준) | G1·Hikari30 | SerialGC·Hikari10 |
|---|---|---|---|
| testid | `...fullscale-newscenario-...173656` | `...hikari30-fullscale-...175522` | `...serial-fullscale-...180637` |
| p95 | 330.08 ms | 357.74 ms | 332.2 ms |
| p99 | **629.28 ms** | **495.78 ms** | **499.41 ms** |
| avg(성공 응답) | **146.26 ms** | 203.3 ms | 199.35 ms |
| 실패율 | 0.02%(165/678,610) | 0.01%(68/496,867) | 0.00%(32/493,208) |
| checks | 100% | 100% | 100% |

## 앱 리소스(전체 구간)

| 항목 | G1·Hikari10 | G1·Hikari30 | SerialGC·Hikari10 |
|---|---|---|---|
| GC 정지 비율 | **4.7%** | 7.0% | **10.1%**(2배 이상) |
| GC 횟수 | (1단 189) | 1,250 | 1,652 |
| Hikari 대기 최대 | 656 | 998 | 598 |
| 힙 사용 최대 / 상한 | 470/512 | 474/512 | 437/**495**(SerialGC는 ergonomics 상한 자체가 다름) |

## 판정

**GC 알고리즘: 처음으로 SerialGC가 뚜렷이 나쁘다는 신호가 나왔다.** GC 정지 비율이 G1·Hikari10
대비 **2배 이상**(4.7%→10.1%)이다. 다만 지연(p95/p99/실패율)은 아직 G1·Hikari10과 비슷하거나
오히려 나은 수준이라, **정지 비율 상승이 아직 사용자 체감 지연으로 직결되진 않았다** — 부하를
더 올리면(VU 증가 또는 간격 단축) 예전 README의 "SerialGC 붕괴"(정지 비율 51%)처럼 실제로
무너지는 지점이 이 근처 어딘가에 있을 가능성이 높다.

**Hikari 풀 크기: 30이 p99를 확실히 낮췄다**(629ms→496ms, -21%). 대신 GC 정지 비율이
4.7%→7.0%로 올라갔다 — 커넥션을 더 허용하니 동시에 진행되는 DB 작업이 늘어 할당률도 함께
오른 것으로 보인다. avg는 오히려 10 쪽이 낫다(146ms vs 203ms) — **풀 크기 선택은 p99(꼬리
지연)와 평균 중 뭘 우선할지의 트레이드오프**로 보인다. 대기 큐 자체는 여전히 완전히
사라지진 않는다(30에서도 최대 998) — 30보다 더 키우면 어떻게 되는지는 안 재봤다.

## 참고

- SerialGC의 힙 상한이 512가 아니라 **495 MiB**로 나온다 — 컬렉터별 ergonomics 차이(다른
  실험에서도 반복 관찰됨), 실험 설계상 문제는 아니다.
- SerialGC 측정 런은 `--steps`의 유지 구간 탐지가 안 돼(계단 램프가 아니므로 원래도 우연에
  가깝다) 1단 표가 안 나왔다 — 이 리포트의 GC/Hikari 비교는 그래서 전체 구간 기준으로 통일했다.
- 웜업 확인: 세 런 모두 측정 구간 JIT 부하가 15~74ms/s로 낮다(콜드 스타트 수백~수천 ms/s 대비) —
  웜업 성립.

## 원본 데이터

- G1·Hikari10(기준): `docs/loadtest/2026-08-14-mixed-realistic-fullscale-newscenario-20260814-173656-raw.json`
- G1·Hikari30: `docs/loadtest/2026-08-14-mixed-realistic-hikari30-fullscale-20260814-175522-raw.json`
- SerialGC·Hikari10: `docs/loadtest/2026-08-14-mixed-realistic-serial-fullscale-20260814-180637-raw.json`
- 웜업(참고용): `warmup-hikari30-fullscale-20260814-175012`, `warmup-serial-fullscale-20260814-180116`
