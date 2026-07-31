import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/customer/_authed/deliveries/$deliveryId/')({
  component: DeliveryDetail,
})

function DeliveryDetail() {
  return (
    <main
      className="w-full max-w-[400px] bg-[#F8F9FA] h-full overflow-y-auto pb-10 shadow-lg relative"
      data-purpose="mobile-container"
    >
      {/* BEGIN: Header */}
      <header className="pt-12 px-6 pb-6" data-purpose="page-header">
        {/* Icon Placeholder */}
        <div className="mb-4">
          <div className="w-12 h-12 bg-yellow-400 rounded flex items-center justify-center text-white font-bold text-lg">
            <svg
              className="h-6 w-6"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
              xmlns="http://www.w3.org/2000/svg"
            >
              <path
                d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4"
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth="2"
              ></path>
            </svg>
          </div>
        </div>
        <div className="flex justify-between items-end">
          <h1 className="text-2xl font-bold text-gray-900 tracking-tight">퀵 이용 상세</h1>
          <span className="text-sm text-gray-500 font-medium tracking-wide">22.09.26</span>
        </div>
      </header>
      {/* END: Header */}
      {/* BEGIN: Main Content Area */}
      <section className="px-4" data-purpose="content-area">
        <div className="bg-white rounded-2xl shadow-sm p-6 space-y-8">
          {/* BEGIN: Order Info */}
          <article data-purpose="order-info">
            <h2 className="text-[17px] font-bold text-gray-900 mb-5">주문 정보</h2>
            <ul className="space-y-[14px] text-[15px]">
              <li className="flex justify-between items-start">
                <span className="text-gray-400 w-24 flex-shrink-0">주문상태</span>
                <span className="text-gray-900 font-medium text-right font-bold">배송완료</span>
              </li>
              <li className="flex justify-between items-start">
                <span className="text-gray-400 w-24 flex-shrink-0">주문번호</span>
                <span className="text-gray-900 font-medium text-right">-</span>
              </li>
              <li className="flex justify-between items-start">
                <span className="text-gray-400 w-24 flex-shrink-0">출발지 이름</span>
                <span className="text-gray-900 font-medium text-right">-</span>
              </li>
              <li className="flex justify-between items-start">
                <span className="text-gray-400 w-24 flex-shrink-0">출발지 주소</span>
                <span className="text-gray-900 font-medium text-right line-clamp-2 break-all">-</span>
              </li>
              <li className="flex justify-between items-start">
                <span className="text-gray-400 w-24 flex-shrink-0">도착지 이름</span>
                <span className="text-gray-900 font-medium text-right">-</span>
              </li>
              <li className="flex justify-between items-start">
                <span className="text-gray-400 w-24 flex-shrink-0">도착지 주소</span>
                <span className="text-gray-900 font-medium text-right line-clamp-2 break-all">-</span>
              </li>
              <li className="flex justify-between items-start mt-2">
                <span className="text-gray-400 w-24 flex-shrink-0">예약일시</span>
                <span className="text-gray-900 font-medium text-right">22.09.26 23:30</span>
              </li>
              <li className="flex justify-between items-start">
                <span className="text-gray-400 w-24 flex-shrink-0">배송시간</span>
                <span className="text-gray-900 font-medium text-right">22:31 - 00:53</span>
              </li>
              <li className="flex justify-between items-start">
                <span className="text-gray-400 w-24 flex-shrink-0">배송방법</span>
                <span className="text-gray-900 font-medium text-right">퀵 이코노미</span>
              </li>
              <li className="flex justify-between items-start">
                <span className="text-gray-400 w-24 flex-shrink-0">보관장소</span>
                <span className="text-gray-900 font-medium text-right">문 앞에 보관</span>
              </li>
            </ul>
          </article>
          {/* END: Order Info */}
          {/* Divider */}
          <hr className="border-gray-100" />
          {/* BEGIN: Pickup Info */}
          <article data-purpose="pickup-info">
            <h2 className="text-[17px] font-bold text-gray-900 mb-5">인수 정보</h2>
            <div className="flex justify-between items-start">
              <span className="text-gray-500 text-[15px]">사진: 비대면 수령</span>
              <div className="w-[84px] h-[84px] bg-gray-200 rounded-lg overflow-hidden border border-gray-100 flex-shrink-0">
                {/* TODO: 배송완료 인수 사진 연결 */}
                <div className="w-full h-full bg-surface-container-high" />
              </div>
            </div>
          </article>
          {/* END: Pickup Info */}
          {/* Divider */}
          <hr className="border-gray-100" />
          {/* BEGIN: Fee Info */}
          <article data-purpose="fee-info">
            <h2 className="text-[17px] font-bold text-gray-900 mb-5">요금 정보</h2>
            <div className="flex justify-between items-center text-[15px]">
              <span className="text-gray-500">배송요금</span>
              <span className="text-gray-900 font-bold">63,100원</span>
            </div>
          </article>
          {/* END: Fee Info */}
        </div>
      </section>
      {/* END: Main Content Area */}
    </main>
  )
}
