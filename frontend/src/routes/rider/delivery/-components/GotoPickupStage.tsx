/**
 * 픽업지로 이동 단계 (MOVING_TO_PICKUP) — 시안 프레젠테이션 전용.
 * 원본 시안: rider/_5/goto_pickup.html
 */
export function GotoPickupStage() {
  return (
    <div className="h-screen w-full max-w-md mx-auto flex flex-col font-sans text-[#111111] overflow-hidden">
      {/* BEGIN: MainHeader */}
      <header className="bg-white px-4 py-4 flex items-center justify-between shadow-sm relative z-20">
        <button aria-label="Go back" className="text-[#111111] p-1">
          <svg className="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
            <path d="M10 19l-7-7m0 0l7-7m-7 7h18" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" />
          </svg>
        </button>
        <div className="text-[#767676] text-sm font-medium absolute left-1/2 transform -translate-x-1/2">
          ORD110239375
        </div>
      </header>
      {/* END: MainHeader */}
      {/* BEGIN: StatusHeader */}
      <section className="bg-white px-5 py-4 z-20 border-b border-gray-100 shadow-sm relative">
        <h1 className="text-[28px] font-bold text-[#4a6cf7] leading-tight mb-2 tracking-tight">픽업 출발해주세요</h1>
        <div className="flex items-center text-sm">
          <span className="font-bold text-[#4a6cf7] mr-1">13:30</span>
          <span className="text-[#4a6cf7] mr-3">까지 픽업완료</span>
          <span className="text-gray-300 mx-2">|</span>
          <span className="text-[#767676]">픽업지 <strong className="text-[#111111] font-semibold">0.7km</strong></span>
        </div>
      </section>
      {/* END: StatusHeader */}
      {/* BEGIN: MapSection */}
      {/* TODO: 지도 연결 (원본 시안의 map-bg 배경 이미지 대체) */}
      <div className="flex-1 bg-surface-container-high relative z-0" />
      {/* END: MapSection */}
      {/* BEGIN: BottomSheetCard */}
      <main aria-label="픽업 출발 안내" className="bg-white rounded-t-3xl pt-6 px-5 pb-24 shadow-[0_-4px_16px_rgba(0,0,0,0.05)] z-20 relative flex flex-col gap-4 mt-auto overflow-y-auto max-h-[65vh]">
        {/* Drag Handle */}
        <div className="absolute top-3 left-1/2 transform -translate-x-1/2 w-10 h-1 bg-gray-200 rounded-full" />
        {/* Locations */}
        <div className="flex flex-col gap-0 relative pt-2">
          {/* Pickup Point */}
          <div className="flex items-start">
            <div className="flex flex-col items-center mr-3 relative w-4">
              <span className="inline-block w-2.5 h-2.5 rounded-full bg-[#4a6cf7] mt-1.5" />
              <div className="dotted-line w-0.5 h-10 my-1" />
            </div>
            <div className="flex-1 pb-4">
              <div className="flex justify-between items-start">
                <div>
                  <div className="flex items-center gap-2 mb-1">
                    <span className="bg-[#fefce8] px-1 text-[17px] font-bold text-[#111111]">성동구 성수동2가 333</span>
                    <button className="border border-gray-300 rounded text-[11px] px-1.5 py-0.5 text-gray-500">복사</button>
                  </div>
                  <p className="text-[15px] text-[#767676]">카카오모빌리티, 13층</p>
                  <p className="text-[15px] text-[#767676] mt-0.5">김카모</p>
                </div>
                <button aria-label="Call Pickup" className="w-10 h-10 bg-gray-100 rounded-full flex items-center justify-center text-gray-600 mt-1">
                  <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20" xmlns="http://www.w3.org/2000/svg">
                    <path d="M2 3a1 1 0 011-1h2.153a1 1 0 01.986.836l.74 4.435a1 1 0 01-.54 1.06l-1.548.773a11.037 11.037 0 006.105 6.105l.774-1.548a1 1 0 011.059-.54l4.435.74a1 1 0 01.836.986V17a1 1 0 01-1 1h-2C7.82 18 2 12.18 2 5V3z" />
                  </svg>
                </button>
              </div>
            </div>
          </div>
          {/* Dropoff Point */}
          <div className="flex items-start">
            <div className="flex flex-col items-center mr-3 relative w-4">
              <span className="inline-block w-2.5 h-2.5 rounded-full bg-[#c4c4c4] mt-1.5" />
            </div>
            <div className="flex-1">
              <div className="flex justify-between items-start">
                <div>
                  <div className="flex items-center gap-2 mb-1">
                    <span className="bg-[#fefce8] px-1 text-[17px] font-bold text-[#111111]">강남구 압구정동 369</span>
                    <button className="border border-gray-300 rounded text-[11px] px-1.5 py-0.5 text-gray-500">복사</button>
                  </div>
                  <p className="text-[15px] text-[#767676]">GS타워, 14층 운영지원팀</p>
                  <p className="text-[15px] text-[#767676] mt-0.5">김미영 과장님</p>
                </div>
                <button aria-label="Call Dropoff" className="w-10 h-10 bg-gray-100 rounded-full flex items-center justify-center text-gray-600 mt-1">
                  <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20" xmlns="http://www.w3.org/2000/svg">
                    <path d="M2 3a1 1 0 011-1h2.153a1 1 0 01.986.836l.74 4.435a1 1 0 01-.54 1.06l-1.548.773a11.037 11.037 0 006.105 6.105l.774-1.548a1 1 0 011.059-.54l4.435.74a1 1 0 01.836.986V17a1 1 0 01-1 1h-2C7.82 18 2 12.18 2 5V3z" />
                  </svg>
                </button>
              </div>
            </div>
          </div>
        </div>
        {/* Order Details */}
        <div className="flex flex-col gap-2 mt-2">
          <div className="bg-[#f3f3f3] rounded-lg p-3.5 flex items-center">
            <span className="text-[#767676] text-[14px] w-20">픽업 장소</span>
            <span className="text-[#111111] text-[15px] font-medium">직접전달 (부재시 문앞)</span>
          </div>
          <div className="bg-[#f3f3f3] rounded-lg p-3.5 flex items-center">
            <span className="text-[#767676] text-[14px] w-20">물품 정보</span>
            <span className="text-[#111111] text-[15px] font-medium">초소형</span>
          </div>
        </div>
        {/* Earnings Breakdown */}
        <div className="bg-[#f3f3f3] rounded-xl p-5 mt-2">
          <div className="flex justify-between items-center mb-4 border-b border-gray-200 pb-4">
            <span className="text-lg font-bold text-[#111111]">총 수익</span>
            <div className="flex items-center gap-1.5">
              <span className="text-[22px] font-bold text-[#111111]">28,000</span>
              <div className="w-[22px] h-[22px] bg-[#fee500] rounded-full flex items-center justify-center">
                <span className="text-white font-bold text-[14px] leading-none">P</span>
              </div>
            </div>
          </div>
          <div className="flex flex-col gap-2.5">
            <div className="flex justify-between items-center">
              <span className="text-[#767676] text-[14px]">배송비</span>
              <span className="text-[#111111] font-medium text-[15px]">23,000P</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="text-[#767676] text-[14px]">추가금 (A)</span>
              <span className="text-[#111111] font-medium text-[15px]">5,000P</span>
            </div>
          </div>
        </div>
        {/* Action Links */}
        <div className="flex flex-col gap-2 mt-2">
          <button className="w-full border border-gray-200 rounded-lg py-3.5 text-[15px] font-medium text-[#111111]">
            오더 수행을 위한 팁
          </button>
          <div className="flex gap-2">
            <button className="flex-1 border border-gray-200 rounded-lg py-3.5 text-[15px] font-medium text-[#111111]">
              고객센터 연결
            </button>
            <button className="flex-1 border border-gray-200 rounded-lg py-3.5 text-[15px] font-medium text-[#111111]">
              추가요금 안내
            </button>
          </div>
        </div>
      </main>
      {/* END: BottomSheetCard */}
      {/* BEGIN: BottomActions */}
      <div className="fixed bottom-0 left-0 right-0 max-w-md mx-auto z-30 flex h-[72px]">
        <button aria-label="Navigate" className="w-[120px] bg-[#585858] text-white flex flex-col items-center justify-center">
          <svg className="w-6 h-6 mb-1" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
            <path d="M14 5l7 7m0 0l-7 7m7-7H3" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" />
          </svg>
          <span className="text-[14px] font-bold">길안내</span>
        </button>
        <button aria-label="Start Pickup" className="flex-1 bg-[#4a6cf7] text-white flex items-center justify-center text-[22px] font-bold">
          픽업 출발하기
        </button>
      </div>
      {/* END: BottomActions */}
    </div>
  )
}
