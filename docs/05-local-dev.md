---
title: 로컬 개발 환경 (DB · Redis)
status: draft
---

# 로컬 개발 환경 (DB · Redis)

로컬 개발 DB는 **Docker 컨테이너의 MySQL 8.4** 를 사용한다. 배포(EC2)와 같은 엔진·버전·문자셋·타임존으로 맞춰,
스키마와 타입 문제를 배포가 아니라 로컬에서 잡는 것이 목적이다.

**Redis 도 같은 컨테이너 구성에 포함된다**(`redis:7.4`). 통합·E2E 테스트도 이 컨테이너에 붙는다
(2026-07-29 변경 — 그 전에는 인메모리 대체를 썼다). 아래 「Redis」 절 참고.

## 왜 H2 를 걷어냈나

이전에는 H2 를 MySQL 호환 모드로 사용했다. 하지만 **호환 모드는 MySQL 이 아니다.**

실제로 발생한 장애: `TINYINT(1)` 컬럼을 MySQL Connector/J 가 기본값(`tinyInt1isBit=true`)에서
`Types.BIT` 으로 보고하는데, 엔티티는 `@JdbcTypeCode(SqlTypes.TINYINT)` 로 매핑돼 있어
`ddl-auto: validate` 가 실패하며 앱이 기동하지 않았다. H2 는 `TINYINT` 을 그대로 보고하므로
로컬에서는 재현되지 않았고, 배포 후에야 드러났다.

마이그레이션도 `BIGINT UNSIGNED`, `ENGINE=InnoDB`, `COLLATE=utf8mb4_0900_ai_ci` 등 MySQL 전용
문법을 쓰고 있어, H2 에서의 성공은 실제 적용 결과를 보장하지 못했다.

## 사전 준비

Docker Desktop 또는 OrbStack 등 컨테이너 런타임을 설치한다. `docker compose` 명령을 사용한다.

## 실행

```bash
cd backend
docker compose up -d          # MySQL 8.4 컨테이너 기동
docker compose ps             # STATUS 가 healthy 가 될 때까지 대기(초기 기동 20~30초)
```

컨테이너가 healthy 해지면 애플리케이션을 `local` 프로파일로 실행한다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

기동 시 Flyway 가 `src/main/resources/db/migration` 의 마이그레이션을 순서대로 적용하고,
JPA 는 `ddl-auto: validate` 로 스키마를 검증만 한다(스키마 생성은 항상 Flyway 담당).

## 접속 정보

`backend/docker-compose.yml` 과 `application-local.yml` 에 정의된 **로컬 컨테이너 전용** 값이다.
배포 접속 정보와 무관하며, 배포 값은 저장소에 두지 않는다.

| 항목 | 값 |
|---|---|
| Host / Port | `localhost:3306` |
| Database | `turkey` |
| User / Password | `turkey` / `local` |
| root Password | `local` |
| 문자셋 / 콜레이션 | `utf8mb4` / `utf8mb4_0900_ai_ci` |
| 타임존 | UTC (`--default-time-zone=+00:00`) |

JDBC URL 에는 `?tinyInt1isBit=false` 가 붙어 있다. 이걸 빼면 위에서 설명한 `BIT` / `TINYINT`
불일치로 기동이 실패한다. 배포 환경의 `DB_URL` 에도 같은 파라미터가 적용돼 있다.

## Redis

`docker compose up -d` 가 MySQL 과 함께 `redis:7.4` 컨테이너(`turkey-redis-local`)도 띄운다.

| 항목 | 값 |
|---|---|
| Host / Port | `localhost:6379` |
| 개발용 로직 DB | `0` (기본값) |
| **테스트용 로직 DB** | `1` (`application-integration.yml`) |

용도는 4가지로 한정된다(`CLAUDE.md` 「확정된 결정」): 세션 저장 / 라이더 최신 위치 / GEO 위치 검색 /
휴대전화 인증번호. 배포는 ElastiCache 를 쓴다.

### 개발 PC 에 Redis 를 직접 설치하지 않는다

**이게 이 절에서 가장 중요하다.** Homebrew 등으로 설치한 Redis 는 `127.0.0.1:6379` 에 바인딩하고
컨테이너는 `*:6379` 에 바인딩하는데, **더 구체적인 호스트 쪽이 이긴다.** 그러면 `localhost:6379` 로
붙는 애플리케이션과 테스트가 컨테이너가 아니라 호스트 인스턴스에 **조용히** 연결된다.

