import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/rider/requests/$deliveryId/')({
  component: RiderRequestDetail,
})

function RiderRequestDetail() {
  return (
    <main
      aria-label="배송요청 상세"
      className="relative flex flex-col w-full max-w-[414px] h-screen mx-auto bg-white overflow-hidden shadow-xl"
    >
      {/* BEGIN: Map Area & Header */}
      <div className="relative h-[45%] bg-surface-container-high">
        {/* TODO: 지도 연결 */}
        {/* Back Button */}
        <button className="absolute top-4 left-4 z-10 w-12 h-12 bg-white rounded-full flex items-center justify-center shadow-md">
          <svg className="h-6 w-6 text-gray-800" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
            <path d="M10 19l-7-7m0 0l7-7m-7 7h18" strokeLinecap="round" strokeLinejoin="round"></path>
          </svg>
        </button>
      </div>
      {/* END: Map Area & Header */}
      {/* BEGIN: Bottom Sheet Order Details */}
      <div className="flex-1 bg-white rounded-t-[24px] -mt-5 z-20 relative flex flex-col shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.05)]">
        {/* Drag Handle Placeholder */}
        <div className="flex justify-center pt-3 pb-4">
          <div className="w-12 h-1.5 bg-gray-300 rounded-full"></div>
        </div>
        <div className="flex-1 overflow-y-auto pb-20 px-5">
          {/* Header: Quick Biz Info */}
          <div className="flex items-center space-x-2 mb-6 text-sm text-gray-600">
            <span className="text-teal-500 font-bold bg-teal-50 px-1.5 py-0.5 rounded">퀵</span>
            <span className="font-medium">비즈</span>
            <svg className="h-4 w-4 text-gray-400" fill="currentColor" viewBox="0 0 20 20" xmlns="http://www.w3.org/2000/svg">
              <path clipRule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" fillRule="evenodd"></path>
            </svg>
          </div>
          {/* Locations Route List */}
          <div className="relative pl-6 border-l-2 border-dashed border-gray-200 mb-6 space-y-6">
            {/* Pickup Location */}
            <div className="relative">
              {/* Pickup Dot */}
              <div className="absolute -left-[29px] top-1.5 w-3 h-3 bg-[#4A74E8] rounded-full border-2 border-white shadow-sm"></div>
              <div className="flex justify-between items-start">
                <div>
                  <h3 className="text-lg font-bold text-gray-900 leading-tight">서울 강남구 역삼1동</h3>
                  <p className="text-gray-500 text-sm mt-1">동방미인</p>
                </div>
                <div className="text-right">
                  <span className="text-[#4A74E8] font-bold">픽업 7.6km</span>
                </div>
              </div>
            </div>
            {/* Delivery Location */}
            <div className="relative">
              {/* Delivery Dot */}
              <div className="absolute -left-[29px] top-1.5 w-3 h-3 bg-[#7D4CDb] rounded-full border-2 border-white shadow-sm"></div>
              <div className="flex justify-between items-start">
                <div>
                  <h3 className="text-lg font-bold text-gray-900 leading-tight">서울 용산구 한남동</h3>
                  <p className="text-gray-500 text-sm mt-1">한남더힐아파트</p>
                </div>
                <div className="text-right">
                  <span className="text-[#7D4CDb] font-bold block">배송 4.1km</span>
                  <span className="text-gray-500 text-xs mt-1 block">14:04까지 배송</span>
                </div>
              </div>
            </div>
          </div>
          {/* Item Information Box */}
          <div className="bg-gray-50 rounded-[8px] p-4 mb-4 flex items-center text-sm">
            <span className="text-gray-500 w-20">물품 정보</span>
            <span className="font-bold text-gray-900 mr-2">소형</span>
            <span className="text-gray-600">세 변의 합 100cm · 5kg 이하</span>
          </div>
          {/* Earnings Information Box */}
          <div className="bg-gray-50 rounded-[8px] p-5">
            <div className="flex justify-between items-center mb-4">
              <h2 className="text-lg font-bold text-gray-900">최종 수익</h2>
              <div className="flex items-center space-x-1">
                <span className="text-2xl font-bold text-gray-900">6,730</span>
                <div className="w-6 h-6 bg-yellow-400 rounded-full flex items-center justify-center text-white font-bold text-sm">P</div>
              </div>
            </div>
            <div className="space-y-2 text-sm">
              <div className="flex justify-between text-gray-600">
                <span className="">배송비</span>
                <span className="">6,230P</span>
              </div>
              <div className="flex justify-between text-gray-600">
                <span className="">프로모션</span>
                <span className="">500P</span>
              </div>
            </div>
          </div>
        </div>
        {/* BEGIN: Fixed Bottom Action Buttons */}
        <div className="absolute bottom-0 left-0 right-0 flex h-16">
          <button className="w-1/3 bg-gray-500 hover:bg-gray-600 text-white font-bold text-lg transition-colors">
            넘기기
          </button>
          <button className="w-2/3 bg-[#FEE500] hover:bg-yellow-400 text-black font-bold text-xl transition-colors relative flex items-center justify-center">
            수락하기
            {/* Simulated slider circle from the image */}
          </button>
        </div>
        {/* END: Fixed Bottom Action Buttons */}
      </div>
      {/* END: Bottom Sheet Order Details */}
    </main>
  )
}
