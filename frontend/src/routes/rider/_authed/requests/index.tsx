import { createFileRoute } from '@tanstack/react-router'
import { RequestCard } from './-components/RequestCard'

export const Route = createFileRoute('/rider/_authed/requests/')({
  component: RiderRequests,
})

function RiderRequests() {
  return (
    <div className="flex flex-col h-screen w-full max-w-[604px] mx-auto bg-surface-container-lowest font-sans relative pb-16">
      {/* BEGIN: Header */}
      <header className="flex items-center justify-between px-4 py-3 bg-surface-container-lowest">
        <button aria-label="Home" className="p-2 text-on-surface">
          <span className="material-symbols-outlined text-2xl">home</span>
        </button>
        <button aria-label="Menu" className="p-2 text-on-surface">
          <span className="material-symbols-outlined text-2xl">menu</span>
        </button>
      </header>
      {/* END: Header */}
      {/* BEGIN: Filter Tabs */}
      <div className="px-4 py-2 flex gap-2 overflow-x-auto no-scrollbar bg-surface-container-lowest">
      </div>
      {/* END: Filter Tabs */}
      {/* BEGIN: Banner */}
      {/* END: Banner */}
      {/* BEGIN: Main Content */}
      <main aria-label="배송요청 목록" className="flex-1 overflow-y-auto pb-20 bg-surface">
        {/* Top Highlighted Order */}
        <div className="p-4">
          <div className="bg-white rounded-xl shadow-sm border border-outline-variant p-3 flex relative">
            <button aria-label="닫기" className="absolute left-2 top-1/2 -translate-y-1/2 text-outline">
              <span className="material-symbols-outlined">close</span>
            </button>
            <div className="ml-6 flex-1">
              <div className="flex items-center gap-2 mb-1">
                <span className="text-sm text-on-surface-variant">마포</span>
                <span className="text-lg font-bold">도화</span>
                <span className="text-sm text-outline">3.2km</span>
              </div>
              <div className="flex items-center gap-2 mb-2">
                <span className="text-sm text-on-surface-variant">마포</span>
                <span className="text-lg font-bold">용강</span>
                <span className="text-sm text-outline">400m</span>
              </div>
              <div className="flex items-center gap-1">
                <span className="text-xs bg-teal-100 text-teal-700 px-1 rounded">퀵</span>
                <span className="text-sm text-on-surface-variant">초소형</span>
              </div>
            </div>
            <button className="bg-teal-600 text-white rounded-lg px-4 py-2 flex flex-col items-center justify-center min-w-[100px]">
              <span className="text-lg font-bold flex items-center gap-1">5,259 <span className="text-primary text-xs bg-black rounded-full w-4 h-4 flex items-center justify-center font-normal">P</span></span>
              <span className="text-sm">수락</span>
            </button>
          </div>
        </div>
        {/* List Controls */}
        <div className="flex items-center justify-between px-4 py-2 bg-white border-b-thin">
          <div className="flex gap-2">
            <button className="px-3 py-1.5 bg-surface text-sm text-on-surface rounded-md">리스트 설정</button>
            <button className="px-3 py-1.5 bg-surface text-sm text-on-surface rounded-md flex items-center gap-1">추천순 <span className="material-symbols-outlined text-base text-outline">expand_more</span></button>
            <button className="px-3 py-1.5 bg-surface text-sm text-on-surface rounded-md flex items-center gap-1">3km <span className="material-symbols-outlined text-base text-outline">expand_more</span></button>
          </div>
          <button aria-label="도움말" className="text-outline"><span className="material-symbols-outlined">help</span></button>
        </div>
        {/* Order List */}
        <div className="bg-white">
          <RequestCard />
          <RequestCard />
          <RequestCard />
        </div>
      </main>
      {/* END: Main Content */}
      {/* BEGIN: Bottom Overlay Buttons */}
      <div className="fixed bottom-16 left-1/2 -translate-x-1/2 flex gap-2 bg-blue-50/90 backdrop-blur-sm px-4 py-2 rounded-full border border-blue-100 shadow-sm z-10 w-fit">
        <div className="w-px h-4 bg-outline-variant my-auto"></div>
        <div className="w-px h-4 bg-outline-variant my-auto"></div>
      </div>
      {/* END: Bottom Overlay Buttons */}
      {/* BEGIN: Bottom Navigation */}
      <nav className="fixed bottom-0 left-0 right-0 h-16 bg-surface-container-lowest border-t border-outline-variant flex max-w-[604px] mx-auto z-20">
        <button className="flex-1 flex items-center justify-center font-bold text-blue-600 bg-white">새로 고침</button>
      </nav>
      {/* END: Bottom Navigation */}
    </div>
  )
}