2026-07-29 에 실제로 그 상태로 테스트가 돌고 있었다 — 호스트 8.8.0 vs 컨테이너 7.4.10. H2 로 통과하고
MySQL 에서 깨졌던 것과 같은 종류의 문제다. 통합·E2E 의 `RedisCleaner` 가 연결된 인스턴스의
`redis_version` 을 확인해 이 상황을 테스트 실패로 만든다.

이미 설치돼 있다면 지운다:

```bash
lsof -nP -iTCP:6379 -sTCP:LISTEN     # 누가 잡고 있는지 확인
brew services stop redis && brew uninstall redis
docker compose up -d redis
docker exec turkey-redis-local redis-cli INFO server | grep redis_version   # 7.4.x 여야 한다
```

### 테스트가 개발 데이터에 미치는 영향

- **MySQL**: 개발용과 같은 `turkey` 스키마를 공유하므로 `./gradlew test` 가 **개발 데이터를 지운다**
  (`DatabaseCleaner` 가 매 테스트 전 TRUNCATE).
- **Redis**: 테스트는 로직 DB `1` 을 쓰고 `FLUSHDB` 하므로 **개발용 DB `0` 은 지워지지 않는다.**
  세션이 날아가면 브라우저에서 매번 다시 로그인해야 해서 MySQL 과 다르게 분리했다.
  `RedisCleaner` 는 로직 DB 가 `0` 이면 `FLUSHDB` 를 거부한다.

## DB 초기화 (스키마를 처음부터 다시 만들기)

마이그레이션을 새로 작성했거나 로컬 스키마가 꼬였을 때는 **볼륨째 지우고 다시 띄운다.**
`flyway clean` 이 아니라 이 방법을 쓴다.

```bash
cd backend
docker compose down -v        # -v: 데이터 볼륨까지 삭제
docker compose up -d
```

컨테이너만 재시작하는 경우(`docker compose restart`)에는 데이터가 유지된다.

## CLI 접속

```bash
docker compose exec mysql mysql -uturkey -plocal turkey
```

## 자주 겪는 문제

**포트 3306 충돌** — 이미 로컬에 MySQL 이 떠 있는 경우다. 기존 MySQL 을 끄거나,
`docker-compose.yml` 의 포트 매핑과 `application-local.yml` 의 URL 을 함께 다른 포트로 바꾼다.

**`Communications link failure` / 접속 거부** — 컨테이너는 떴지만 초기화가 끝나지 않은 상태다.
`docker compose ps` 로 `healthy` 를 확인한 뒤 앱을 실행한다.

**Flyway `Validate failed: Migration checksum mismatch`** — 이미 적용된 마이그레이션 파일을 수정한 경우다.
파일을 되돌리고 새 마이그레이션을 추가하는 것이 원칙이다([Flyway 그라운드룰](flyway-ground-rules-short.md) §3).
로컬에서 급히 넘어가야 하면 위의 볼륨 삭제 후 재기동으로 전체를 다시 적용한다.

**`Flyway upgrade recommended: MySQL 8.4 is newer than this version of Flyway`** — 경고이며 기동에는
문제가 없다(V1~V17 정상 적용 확인). Spring Boot 3.4.1 이 물고 오는 Flyway 버전이 MySQL 8.1 까지만
테스트된 탓이다. 배포 환경도 MySQL 8.4 라 동일하게 뜬다. Flyway 버전 상향은 별도로 다룬다.

**앱 기동 시 `Schema-validation: wrong column type`** — 마이그레이션의 컬럼 타입과 엔티티 매핑이
어긋난 경우다. 이제 로컬이 배포와 같은 엔진이므로, **이 에러는 로컬에서 잡고 넘어가야 한다.**

## 범위

단위 테스트는 여전히 DataSource 자동설정을 제외한 채 DB 없이 돈다(`src/test/resources/application.yml`).

통합·E2E 테스트(`integration` 프로파일)는 **이 문서의 컨테이너에 그대로 붙는다.** 개발용과 같은 `turkey`
스키마를 공유하므로, 각 테스트는 `IntegrationTestSupport`(→ `DatabaseCleaner`)로 테이블을 비우고 시작한다.
즉 **`./gradlew test` 를 돌리면 로컬 개발 데이터가 지워진다.** 남겨야 할 데이터가 있으면 먼저 백업한다.
상세 규칙은 `.claude/skills/mvp-feature/references/testing.md` 에 있다.

CI(`.github/workflows/deploy.yml`)는 `./gradlew clean build -x test` 로 테스트를 돌리지 않으므로 영향이 없다.
CI 에서 테스트를 켜려면 워크플로에 MySQL 서비스 컨테이너를 붙이는 작업이 따로 필요하다.
