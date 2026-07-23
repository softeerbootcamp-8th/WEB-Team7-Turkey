## UI 컴포넌트
- 인터랙티브 UI는 우선 src/components/ui/ 의 shadcn/ui 조합을 사용
- raw <button>, <input> 은 특별한 이유가 없으면 재구현하지 않음

## 디자인 토큰
- src/styles/globals.css 의 @theme 토큰을 우선 사용
- 색상 하드코딩은 가급적 피하고 반복되면 토큰으로 승격

## API
- 기본은 src/api/generated/ 의 Orval 훅 사용
- 예외가 있으면 lib/axios.ts 또는 mutator 레이어에서 처리

## 레이아웃
- 기본은 flex / grid 사용
- position: absolute 는 오버레이/장식 등 이유가 있을 때만 사용
- 간격 조정용 빈 <div> 금지 — gap / padding / margin 사용

## 접근성
- 페이지 루트: <main aria-label="...">
- 에러/상태 변화: role="alert" 또는 aria-live