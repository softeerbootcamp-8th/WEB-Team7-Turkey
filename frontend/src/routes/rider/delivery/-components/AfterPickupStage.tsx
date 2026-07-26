/**
 * 픽업 후 배송 중(DELIVERING) 단계 — 시안 프레젠테이션 전용.
 * 원본 시안: rider/_2/after_pickup.html
 */
export function AfterPickupStage() {
  return (
    <div className="flex flex-col h-screen max-w-md mx-auto bg-white overflow-hidden shadow-lg border border-gray-200">
      {/* BEGIN: MainContent */}
      <main aria-label="배송 완료 안내" className="flex-1 overflow-y-auto pb-24">
        {/* BEGIN: HeaderSection */}
        <header className="pt-6 pb-4 px-5 bg-white">
          <p className="text-sm text-gray-500 font-medium mb-1">ORD110239375</p>
          <h1 className="text-3xl font-bold text-[#8a3ffc] mb-2 tracking-tight">배달을 완료해주세요</h1>
          <div className="flex items-center text-[15px]">
            <span className="text-[#8a3ffc] font-semibold">13:30<span className="text-gray-500 font-normal">까지 배송완료</span></span>
            <span className="mx-2 text-gray-300">|</span>
            <span className="text-gray-600">배송지 0.7km</span>
          </div>
        </header>
        {/* END: HeaderSection */}
        {/* Divider */}
        <div className="h-2 bg-gray-100" />
        {/* BEGIN: AddressSection */}
        <section className="px-5 py-6 bg-white relative">
          {/* Location Timeline Line */}
          <div className="absolute left-7 top-10 bottom-24 w-0.5 border-l-2 border-dotted border-gray-300" />
          {/* Origin Address */}
          <div className="flex items-start mb-6 relative">
            <div className="w-4 h-4 rounded-full bg-gray-300 mt-1 flex-shrink-0 relative z-10 ring-4 ring-white" />
            <div className="ml-4 flex-1">
              <div className="flex items-center gap-2 mb-1">
                <h2 className="text-lg font-bold text-gray-900">분당구 백현동 537</h2>
                <button className="px-2 py-0.5 text-xs text-gray-500 border border-gray-300 rounded bg-white">복사</button>
              </div>
              <p className="text-[15px] text-gray-500 mb-0.5">카카오모빌리티, 13층</p>
              <p className="text-[15px] text-gray-500">김카모</p>
            </div>
            <button className="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center flex-shrink-0 ml-2">
              <svg className="w-5 h-5 text-gray-600" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                <path d="M3 5a2 2 0 012-2h3.28a1 1 0 01.948.684l1.498 4.493a1 1 0 01-.502 1.21l-2.257 1.13a11.042 11.042 0 005.516 5.516l1.13-2.257a1 1 0 011.21-.502l4.493 1.498a1 1 0 01.684.949V19a2 2 0 01-2 2h-1C9.716 21 3 14.284 3 6V5z" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" />
              </svg>
            </button>
          </div>
          {/* Destination Address */}
          <div className="flex items-start relative">
            <div className="w-4 h-4 rounded-full bg-[#8a3ffc] mt-1 flex-shrink-0 relative z-10 ring-4 ring-white" />
            <div className="ml-4 flex-1">
              <div className="flex items-center gap-2 mb-1">
                <h2 className="text-lg font-bold text-gray-900">분당구 정자동 126</h2>
                <button className="px-2 py-0.5 text-xs text-gray-500 border border-gray-300 rounded bg-white">복사</button>
              </div>
              <p className="text-[15px] text-gray-500 mb-0.5">GS타워, 14층 운영지원팀</p>
              <p className="text-[15px] text-gray-500">김미영 과장님</p>
            </div>
            <button className="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center flex-shrink-0 ml-2">
              <svg className="w-5 h-5 text-gray-600" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                <path d="M3 5a2 2 0 012-2h3.28a1 1 0 01.948.684l1.498 4.493a1 1 0 01-.502 1.21l-2.257 1.13a11.042 11.042 0 005.516 5.516l1.13-2.257a1 1 0 011.21-.502l4.493 1.498a1 1 0 01.684.949V19a2 2 0 01-2 2h-1C9.716 21 3 14.284 3 6V5z" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" />
              </svg>
            </button>
          </div>
        </section>
        {/* END: AddressSection */}
        {/* BEGIN: DeliveryInfoSection */}
        <section className="px-5 pb-6 bg-white space-y-2">
          <div className="flex bg-gray-50/80 rounded-lg p-4 items-center">
            <span className="w-20 text-[15px] text-gray-500">픽업 장소</span>
            <span className="text-[15px] font-semibold text-gray-900">직접전달 (부재시 문앞)</span>
          </div>
          <div className="flex bg-gray-50/80 rounded-lg p-4 items-center">
            <span className="w-20 text-[15px] text-gray-500">물품 정보</span>
            <span className="text-[15px] font-semibold text-gray-900">초소형</span>
          </div>
        </section>
        {/* END: DeliveryInfoSection */}
        {/* BEGIN: EarningsSection */}
        <section className="px-5 pb-6 bg-white">
          <div className="bg-gray-50/80 rounded-xl p-5 border border-gray-100">
            <div className="flex justify-between items-center mb-4 pb-4 border-b border-gray-200">
              <span className="text-lg font-bold text-gray-700">총 수익</span>
              <div className="flex items-center gap-1">
                <span className="text-2xl font-bold text-gray-900">28,000</span>
                <span className="w-6 h-6 rounded-full bg-yellow-400 text-white flex items-center justify-center text-sm font-bold">P</span>
              </div>
            </div>
            <div className="space-y-2">
              <div className="flex justify-between items-center">
                <span className="text-[15px] text-gray-500">배송비</span>
                <span className="text-[15px] font-semibold text-gray-900">23,000P</span>
              </div>
              <div className="flex justify-between items-center">
                <span className="text-[15px] text-gray-500">추가금 (A)</span>
                <span className="text-[15px] font-semibold text-gray-900">5,000P</span>
              </div>
            </div>
          </div>
        </section>
        {/* END: EarningsSection */}
        {/* BEGIN: ActionButtonsSection */}
        <section className="px-5 pb-8 bg-white space-y-3">
          <button className="w-full py-3.5 px-4 bg-white border border-gray-200 rounded-lg text-gray-700 font-medium text-[15px] shadow-sm flex items-center justify-center gap-2">
            오더 수행을 위한 팁
          </button>
          <div className="flex gap-3">
            <button className="flex-1 py-3.5 px-4 bg-white border border-gray-200 rounded-lg text-gray-700 font-medium text-[15px] shadow-sm">
              고객센터 연결
            </button>
            <button className="flex-1 py-3.5 px-4 bg-white border border-gray-200 rounded-lg text-gray-700 font-medium text-[15px] shadow-sm">
              추가요금 안내
            </button>
          </div>
        </section>
        {/* END: ActionButtonsSection */}
      </main>
      {/* END: MainContent */}
      {/* BEGIN: BottomActionBar */}
      <footer className="fixed bottom-0 left-0 right-0 max-w-md mx-auto flex h-20 shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.05)] bg-white z-50">
        <button className="w-32 bg-gray-600 text-white flex flex-col items-center justify-center gap-1 active:bg-gray-700 transition-colors">
          <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
            <path d="M14 5l7 7m0 0l-7 7m7-7H3" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2.5" />
          </svg>
          <span className="text-sm font-semibold">길안내</span>
        </button>
        <button className="flex-1 bg-[#8a3ffc] text-white text-xl font-bold active:bg-[#722ed1] transition-colors">
          배송 완료하기
        </button>
      </footer>
      {/* END: BottomActionBar */}
    </div>
  )
}
