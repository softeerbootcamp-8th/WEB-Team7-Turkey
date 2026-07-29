import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/rider/_authed/history/$deliveryId/')({
  component: RiderHistoryDetail,
})

function RiderHistoryDetail() {
  return (
    <div className="bg-background text-on-background font-body-md min-h-screen pb-xl">
      {/* TopAppBar */}
      <header className="bg-surface dark:bg-surface-dim docked full-width top-0 sticky z-50 transition-all duration-200">
        <div className="flex justify-between items-center w-full px-container-margin h-14 border-b border-surface-variant">
          <button className="text-primary dark:text-primary-fixed hover:bg-surface-container-high dark:hover:bg-surface-container-highest p-2 rounded-full transition-all duration-200 active:scale-95 flex items-center justify-center">
            <span className="material-symbols-outlined">arrow_back</span>
          </button>
          <h1 className="font-headline-md text-headline-md font-bold text-on-surface dark:text-on-surface-variant">운행 상세 정보</h1>
          <div className="w-10"></div> {/* Spacer for centering */}
        </div>
      </header>
      <main aria-label="운행 상세 정보" className="px-container-margin py-md flex flex-col gap-md max-w-2xl mx-auto">
        {/* Section 1: Delivery Status */}
        <section className="bg-surface-container-lowest rounded-xl p-md shadow-[0_4px_12px_rgba(0,0,0,0.08)] flex justify-between items-center">
          <div>
            <p className="font-label-sm text-label-sm text-secondary mb-xs">2023.10.25 14:30</p>
            <div className="flex items-center gap-sm">
              <span className="bg-[#EBF3FF] text-[#2D7FF9] px-3 py-1 rounded-full font-label-md text-label-md">정산 완료</span>
              <h2 className="font-headline-sm text-headline-sm text-on-surface">ORD-20231025-001</h2>
            </div>
          </div>
          <button className="bg-surface text-[#2D7FF9] border border-surface-variant px-4 py-2 rounded-lg font-label-lg text-label-lg transition-all active:scale-95">영수증 보기</button>
        </section>
        {/* Section 2: Route Information */}
        <section className="bg-surface-container-lowest rounded-xl p-md shadow-[0_4px_12px_rgba(0,0,0,0.08)]">
          <h3 className="font-label-lg text-label-lg text-secondary mb-md">경로 정보</h3>
          <div className="flex flex-col gap-4 relative">
            {/* Vertical Line Connector */}
            <div className="absolute left-3 top-6 bottom-6 w-[2px] bg-surface-variant z-0"></div>
            {/* From */}
            <div className="flex items-start gap-md relative z-10">
              <div className="w-6 h-6 rounded-full bg-surface-variant flex items-center justify-center mt-0.5 border-2 border-surface-container-lowest">
                <span className="w-2 h-2 rounded-full bg-secondary"></span>
              </div>
              <div className="flex-1">
                <p className="font-label-sm text-label-sm text-secondary mb-xs">출발지</p>
                <p className="font-body-lg text-body-lg font-semibold text-on-surface">서울시 강남구 테헤란로 152</p>
                <p className="font-body-md text-body-md text-secondary mt-1">강남파이낸스센터 1층 로비</p>
              </div>
            </div>
            {/* To */}
            <div className="flex items-start gap-md relative z-10">
              <div className="w-6 h-6 rounded-full bg-primary-container flex items-center justify-center mt-0.5 border-2 border-surface-container-lowest">
                <span className="w-2 h-2 rounded-full bg-[#191919]"></span>
              </div>
              <div className="flex-1">
                <p className="font-label-sm text-label-sm text-secondary mb-xs">도착지</p>
                <p className="font-body-lg text-body-lg font-semibold text-on-surface">서울시 서초구 서초대로 398</p>
                <p className="font-body-md text-body-md text-secondary mt-1">플래티넘타워 5층</p>
              </div>
            </div>
          </div>
          <div className="mt-md w-full h-32 rounded-lg overflow-hidden bg-surface-container-high relative">
            {/* TODO: 지도/이미지 연결 */}
          </div>
        </section>
        {/* Section 3: Item Information */}
        <section className="bg-surface-container-lowest rounded-xl p-md shadow-[0_4px_12px_rgba(0,0,0,0.08)]">
          <h3 className="font-label-lg text-label-lg text-secondary mb-md">물품 정보</h3>
          <div className="flex items-center gap-md bg-surface-container-low p-sm rounded-lg border border-surface-variant">
            <div className="w-12 h-12 bg-surface rounded-lg flex items-center justify-center text-secondary shadow-sm">
              <span className="material-symbols-outlined" style={{ fontSize: '24px' }}>inventory_2</span>
            </div>
            <div>
              <p className="font-body-lg text-body-lg font-semibold text-on-surface">서류 / 소형 박스</p>
              <p className="font-body-md text-body-md text-secondary">라면 박스 크기 이하, 5kg 미만</p>
            </div>
          </div>
          <div className="mt-sm">
            <p className="font-label-sm text-label-sm text-secondary">고객 요청사항</p>
            <p className="font-body-md text-body-md text-on-surface mt-xs bg-surface p-sm rounded-lg border border-surface-variant">"문 앞에 두고 문자 부탁드립니다."</p>
          </div>
        </section>
        {/* Section 4: History Timeline */}
        <section className="bg-surface-container-lowest rounded-xl p-md shadow-[0_4px_12px_rgba(0,0,0,0.08)]">
          <h3 className="font-label-lg text-label-lg text-secondary mb-md">상태 이력</h3>
          <div className="flex flex-col gap-0 relative ml-2">
            {/* Line */}
            <div className="absolute left-2.5 top-2 bottom-6 w-[2px] bg-surface-variant z-0"></div>
            {/* Step 1 */}
            <div className="flex gap-md relative z-10 pb-md">
              <div className="w-6 h-6 rounded-full bg-surface border-2 border-surface-variant flex items-center justify-center bg-white">
                <span className="w-2 h-2 rounded-full bg-secondary"></span>
              </div>
              <div className="flex-1 -mt-0.5">
                <p className="font-body-md text-body-md font-semibold text-on-surface">접수 완료</p>
                <p className="font-label-sm text-label-sm text-secondary">13:10</p>
              </div>
            </div>
            {/* Step 2 */}
            <div className="flex gap-md relative z-10 pb-md">
              <div className="w-6 h-6 rounded-full bg-surface border-2 border-surface-variant flex items-center justify-center bg-white">
                <span className="w-2 h-2 rounded-full bg-secondary"></span>
              </div>
              <div className="flex-1 -mt-0.5">
                <p className="font-body-md text-body-md font-semibold text-on-surface">기사 배정</p>
                <p className="font-label-sm text-label-sm text-secondary">13:15</p>
              </div>
            </div>
            {/* Step 3 */}
            <div className="flex gap-md relative z-10 pb-md">
              <div className="w-6 h-6 rounded-full bg-surface border-2 border-surface-variant flex items-center justify-center bg-white">
                <span className="w-2 h-2 rounded-full bg-secondary"></span>
              </div>
              <div className="flex-1 -mt-0.5">
                <p className="font-body-md text-body-md font-semibold text-on-surface">픽업 완료</p>
                <p className="font-label-sm text-label-sm text-secondary">13:40</p>
              </div>
            </div>
            {/* Step 4 (Current/Final) */}
            <div className="flex gap-md relative z-10">
              <div className="w-6 h-6 rounded-full bg-primary-container border-2 border-primary-container flex items-center justify-center">
                <span className="material-symbols-outlined text-[#191919]" style={{ fontSize: '14px', fontWeight: 700 }}>check</span>
              </div>
              <div className="flex-1 -mt-0.5">
                <p className="font-body-md text-body-md font-bold text-primary">배송 완료</p>
                <p className="font-label-sm text-label-sm text-secondary">14:30</p>
              </div>
            </div>
          </div>
        </section>
        {/* Section 5: Settlement Detail */}
        <section className="bg-surface-container-lowest rounded-xl p-md shadow-[0_4px_12px_rgba(0,0,0,0.08)] mb-xl">
          <h3 className="font-label-lg text-label-lg text-secondary mb-md border-b border-surface-variant pb-2">정산 내역</h3>
          <div className="flex flex-col gap-sm mt-md">
            <div className="flex justify-between items-center">
              <span className="font-body-md text-body-md text-on-surface">기본 운임</span>
              <span className="font-body-md text-body-md text-on-surface">8,000 원</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="font-body-md text-body-md text-on-surface">거리 할증 (1.2km)</span>
              <span className="font-body-md text-body-md text-on-surface">+1,500 원</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="font-body-md text-body-md text-on-surface">날씨 할증 (우천)</span>
              <span className="font-body-md text-body-md text-on-surface">+1,000 원</span>
            </div>
            <div className="flex justify-between items-center mt-sm pt-sm border-t border-surface-variant border-dashed">
              <span className="font-body-lg text-body-lg font-semibold text-on-surface">확정 운임</span>
              <span className="font-body-lg text-body-lg font-semibold text-on-surface">10,500 원</span>
            </div>
            <div className="flex justify-between items-center text-secondary">
              <span className="font-label-sm text-label-sm">수수료 (10%)</span>
              <span className="font-label-sm text-label-sm">-1,050 원</span>
            </div>
          </div>
          <div className="mt-md bg-surface-container-low p-md rounded-lg flex justify-between items-center border border-primary-container">
            <span className="font-headline-sm text-headline-sm font-bold text-on-surface">최종 정산 금액</span>
            <span className="font-headline-lg text-headline-lg font-bold text-primary">9,450 원</span>
          </div>
        </section>
      </main>
    </div>
  )
}
