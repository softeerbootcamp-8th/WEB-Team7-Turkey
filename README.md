# WEB-Team7-Turkey
Softeer 8기 7팀 종합 프로젝트


## Commit Convention

커밋 메시지는 변경 목적이 명확하게 드러나도록 Conventional Commits 형식을 사용합니다.

```text
<type>: <변경 내용>
```

### Type

* `feat`: 새로운 기능 추가
* `fix`: 버그 수정
* `refactor`: 기능 변경 없는 코드 구조 개선
* `test`: 테스트 코드 추가 및 수정
* `docs`: 문서 추가 및 수정
* `chore`: 의존성, 빌드, 설정 등 기타 작업
* `ci`: CI/CD 설정 변경

### 작성 규칙

* 커밋 메시지는 한글로 작성합니다.
* 제목 끝에는 마침표를 붙이지 않습니다.
* 하나의 커밋에는 하나의 변경 목적만 포함합니다.
* `수정`, `작업 완료`처럼 모호한 표현은 사용하지 않습니다.
* 관련 GitHub Issue가 있다면 메시지 마지막에 이슈 번호를 작성합니다.

### 예시

```text
feat: 배송 요청 생성 API 구현 #12
feat: SSE 기반 라이더 위치 구독 기능 추가 #15
fix: 이미 배차된 배송을 다시 수락하는 문제 수정 #18
refactor: 배송 상태 검증 로직을 도메인 객체로 분리
test: 라이더 배차 동시성 테스트 추가
docs: ERD 및 API 명세 링크 추가
chore: MySQL 드라이버 의존성 추가
ci: main 브랜치 테스트 워크플로 추가
```
