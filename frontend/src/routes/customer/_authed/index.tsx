import { createFileRoute, Link } from '@tanstack/react-router'
import { ChevronRight, Settings, Truck } from 'lucide-react'
import { useGetCustomerActiveDelivery } from '@/api/generated/customer-delivery/customer-delivery'
import { getCustomerDeliveryStatusLabel } from '@/shared/delivery/status'

export const Route = createFileRoute('/customer/_authed/')({
  component: CustomerHome,
})

function CustomerHome() {
  // ApiResponse<T> 봉투는 훅을 쓰는 쪽이 벗긴다(customInstance 는 axios 레벨만 언랩).
  // 진행 중 배송이 없으면 서버가 200 + data=null 을 준다(#100) — 오류가 아니라 정상 신호다.
  const activeQuery = useGetCustomerActiveDelivery({ query: { retry: false } })
  const active = activeQuery.data?.data ?? null
  const hasActive = active?.deliveryId != null

  return (
    <div className="customer-home">
      <header className="customer-home__header">
        <h1 className="customer-home__title">Quick</h1>
        <Link to="/account/settings" aria-label="설정" className="customer-home__icon-button">
          <Settings size={22} />
        </Link>
      </header>

      <main className="customer-home__main">
        <div className="customer-home__content">
          <div className="customer-home__greeting">
            <h2>반갑습니다!</h2>
            <p>{hasActive ? '진행 중인 배송이 있어요.' : '어디로 보낼까요?'}</p>
          </div>

          {/* TODO: 배송 일러스트 이미지 자산 연결 */}
          <div className="customer-home__hero" aria-hidden="true">
            배송 일러스트
          </div>

          <div className="customer-home__actions">
            {activeQuery.isPending ? (
              <p role="status" className="customer-home__hint">진행 중 배송을 확인하고 있어요…</p>
            ) : activeQuery.isError ? (
              // 조회 실패는 요약 영역에만 알리고 퀵 부르기는 계속 열어 둔다(홈 전체를 오류로 만들지 않음, #208).
              // 진행 중 배송이 실제로 있으면 생성은 서버가 409 로 막는다.
              <>
                <p role="alert" className="customer-home__hint customer-home__hint--error">
                  진행 중 배송을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
                </p>
                <Link to="/customer/deliveries/new" className="btn btn--primary">
                  <Truck size={22} />
                  <span>퀵 부르기</span>
                </Link>
              </>
            ) : hasActive ? (
              // 진행 중 배송이 있으면 퀵 부르기를 없애고 요약 카드만 둔다 — 누르면 추적 화면으로 간다(#208).
              <Link
                to="/customer/deliveries/$deliveryId/tracking"
                params={{ deliveryId: String(active!.deliveryId) }}
                className="customer-home__summary"
                aria-label="진행 중 배송 추적 화면으로 이동"
              >
                <span className="customer-home__summary-status">
                  {getCustomerDeliveryStatusLabel(active!.status)}
                </span>
                <span className="customer-home__summary-route">
                  <span className="customer-home__summary-addr">{active!.pickupRoadAddress ?? '출발지 미확인'}</span>
                  <span className="customer-home__summary-arrow" aria-hidden="true">→</span>
                  <span className="customer-home__summary-addr">{active!.destinationRoadAddress ?? '도착지 미확인'}</span>
                </span>
                <span className="customer-home__summary-cta">
                  실시간 배송 상태 보기
                  <ChevronRight size={18} />
                </span>
              </Link>
            ) : (
              <Link to="/customer/deliveries/new" className="btn btn--primary">
                <Truck size={22} />
                <span>퀵 부르기</span>
              </Link>
            )}
          </div>
        </div>
      </main>
    </div>
  )
}
