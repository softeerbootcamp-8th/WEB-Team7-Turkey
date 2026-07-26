import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/customer/points/')({ component: PointsUse })

function PointsUse() {
  return (
    <>
      {/* TopAppBar Semantic Mapping: Intent -> Transactional (Point Recharge context, but usage screen) */}
      {/* Content logic: Keep it minimal. */}
      <header className="w-full top-0 sticky bg-surface dark:bg-on-surface flex items-center px-container-margin h-14 w-full z-10 flat no shadows">
        <button aria-label="Go back" className="flex items-center justify-center w-10 h-10 -ml-2 text-primary dark:text-primary-fixed hover:opacity-80 transition-opacity active:scale-95 transition-transform">
          <span className="material-symbols-outlined">arrow_back</span>
        </button>
        <h1 className="ml-2 font-headline-md text-headline-md font-bold text-on-surface dark:text-surface">포인트 사용</h1>
      </header>
      <main className="flex-1 px-container-margin pt-lg pb-32">
        {/* Current Points Card */}
        <section className="bg-surface-container-lowest rounded-xl p-md shadow-sm border border-outline-variant/30 mb-lg flex justify-between items-center">
          <div>
            <p className="font-body-md text-body-md text-secondary">보유 포인트</p>
            <p className="font-headline-lg text-headline-lg text-on-surface">500 <span className="font-headline-sm text-headline-sm text-secondary">P</span></p>
          </div>
          <div className="w-12 h-12 bg-primary-container rounded-full flex items-center justify-center">
            <span className="material-symbols-outlined text-on-primary-container">payments</span>
          </div>
        </section>
        {/* Input Area (Insufficient Balance State) */}
        <section className="bg-surface-container-lowest rounded-xl p-md shadow-sm border border-outline-variant/30 mb-lg">
          <label className="block font-label-lg text-label-lg text-on-surface mb-sm" htmlFor="point-input">사용할 포인트 입력</label>
          <div className="relative">
            <input className="w-full h-[52px] px-md rounded-xl border border-error bg-error-container/10 font-body-lg text-body-lg text-on-surface focus:outline-none focus:ring-2 focus:ring-error focus:border-transparent transition-all" disabled id="point-input" placeholder="0" type="number" defaultValue="1500" />
            <span className="absolute right-md top-1/2 -translate-y-1/2 font-body-lg text-body-lg text-secondary">P</span>
          </div>
          {/* Error Message & Chip */}
          <div className="mt-sm flex items-start gap-xs">
            <span className="material-symbols-outlined text-error text-[18px]">error</span>
            <div>
              <p className="font-body-md text-body-md text-error">포인트가 부족합니다. 충전 후 이용해주세요.</p>
              <p className="font-label-sm text-label-sm text-secondary mt-1">부족한 금액: 1,000 P</p>
            </div>
          </div>
        </section>
        {/* Action Button (Recharge) */}
        <section className="mt-xl">
          <button className="w-full h-[48px] bg-primary-container text-on-primary-container font-label-lg text-label-lg rounded-xl flex items-center justify-center gap-sm active:scale-[0.98] transition-transform shadow-sm">
            <span className="material-symbols-outlined">add_circle</span>
            포인트 충전하기
          </button>
          <button className="w-full h-[48px] bg-surface-container-lowest border border-outline-variant text-on-surface font-label-lg text-label-lg rounded-xl flex items-center justify-center mt-sm active:scale-[0.98] transition-transform" disabled>
            결제하기
          </button>
        </section>
      </main>
    </>
  )
}
