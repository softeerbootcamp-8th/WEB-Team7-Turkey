import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/customer/_authed/deliveries/$deliveryId/tracking')({
  component: DeliveryTracking,
})

function DeliveryTracking() {
  return (
    // BEGIN: Mobile Container
    <div className="relative w-full max-w-md h-full bg-white shadow-xl overflow-hidden flex flex-col">
      {/* BEGIN: Map Background */}
      <div className="absolute inset-0 z-0 bg-gray-200">
        {/* Placeholder for actual map implementation */}
        <div className="w-full h-full flex flex-col justify-end pb-32">
          <div className="w-full h-64 bg-slate-300 relative">
            <span className="absolute bottom-2 left-2 text-xs font-bold text-gray-600">kakao</span>
            {/* TODO: 실제 지도(SSE 실시간 위치) 연결 — TrackingMap 컴포넌트 */}
            <div className="absolute inset-0 opacity-20 bg-surface-container-high bg-cover bg-center"></div>
          </div>
        </div>
      </div>
      {/* END: Map Background */}
      {/* BEGIN: Status Bottom Sheet */}
      <div className="relative z-10 w-full bg-white rounded-b-3xl shadow-lg flex-1 max-h-[80%] flex flex-col">
        {/* BEGIN: Header */}
        <header className="flex justify-between items-center p-5 pt-8">
          <button aria-label="Home" className="p-2 -ml-2">
            <svg className="h-6 w-6 text-slate-800" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
              <path d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6" strokeLinecap="round" strokeLinejoin="round"></path>
            </svg>
          </button>
          <button className="text-sm font-medium text-gray-500">
            주문취소
          </button>
        </header>
        {/* END: Header */}
        {/* BEGIN: Scrollable Content */}
        <main className="flex-1 overflow-y-auto no-scrollbar px-5 pb-8">
          {/* Status Title */}
          <section className="mt-2 mb-8">
            <h1 className="text-[26px] font-bold leading-tight text-gray-900 mb-1">
              퀵 접수가 완료됐어요
            </h1>
            <h2 className="text-[26px] font-bold leading-tight text-blue-600">
              10:51까지 픽업 목표
            </h2>
          </section>
          {/* BEGIN: Progress Tracker */}
          <section className="mb-10 relative">
            {/* Track Line */}
            <div className="absolute top-[9px] left-2 right-2 h-[3px] bg-gray-200 -z-10 rounded-full"></div>
            <div className="absolute top-[9px] left-2 w-1/2 h-[3px] bg-blue-600 -z-10 rounded-full"></div>
            <div className="flex justify-between">
              {/* Step 1: Received */}
              <div className="flex flex-col items-center">
                <div className="w-5 h-5 bg-blue-600 rounded-full flex items-center justify-center shadow-[0_0_0_4px_white]">
                  <div className="w-2 h-2 bg-white rounded-full"></div>
                </div>
                <span className="text-[11px] font-medium text-gray-500 mt-2">접수</span>
                <span className="text-[11px] text-gray-400">10:21</span>
              </div>
              {/* Step 2: Pickup Goal */}
              <div className="flex flex-col items-center">
                <div className="w-6 h-6 bg-blue-100 rounded-full flex items-center justify-center">
                  <div className="w-3 h-3 bg-blue-500 rounded-full"></div>
                </div>
                <span className="text-[11px] font-medium text-gray-500 mt-2">픽업목표</span>
                <span className="text-[11px] text-gray-400">10:51</span>
              </div>
              {/* Step 3: Delivery Goal */}
              <div className="flex flex-col items-center">
                <div className="w-5 h-5 bg-white border-2 border-gray-200 rounded-full flex items-center justify-center"></div>
                <span className="text-[11px] font-medium text-gray-500 mt-2">배송목표</span>
                <span className="text-[11px] text-gray-400">12:18</span>
              </div>
            </div>
          </section>
          {/* END: Progress Tracker */}
          {/* BEGIN: Order Details */}
          <section className="border-t border-gray-100 pt-6">
            <div className="flex justify-between items-center mb-5">
              <h3 className="text-base font-bold text-gray-900">
                주문 <span className="font-bold">KDTKY6R</span>
              </h3>
              <button className="text-sm text-gray-400 flex items-center gap-1">
                상세보기
                <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                  <path d="M9 5l7 7-7 7" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"></path>
                </svg>
              </button>
            </div>
            {/* Address List */}
            <div className="space-y-4 mb-6 relative before:absolute before:left-[11px] before:top-4 before:bottom-4 before:w-[2px] before:bg-gray-100 before:-z-10">
              {/* Pickup */}
              <div className="flex gap-4">
                <div className="w-6 h-6 bg-blue-50 rounded-full flex items-center justify-center shrink-0 border border-white">
                  <div className="w-2 h-2 bg-blue-500"></div>
                </div>
                <div>
                  <p className="text-sm font-medium text-gray-900 mb-0.5">도수향</p>
                  <p className="text-[13px] text-gray-500">서울 강남구 선릉로161길 21-4, 소자 4개</p>
                </div>
              </div>
              {/* Delivery */}
              <div className="flex gap-4">
                <div className="w-6 h-6 bg-red-50 rounded-full flex items-center justify-center shrink-0 border border-white">
                  <div className="w-2 h-2 bg-red-500 rounded-full"></div>
                </div>
                <div>
                  <p className="text-sm font-medium text-gray-900 mb-0.5">홍길동 (도착지)</p>
                  <p className="text-[13px] text-gray-500">서울 서초구 반포대로 123, 1층</p>
                </div>
              </div>
            </div>
            {/* Payment Summary Card */}
            <div className="bg-gray-50 rounded-xl p-4 space-y-3">
              <div className="flex justify-between items-center">
                <span className="text-sm text-gray-500">호출 상품</span>
                <span className="text-sm font-medium text-gray-900">오토바이 · 일반</span>
              </div>
              <div className="flex justify-between items-center">
                <span className="text-sm text-gray-500">최종 결제 금액</span>
                <div className="flex items-center gap-2">
                  <span className="text-[13px] text-gray-400">(현대카드···/체크카드)</span>
                  <span className="text-base font-bold text-gray-900">14,500원</span>
                </div>
              </div>
            </div>
          </section>
          {/* END: Order Details */}
          {/* Pull up handle / Close text */}
          <div className="flex justify-center mt-6">
            <button className="text-sm text-gray-400 flex items-center gap-1">
              닫기
              <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                <path d="M5 15l7-7 7 7" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"></path>
              </svg>
            </button>
          </div>
        </main>
        {/* END: Scrollable Content */}
      </div>
      {/* END: Status Bottom Sheet */}
      {/* BEGIN: Fixed Bottom Action */}
      <div className="absolute bottom-0 w-full p-5 bg-white z-20 pb-8 rounded-t-2xl shadow-[0_-4px_10px_rgba(0,0,0,0.05)]">
        {/* Tooltip */}
        <div className="absolute -top-12 left-1/2 -translate-x-1/2 bg-blue-600 text-white text-sm font-medium py-2 px-4 rounded-lg whitespace-nowrap shadow-md after:content-[''] after:absolute after:top-full after:left-1/2 after:-translate-x-1/2 after:border-[6px] after:border-transparent after:border-t-blue-600">
          보낼 물건이 더 있으신가요?
        </div>
        <button className="w-full bg-white border border-blue-500 text-blue-600 font-medium py-3.5 rounded-xl flex items-center justify-center gap-2">
          <svg className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
            <path d="M12 9v3m0 0v3m0-3h3m-3 0H9m12 0a9 9 0 11-18 0 9 9 0 0118 0z" strokeLinecap="round" strokeLinejoin="round"></path>
          </svg>
          퀵·택배 추가로 이용하기
        </button>
        {/* iOS Home Indicator */}
        <div className="w-32 h-1.5 bg-gray-900 rounded-full mx-auto mt-6"></div>
      </div>
      {/* END: Fixed Bottom Action */}
    </div>
  )
}
