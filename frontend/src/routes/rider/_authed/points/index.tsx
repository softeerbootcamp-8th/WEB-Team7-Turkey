import { createFileRoute } from '@tanstack/react-router'
import { ArrowLeft, ChevronDown, ChevronLeft, ChevronRight, Info } from 'lucide-react'

export const Route = createFileRoute('/rider/_authed/points/')({
  component: RiderPoints,
})

/**
 * 라이더 정산/출금 내역(포인트 내역) 화면.
 * Figma(Turkey7_피그마) 라이더 페이지의 "정산 화면" 스크린샷을 기반으로 재구성.
 */
function RiderPoints() {
  return (
    <div className="flex min-h-screen flex-col bg-surface text-on-surface">
      {/* Header */}
      <header className="sticky top-0 z-10 flex h-14 items-center gap-3 bg-surface px-container-margin">
        <button aria-label="뒤로" className="-ml-2 flex h-10 w-10 items-center justify-center rounded-full hover:bg-surface-container">
          <ArrowLeft size={24} />
        </button>
        <h1 className="text-headline-sm font-bold">포인트 내역</h1>
      </header>

      <main className="flex-1">
        {/* 출금 가능 포인트 */}
        <section className="flex items-center justify-between px-container-margin py-lg">
          <div className="flex flex-col gap-sm">
            <span className="text-body-md text-on-surface-variant">출금 가능 포인트</span>
            <div className="flex items-center gap-2">
              <span className="text-headline-lg font-bold">0</span>
              <span className="flex h-5 w-5 items-center justify-center rounded-full bg-primary-container text-[11px] font-bold text-on-primary-container">P</span>
            </div>
          </div>
          {/* TODO: 출금 신청 API 연결 */}
          <button className="rounded-lg bg-tertiary px-4 py-2 text-label-lg font-semibold text-on-tertiary">
            출금 신청
          </button>
        </section>

        {/* 요약 카드 */}
        <section className="px-container-margin">
          <dl className="flex flex-col gap-3 rounded-xl bg-tertiary-container/40 p-md">
            <div className="flex items-center justify-between">
              <dt className="text-body-md text-on-surface">보유 포인트</dt>
              <dd className="text-body-md font-medium text-on-surface">0P</dd>
            </div>
            <div className="flex items-center justify-between">
              <dt className="text-body-md text-on-surface-variant">현재 예상 고용보험료</dt>
              <dd className="text-body-md font-medium text-on-surface-variant">-0P</dd>
            </div>
            <div className="flex items-center justify-between">
              <dt className="text-body-md text-on-surface-variant">현재 예상 산재보험료</dt>
              <dd className="text-body-md font-medium text-on-surface-variant">-0P</dd>
            </div>
          </dl>
        </section>

        {/* 탭 */}
        <nav className="mt-lg flex items-center border-b border-surface-container px-container-margin">
          <button className="flex-1 py-3 text-center text-label-lg font-semibold text-on-surface">충전계좌</button>
          <span className="h-3 w-px bg-surface-container-high" />
          <button className="flex-1 py-3 text-center text-label-lg text-on-surface-variant">출금계좌</button>
          <span className="h-3 w-px bg-surface-container-high" />
          <button className="flex-1 py-3 text-center text-label-lg text-on-surface-variant">가이드</button>
        </nav>

        {/* 기간 선택 */}
        <div className="flex items-center justify-center gap-4 py-md">
          <button aria-label="이전 달" className="flex h-8 w-8 items-center justify-center rounded-full text-on-surface-variant hover:bg-surface-container">
            <ChevronLeft size={20} />
          </button>
          <span className="text-headline-sm font-bold">June 01</span>
          <button aria-label="다음 달" className="flex h-8 w-8 items-center justify-center rounded-full text-on-surface-variant hover:bg-surface-container">
            <ChevronRight size={20} />
          </button>
        </div>

        {/* 집계 + 필터 */}
        <div className="flex items-center justify-between px-container-margin pb-md">
          <span className="text-body-md text-on-surface-variant">총 0건 · 0P</span>
          <button className="flex items-center gap-1 text-body-md text-on-surface-variant">
            전체
            <ChevronDown size={16} />
          </button>
        </div>

        {/* 내역 목록 (빈 상태) */}
        <section className="flex flex-1 flex-col items-center justify-center gap-3 bg-surface-container-low py-20">
          <div className="flex h-12 w-12 items-center justify-center rounded-full bg-surface-dim text-surface-container-lowest">
            <Info size={24} />
          </div>
          <p className="text-body-md text-on-surface-variant">해당 내역이 없습니다.</p>
        </section>
      </main>
    </div>
  )
}
