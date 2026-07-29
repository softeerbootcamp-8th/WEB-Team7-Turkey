import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/customer/_authed/deliveries/')({
  component: DeliveryHistory,
})

function DeliveryHistory() {
  {/* text-blurred: 개인정보 블러 처리 (globals.css에 정의 필요) */}
  return (
    <div className="text-gray-800 antialiased max-w-md mx-auto min-h-screen flex flex-col">
      {/* BEGIN: MainHeader */}
      <header className="bg-white sticky top-0 z-10 border-b border-gray-200">
        {/* Top App Bar */}
        <div className="flex items-center justify-between px-4 py-3 h-14">
          <button aria-label="Go back" className="p-2 -ml-2 text-gray-700 hover:bg-gray-100 rounded-full transition-colors">
            <svg className="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
              <path d="M10 19l-7-7m0 0l7-7m-7 7h18" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"></path>
            </svg>
          </button>
          <h1 className="text-lg font-semibold flex-1 text-center pr-8">이용기록</h1>
          <button aria-label="Help" className="p-2 -mr-2 text-gray-500 hover:bg-gray-100 rounded-full transition-colors">
            <svg className="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
              <path d="M8.228 9c.549-1.165 2.03-2 3.772-2 2.21 0 4 1.343 4 3 0 1.4-1.278 2.575-3.006 2.907-.542.104-.994.54-.994 1.093m0 3h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5"></path>
            </svg>
          </button>
        </div>
        {/* Tabs */}
        <nav className="flex px-4 gap-6 text-sm font-medium text-gray-500">
          <a className="py-3 text-black border-b-2 border-black font-semibold" href="#">전체</a>
          <a className="py-3 hover:text-gray-800 transition-colors" href="#">개인</a>
          <a className="py-3 hover:text-gray-800 transition-colors" href="#">가족계정</a>
        </nav>
      </header>
      {/* END: MainHeader */}
      {/* BEGIN: MainContent */}
      <main className="flex-1 p-4 space-y-4">
        {/* Usage History Card */}
        <article className="bg-white rounded-2xl p-5 shadow-md relative">
          {/* Close Button */}
          <button aria-label="Close" className="absolute top-4 right-4 text-gray-400 hover:text-gray-600 transition-colors">
            <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
              <path d="M6 18L18 6M6 6l12 12" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"></path>
            </svg>
          </button>
          {/* Card Header */}
          <div className="flex items-center gap-3 mb-5">
            <div className="w-10 h-10 bg-orange-100 rounded-full flex items-center justify-center text-xl">
              📦
            </div>
            <h2 className="text-lg font-bold flex items-center gap-1 text-gray-900">
              퀵·배송
              <svg className="h-4 w-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                <path d="M9 5l7 7-7 7" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"></path>
              </svg>
            </h2>
          </div>
          {/* Card Details */}
          <div className="space-y-2.5 text-[15px]">
            <div className="flex">
              <span className="w-20 text-gray-500 shrink-0">날짜</span>
              <span className="text-gray-900 font-medium">25.12.24</span>
            </div>
            <div className="flex">
              <span className="w-20 text-gray-500 shrink-0">배송상태</span>
              <span className="text-gray-900 font-medium">배송완료</span>
            </div>
            <div className="flex">
              <span className="w-20 text-gray-500 shrink-0">배송방법</span>
              <span className="text-gray-900 font-medium">오토바이 · 이코노미</span>
            </div>
            <div className="flex">
              <span className="w-20 text-gray-500 shrink-0">출발</span>
              <span className="text-blurred select-none">서울특별시 강남구 테헤란로</span>
            </div>
            <div className="flex">
              <span className="w-20 text-gray-500 shrink-0">도착</span>
              <span className="text-blurred select-none">경기도 성남시 분당구</span>
            </div>
            <div className="flex pt-1">
              <span className="w-20 text-gray-500 shrink-0">금액</span>
              <span className="text-gray-900 font-bold">100원</span>
            </div>
          </div>
        </article>
      </main>
      {/* END: MainContent */}
    </div>
  )
}
