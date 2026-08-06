const SEOUL_TIME_ZONE = 'Asia/Seoul'

// 문자열 끝에 타임존 지정자(`Z`/`z`, `+09:00`, `+0900`)가 있는지 판정한다.
const HAS_ZONE = /([zZ]|[+-]\d{2}:?\d{2})$/

/**
 * 서버가 내려주는 시각 문자열을 Date 로 파싱한다.
 *
 * 백엔드는 모든 시각을 UTC 로 생성하지만(`LocalDateTime.now(ZoneOffset.UTC)`), 직렬화 시
 * 오프셋·`Z` 없이 내보낸다(예: "2026-07-28T02:11:00"). JS 명세상 오프셋 없는 date-time 문자열은
 * 브라우저 로컬 타임존으로 해석되므로, UTC 값이 로컬로 오인돼 한국에서 -9시간으로 표시된다.
 * 그래서 타임존 지정자가 없으면 `Z` 를 붙여 UTC 로 명시 파싱한다. 이미 오프셋/`Z` 가 있으면
 * (테스트 픽스처, 혹은 나중에 백엔드가 오프셋을 붙이게 되면) 그대로 둔다. 파싱 불가면 null.
 */
export function parseServerDate(value: string | null | undefined): Date | null {
  if (!value) return null
  const normalized = HAS_ZONE.test(value) ? value : `${value}Z`
  const date = new Date(normalized)
  return Number.isNaN(date.getTime()) ? null : date
}

/**
 * 서버 시각을 한국 시간(Asia/Seoul)으로 포맷한다.
 *
 * 표시 타임존은 항상 서울로 고정한다 — 뷰어 브라우저의 타임존에 의존하지 않는다(비-KST 환경에서
 * 화면끼리 시각이 달라지는 것을 막는다). `options` 로 표시 항목을 넘기되 `timeZone` 은 강제로
 * 서울이 이긴다. 값이 없거나 파싱 불가면 null 을 반환하니, 대체 문구는 호출부가 정한다.
 */
export function formatSeoul(
  value: string | null | undefined,
  options: Intl.DateTimeFormatOptions,
  locale = 'ko-KR',
): string | null {
  const date = parseServerDate(value)
  if (!date) return null
  return new Intl.DateTimeFormat(locale, { ...options, timeZone: SEOUL_TIME_ZONE }).format(date)
}

/**
 * 서버 시각의 연·월을 한국 시간 기준으로 뽑는다(포인트 내역 월별 분류 등).
 *
 * Date.getFullYear()/getMonth() 는 브라우저 로컬 타임존 기준이라, UTC 값에 그대로 쓰면 월 경계
 * (예: 한국 자정 직후의 거래)에서 엉뚱한 달로 분류된다. `month` 는 Date.getMonth() 와 맞춰 0-based.
 */
export function getSeoulYearMonth(
  value: string | null | undefined,
): { year: number; month: number } | null {
  const date = parseServerDate(value)
  if (!date) return null
  // en-CA 로케일은 "2026-08" 형태를 준다.
  const [year, month] = new Intl.DateTimeFormat('en-CA', {
    timeZone: SEOUL_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
  })
    .format(date)
    .split('-')
    .map(Number)
  return { year, month: month - 1 }
}
