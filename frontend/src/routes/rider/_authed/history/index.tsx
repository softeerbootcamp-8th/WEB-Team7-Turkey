import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/rider/_authed/history/')({
  component: RiderHistory,
})

function RiderHistory() {
  return (
    <main aria-label="운행 기록" className="bg-background min-h-screen text-on-background pb-[80px]">
      {/* TopAppBar */}
      <header className="bg-surface text-primary font-headline-md text-headline-md sticky w-full top-0 z-10 flex justify-between items-center px-container-margin h-14 transition-all duration-200">
        <button className="text-on-surface-variant hover:bg-surface-container-high rounded-full p-2 transition-all duration-200 active:scale-95 flex items-center justify-center">
          <span className="material-symbols-outlined">arrow_back</span>
        </button>
        <h1 className="font-bold text-on-surface flex-1 text-center">운행 기록</h1>
        {/* Spacer for centering */}
        <div className="w-10"></div>
      </header>
      {/* Main Content Canvas */}
      <div className="px-container-margin py-md flex flex-col gap-md">
        {/* Summary Card */}
        <div className="bg-surface-container-lowest rounded-xl p-md flex justify-between items-center border border-surface-container shadow-[0_4px_12px_rgba(0,0,0,0.08)]">
          <div className="flex flex-col">
            <span className="font-label-sm text-label-sm text-secondary">이번 주 총 수입</span>
            <span className="font-headline-lg text-headline-lg text-on-surface">142,500원</span>
          </div>
          <div className="bg-primary-container text-on-primary-container rounded-full p-3 flex items-center justify-center">
            <span className="material-symbols-outlined text-3xl">account_balance_wallet</span>
          </div>
        </div>
        {/* History List */}
        <div className="flex flex-col gap-gutter">
          {/* List Item 1 */}
          <div className="bg-surface-container-lowest rounded-xl p-md border border-surface-container flex flex-col gap-sm relative overflow-hidden group hover:border-primary-container transition-colors duration-200">
            <div className="flex justify-between items-start w-full">
              <span className="font-label-md text-label-md text-secondary">2023.10.27 (금) 14:30</span>
              <span className="bg-surface-container-high text-primary font-label-sm text-label-sm px-3 py-1 rounded-full">배송 완료</span>
            </div>
            <div className="flex flex-col gap-1 mt-2">
              <div className="flex items-center gap-2">
                <span className="material-symbols-outlined text-secondary text-sm">storefront</span>
                <span className="font-body-md text-body-md text-on-surface truncate">출발: 서울특별시 강남구 테헤란로 152</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="material-symbols-outlined text-secondary text-sm">home</span>
                <span className="font-body-md text-body-md text-on-surface truncate">도착: 서울특별시 서초구 서초대로 396</span>
              </div>
            </div>
            <div className="flex justify-between items-end mt-2 pt-3 border-t border-surface-container">
              <span className="font-label-md text-label-md text-secondary">배달 거리: 2.5km</span>
              <span className="font-headline-sm text-headline-sm text-on-surface font-bold">13,600원</span>
            </div>
            {/* Subtle left border accent on hover */}
            <div className="absolute left-0 top-0 bottom-0 w-1 bg-primary-container opacity-0 group-hover:opacity-100 transition-opacity duration-200"></div>
          </div>
          {/* List Item 2 */}
          <div className="bg-surface-container-lowest rounded-xl p-md border border-surface-container flex flex-col gap-sm relative overflow-hidden group hover:border-primary-container transition-colors duration-200">
            <div className="flex justify-between items-start w-full">
              <span className="font-label-md text-label-md text-secondary">2023.10.27 (금) 12:15</span>
              <span className="bg-surface-container-high text-primary font-label-sm text-label-sm px-3 py-1 rounded-full">배송 완료</span>
            </div>
            <div className="flex flex-col gap-1 mt-2">
              <div className="flex items-center gap-2">
                <span className="material-symbols-outlined text-secondary text-sm">storefront</span>
                <span className="font-body-md text-body-md text-on-surface truncate">출발: 서울특별시 강남구 역삼로 123</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="material-symbols-outlined text-secondary text-sm">home</span>
                <span className="font-body-md text-body-md text-on-surface truncate">도착: 서울특별시 강남구 논현로 456</span>
              </div>
            </div>
            <div className="flex justify-between items-end mt-2 pt-3 border-t border-surface-container">
              <span className="font-label-md text-label-md text-secondary">배달 거리: 1.8km</span>
              <span className="font-headline-sm text-headline-sm text-on-surface font-bold">9,200원</span>
            </div>
            <div className="absolute left-0 top-0 bottom-0 w-1 bg-primary-container opacity-0 group-hover:opacity-100 transition-opacity duration-200"></div>
          </div>
          {/* List Item 3 */}
          <div className="bg-surface-container-lowest rounded-xl p-md border border-surface-container flex flex-col gap-sm relative overflow-hidden group hover:border-primary-container transition-colors duration-200">
            <div className="flex justify-between items-start w-full">
              <span className="font-label-md text-label-md text-secondary">2023.10.26 (목) 19:45</span>
              <span className="bg-surface-container-high text-primary font-label-sm text-label-sm px-3 py-1 rounded-full">배송 완료</span>
            </div>
            <div className="flex flex-col gap-1 mt-2">
              <div className="flex items-center gap-2">
                <span className="material-symbols-outlined text-secondary text-sm">storefront</span>
                <span className="font-body-md text-body-md text-on-surface truncate">출발: 서울특별시 송파구 올림픽로 300</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="material-symbols-outlined text-secondary text-sm">home</span>
                <span className="font-body-md text-body-md text-on-surface truncate">도착: 서울특별시 송파구 백제고분로 250</span>
              </div>
            </div>
            <div className="flex justify-between items-end mt-2 pt-3 border-t border-surface-container">
              <span className="font-label-md text-label-md text-secondary">배달 거리: 3.2km</span>
              <span className="font-headline-sm text-headline-sm text-on-surface font-bold">15,400원</span>
            </div>
            <div className="absolute left-0 top-0 bottom-0 w-1 bg-primary-container opacity-0 group-hover:opacity-100 transition-opacity duration-200"></div>
          </div>
        </div>
        {/* Load More Button */}
        <button className="w-full py-3 mt-4 border border-surface-container rounded-xl text-secondary font-label-lg text-label-lg hover:bg-surface-container-low transition-colors duration-200 flex items-center justify-center gap-2">
          <span>더 보기</span>
          <span className="material-symbols-outlined text-sm">expand_more</span>
        </button>
      </div>
    </main>
  )
}
