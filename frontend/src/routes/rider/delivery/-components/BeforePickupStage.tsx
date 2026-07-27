/**
 * 픽업 전(픽업지 도착) 단계 — 시안 프레젠테이션 전용.
 * 원본 시안: rider/_4/before_pickup.html
 */
export function BeforePickupStage() {
  return (
    <main
      aria-label="픽업 완료 안내"
      className="relative max-w-md mx-auto bg-white min-h-screen pb-20 shadow-[0_0_20px_rgba(0,0,0,0.05)]"
    >
      {/* BEGIN: Header Section */}
      <header className="p-5 pt-8">
        <div className="text-gray-400 text-sm font-medium mb-1">ORD110239375</div>
        <h1 className="text-[#4285F4] text-3xl font-bold tracking-tight mb-2">픽업을 완료해주세요</h1>
        <div className="flex items-center text-sm">
          <span className="text-[#4285F4] font-semibold mr-2">13:30까지 픽업완료</span>
          <span className="text-gray-300 mx-1">|</span>
          <span className="text-gray-500 ml-1">픽업지 0.7km</span>
        </div>
      </header>
      {/* END: Header Section */}
      {/* Divider */}
      <div className="h-1 bg-gray-100 mx-5 rounded-full mb-6" />
      {/* BEGIN: Address List */}
      <section className="px-5 mb-6">
        {/* Pickup Address */}
        <div className="flex items-start mb-2 relative">
          <div className="mt-1.5 w-6 flex-shrink-0 flex flex-col items-center">
            <div className="w-2.5 h-2.5 rounded-full bg-[#4285F4]" />
            <div className="dotted-line w-0.5 h-10" />
          </div>
          <div className="flex-grow ml-2">
            <div className="flex items-center mb-1">
              <span className="bg-yellow-100 px-2 py-0.5 rounded text-gray-800 font-bold text-lg leading-tight">성동구 성수동2가 333</span>
              <button className="ml-2 border border-gray-300 rounded px-1.5 py-0.5 text-xs text-gray-500 whitespace-nowrap">복사</button>
            </div>
            <div className="text-gray-500 text-sm">카카오모빌리티, 13층</div>
            <div className="text-gray-500 text-sm">김카모</div>
          </div>
          <button className="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center flex-shrink-0 mt-1">
            <svg className="h-5 w-5 text-gray-600" fill="currentColor" viewBox="0 0 20 20" xmlns="http://www.w3.org/2000/svg">
              <path d="M2 3a1 1 0 011-1h2.153a1 1 0 01.986.836l.74 4.435a1 1 0 01-.54 1.06l-1.548.773a11.037 11.037 0 006.105 6.105l.774-1.548a1 1 0 011.059-.54l4.435.74a1 1 0 01.836.986V17a1 1 0 01-1 1h-2C7.82 18 2 12.18 2 5V3z" />
            </svg>
          </button>
        </div>
        {/* Destination Address */}
        <div className="flex items-start">
          <div className="mt-1.5 w-6 flex-shrink-0 flex flex-col items-center">
            <div className="w-2.5 h-2.5 rounded-full bg-gray-300" />
          </div>
          <div className="flex-grow ml-2">
            <div className="flex items-center mb-1">
              <span className="bg-yellow-100 px-2 py-0.5 rounded text-gray-800 font-bold text-lg leading-tight">강남구 압구정동 369</span>
              <button className="ml-2 border border-gray-300 rounded px-1.5 py-0.5 text-xs text-gray-500 whitespace-nowrap">복사</button>
            </div>
            <div className="text-gray-500 text-sm">GS타워, 14층 운영지원팀</div>
            <div className="text-gray-500 text-sm">김미영 과장님</div>
          </div>
          <button className="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center flex-shrink-0 mt-1">
            <svg className="h-5 w-5 text-gray-600" fill="currentColor" viewBox="0 0 20 20" xmlns="http://www.w3.org/2000/svg">
              <path d="M2 3a1 1 0 011-1h2.153a1 1 0 01.986.836l.74 4.435a1 1 0 01-.54 1.06l-1.548.773a11.037 11.037 0 006.105 6.105l.774-1.548a1 1 0 011.059-.54l4.435.74a1 1 0 01.836.986V17a1 1 0 01-1 1h-2C7.82 18 2 12.18 2 5V3z" />
            </svg>
          </button>
        </div>
      </section>
      {/* END: Address List */}
      {/* BEGIN: Details Cards */}
      <section className="px-5 space-y-2 mb-6">
        <div className="bg-gray-50 rounded-lg p-4 flex items-center">
          <span className="text-gray-500 text-sm w-20 flex-shrink-0">픽업 장소</span>
          <span className="text-gray-800 font-semibold text-sm">직접전달 (부재시 문앞)</span>
        </div>
        <div className="bg-gray-50 rounded-lg p-4 flex items-center">
          <span className="text-gray-500 text-sm w-20 flex-shrink-0">물품 정보</span>
          <span className="text-gray-800 font-semibold text-sm">초소형</span>
        </div>
      </section>
      {/* END: Details Cards */}
      {/* BEGIN: Earnings Section */}
      <section className="px-5 mb-6">
        <div className="bg-gray-50 rounded-xl p-5">
          <div className="flex justify-between items-center mb-4 border-b border-gray-200 pb-4">
            <span className="text-gray-800 font-bold text-lg">총 수익</span>
            <div className="flex items-center">
              <span className="text-2xl font-bold text-gray-900 mr-1">28,000</span>
              <div className="w-5 h-5 bg-[#fee500] rounded-full flex items-center justify-center text-xs font-bold text-gray-800">P</div>
            </div>
          </div>
          <div className="space-y-2">
            <div className="flex justify-between items-center text-sm">
              <span className="text-gray-500">배송비</span>
              <span className="text-gray-800 font-medium">23,000P</span>
            </div>
            <div className="flex justify-between items-center text-sm">
              <span className="text-gray-500">추가금 (A)</span>
              <span className="text-gray-800 font-medium">5,000P</span>
            </div>
          </div>
        </div>
      </section>
      {/* END: Earnings Section */}
      {/* BEGIN: Bottom Action Bar */}
      <footer className="fixed bottom-0 left-0 right-0 max-w-md mx-auto flex h-20 shadow-[0_-2px_10px_rgba(0,0,0,0.05)]">
        <button className="w-1/3 bg-gray-600 flex flex-col items-center justify-center text-white">
          <svg className="h-8 w-8 mb-0.5 transform -rotate-45" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
            <path d="M14 5l7 7m0 0l-7 7m7-7H3" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" />
          </svg>
          <span className="text-sm font-semibold">길안내</span>
        </button>
        <button className="w-2/3 bg-[#4285F4] flex items-center justify-center text-white text-2xl font-bold tracking-wide">
          픽업 완료하기
        </button>
      </footer>
      {/* END: Bottom Action Bar */}
    </main>
  )
}
