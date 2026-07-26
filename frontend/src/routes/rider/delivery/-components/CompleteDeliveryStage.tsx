/**
 * 배송 완료 인증(COMPLETED) 단계 — 시안 프레젠테이션 전용.
 * 원본 시안: rider/_3/complete_devlivery.html
 */
export function CompleteDeliveryStage() {
  return (
    <div className="max-w-md mx-auto bg-white h-[100dvh] flex flex-col relative shadow-[0_0_10px_rgba(0,0,0,0.1)] overflow-hidden">
      {/* BEGIN: Header */}
      <header className="flex items-center h-14 px-4 bg-white z-10 shrink-0">
        {/* Back Button */}
        <button aria-label="뒤로가기" className="p-1 -ml-1 text-gray-800 focus:outline-none">
          <svg className="h-6 w-6" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
            <path d="M10 19l-7-7m0 0l7-7m-7 7h18" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
        {/* Title */}
        <h1 className="ml-4 text-[17px] font-medium text-gray-700">촬영 (비대면 수령)</h1>
      </header>
      {/* END: Header */}
      {/* BEGIN: Main Content Area */}
      <main aria-label="배송 완료 인증" className="flex-1 flex flex-col bg-white overflow-y-auto">
        {/* Captured Image Container */}
        <div className="w-full relative bg-gray-100 flex-1 min-h-[50vh]">
          {/* TODO: 배송 완료 인증 사진 연결 */}
          <div className="absolute inset-0 bg-surface-container-high" />
        </div>
        {/* Helper Text Area */}
        <div className="py-12 px-4 flex items-center justify-center shrink-0">
          <p className="text-[15px] text-gray-600 font-medium">
            위 이미지로 고객에게 전송됩니다.
          </p>
        </div>
      </main>
      {/* END: Main Content Area */}
      {/* BEGIN: Bottom Action Bar */}
      <footer className="flex h-[72px] shrink-0 w-full">
        {/* Retake Button */}
        <button className="w-[35%] bg-gray-600 hover:bg-gray-700 active:bg-gray-800 transition-colors text-white text-lg font-bold flex items-center justify-center focus:outline-none">
          재촬영
        </button>
        {/* Complete Delivery Button */}
        <button className="w-[65%] bg-[#8B5CF6] hover:bg-[#7c4dec] active:bg-[#6c40db] transition-colors text-white text-[20px] font-bold flex items-center justify-center focus:outline-none">
          배송 완료하기
        </button>
      </footer>
      {/* END: Bottom Action Bar */}
    </div>
  )
}
