/**
 * 픽업 중(배송 출발 대기) 단계 — 시안 프레젠테이션 전용.
 * 원본 시안: rider/_1/while_pickup.html
 */
export function WhilePickupStage() {
  return (
    <div className="flex flex-col min-h-screen max-w-md mx-auto bg-white">
      {/* BEGIN: MainContent */}
      <main aria-label="배송 출발 안내" className="flex-1 overflow-y-auto pb-24 px-4 pt-6">
        {/* BEGIN: HeaderSection */}
        <header className="mb-6">
          <div className="text-gray-400 text-sm mb-1 font-medium">ORD110239375</div>
          <h1 className="text-2xl font-bold text-[#6B46C1] mb-2">배송 출발해주세요</h1>
          <div className="flex items-center text-sm">
            <span className="text-[#6B46C1] font-bold mr-1">13:30<span className="text-gray-500 font-normal">까지 배송완료</span></span>
            <span className="mx-2 text-gray-300">|</span>
            <span className="text-gray-500">배송지 0.7km</span>
          </div>
        </header>
        {/* END: HeaderSection */}
        {/* BEGIN: AddressSection */}
        <section className="mb-6">
          <div className="relative pl-6 pb-6">
            {/* Vertical dashed line */}
            <div className="absolute left-2.5 top-2 bottom-0 w-px border-l-2 border-dashed border-[#E5E7EB] -ml-px" />
            {/* Pickup Point */}
            <div className="relative mb-6">
              <div className="absolute left-[-24px] top-1.5 w-2 h-2 rounded-full bg-gray-300" />
              <div className="flex justify-between items-start">
                <div>
                  <div className="flex items-center gap-2 mb-1">
                    <h2 className="text-lg font-bold text-gray-800">분당구 백현동 537</h2>
                    <button className="text-xs border border-gray-200 rounded px-1 text-gray-400">복사</button>
                  </div>
                  <p className="text-gray-500 text-sm">카카오모빌리티, 13층</p>
                  <p className="text-gray-500 text-sm">김카모</p>
                </div>
                <button className="p-3 bg-gray-50 rounded-full">
                  <svg className="h-5 w-5 text-gray-600" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                    <path d="M3 5a2 2 0 012-2h3.28a1 1 0 01.948.684l1.498 4.493a1 1 0 01-.502 1.21l-2.257 1.13a11.042 11.042 0 005.516 5.516l1.13-2.257a1 1 0 011.21-.502l4.493 1.498a1 1 0 01.684.949V19a2 2 0 01-2 2h-1C9.716 21 3 14.284 3 6V5z" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" />
                  </svg>
                </button>
              </div>
            </div>
            {/* Delivery Point */}
            <div className="relative">
              <div className="absolute left-[-24px] top-1.5 w-2 h-2 rounded-full bg-[#8B5CF6]" />
              <div className="flex justify-between items-start">
                <div>
                  <div className="flex items-center gap-2 mb-1">
                    <h2 className="text-lg font-bold text-gray-800">분당구 정자동 126</h2>
                    <button className="text-xs border border-gray-200 rounded px-1 text-gray-400">복사</button>
                  </div>
                  <p className="text-gray-500 text-sm">GS타워, 14층 운영지원팀</p>
                  <p className="text-gray-500 text-sm">김미영 과장님</p>
                </div>
                <button className="p-3 bg-gray-50 rounded-full">
                  <svg className="h-5 w-5 text-gray-600" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                    <path d="M3 5a2 2 0 012-2h3.28a1 1 0 01.948.684l1.498 4.493a1 1 0 01-.502 1.21l-2.257 1.13a11.042 11.042 0 005.516 5.516l1.13-2.257a1 1 0 011.21-.502l4.493 1.498a1 1 0 01.684.949V19a2 2 0 01-2 2h-1C9.716 21 3 14.284 3 6V5z" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" />
                  </svg>
                </button>
              </div>
            </div>
          </div>
        </section>
        {/* END: AddressSection */}
        {/* BEGIN: DetailsSection */}
        <section className="space-y-2 mb-6">
          <div className="flex bg-gray-50 p-4 rounded-lg items-center">
            <span className="text-gray-400 w-20 text-sm">픽업 장소</span>
            <span className="font-medium text-gray-800">직접전달 (부재시 문앞)</span>
          </div>
          <div className="flex bg-gray-50 p-4 rounded-lg items-center">
            <span className="text-gray-400 w-20 text-sm">물품 정보</span>
            <span className="font-medium text-gray-800">초소형</span>
          </div>
        </section>
        {/* END: DetailsSection */}
        {/* BEGIN: EarningsSection */}
        <section className="bg-gray-50 p-5 rounded-lg mb-6">
          <div className="flex justify-between items-center mb-4 pb-4 border-b border-gray-200">
            <span className="text-gray-800 font-bold text-lg">총 수익</span>
            <div className="flex items-center gap-1">
              <span className="text-2xl font-bold text-gray-800">28,000</span>
              <div className="w-6 h-6 bg-yellow-400 text-white rounded-full flex items-center justify-center text-xs font-bold">P</div>
            </div>
          </div>
          <div className="space-y-2 text-sm">
            <div className="flex justify-between text-gray-600">
              <span>배송비</span>
              <span className="font-medium text-gray-800">23,000P</span>
            </div>
            <div className="flex justify-between text-gray-600">
              <span>추가금 (A)</span>
              <span className="font-medium text-gray-800">5,000P</span>
            </div>
          </div>
        </section>
        {/* END: EarningsSection */}
        {/* BEGIN: ActionButtons */}
        <section className="space-y-3">
          <div className="flex gap-3">
            <button className="flex-1 py-3 border border-gray-200 rounded-lg text-gray-700 font-medium text-sm text-center">
              추가요금 안내
            </button>
          </div>
        </section>
        {/* END: ActionButtons */}
      </main>
      {/* END: MainContent */}
      {/* BEGIN: BottomNavigation */}
      <footer className="fixed bottom-0 left-0 right-0 max-w-md mx-auto flex h-20">
        <button className="w-24 bg-[#4B5563] text-white flex flex-col items-center justify-center">
          <svg className="h-8 w-8 mb-1" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
            <path d="M9 5l7 7-7 7" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" />
          </svg>
          <span className="text-sm font-bold">길안내</span>
        </button>
        <button className="flex-1 bg-[#8B5CF6] text-white text-2xl font-bold flex items-center justify-center">
          배송 출발하기
        </button>
      </footer>
      {/* END: BottomNavigation */}
    </div>
  )
}
