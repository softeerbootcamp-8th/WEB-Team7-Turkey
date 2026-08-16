import { createFileRoute, Link } from '@tanstack/react-router'
import { ChevronRight, History, Package, Settings, Truck, WalletCards } from 'lucide-react'
import { useGetCustomerActiveDelivery } from '@/api/generated/customer-delivery/customer-delivery'
import type { ActiveDeliveryResponseItemType } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { getCustomerDeliveryStatusLabel } from '@/shared/delivery/status'
import { formatDeliveryRequestedAt } from './deliveries/-deliveryList'

export const Route = createFileRoute('/customer/_authed/')({
  component: CustomerHome,
})

const ITEM_TYPE_LABELS: Record<ActiveDeliveryResponseItemType, string> = {
  DOCUMENT: '문서',
  SMALL_PARCEL: '소형 소포',
  MEDIUM_PARCEL: '중형 소포',
  LARGE_PARCEL: '대형 소포',
  FOOD: '음식',
}

function getItemTypeLabel(itemType: ActiveDeliveryResponseItemType | undefined): string | null {
  return itemType ? ITEM_TYPE_LABELS[itemType] : null
}

function CustomerHome() {
  // ApiResponse<T> 봉투는 훅을 쓰는 쪽이 벗긴다(customInstance 는 axios 레벨만 언랩).
  // 진행 중 배송이 없으면 서버가 200 + data=null 을 준다(#100) — 오류가 아니라 정상 신호다.
  //
  // refetchOnMount: 'always' — 홈은 "지금 배송 상태"를 정확히 보여야 하는 랜딩이다. 전역
  // staleTime(30초, lib/queryClient.ts) 때문에 추적 화면에서 홈으로 돌아왔을 때 직전에 캐시된
  // "진행 중 없음"이 그대로 남아 새로고침해야 갱신되는 문제가 있었다. 또 라이더가 상태를 바꾸는
  // 전이는 고객 쪽 mutation 이 없어 캐시가 저절로 무효화되지 않는다. 그래서 마운트마다 강제
  // 재조회한다(방문 빈도가 낮은 화면이라 비용도 작다).
  const activeQuery = useGetCustomerActiveDelivery({
    query: { retry: false, refetchOnMount: 'always' },
  })
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

          {/* 배송 일러스트(CSS 컴포지션) — 라이더 홈과 동일한 구성, 아이콘만 택배 박스로. */}
          <div className="customer-home__hero" aria-hidden="true">
            <span className="customer-home__hero-icon">
              <Package size={52} strokeWidth={1.6} />
            </span>
            <span className="customer-home__hero-badge">빠른 배송</span>
          </div>

          <div className="customer-home__actions">
            {activeQuery.isFetching ? (
              // isPending 이 아니라 isFetching 으로 건다 — refetchOnMount 재조회 중에는 직전에
              // 캐시된 상태(예: 진행 중 없음)를 그대로 보여 주면 안 되기 때문이다(잘못된 상태 깜빡임 방지).
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
                <span className="customer-home__summary-header">
                  <span className="customer-home__summary-status">
                    {getCustomerDeliveryStatusLabel(active!.status)}
                  </span>
                  <span className="customer-home__summary-time">
                    접수 {formatDeliveryRequestedAt(active!.requestedAt)}
                  </span>
                </span>
                <span className="customer-home__summary-route">
                  <span className="customer-home__summary-point">
                    <span className="customer-home__summary-label">출발</span>
                    <span className="customer-home__summary-addr">{active!.pickupRoadAddress ?? '출발지 미확인'}</span>
                  </span>
                  <span className="customer-home__summary-point">
                    <span className="customer-home__summary-label">도착</span>
                    <span className="customer-home__summary-addr">{active!.destinationRoadAddress ?? '도착지 미확인'}</span>
                  </span>
                </span>
                {getItemTypeLabel(active!.itemType) && (
                  <span className="customer-home__summary-item">
                    <Package size={14} aria-hidden="true" />
                    {getItemTypeLabel(active!.itemType)}
                  </span>
                )}
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
            {/* 배송내역이 설정(톱니바퀴) 안에만 있어 접근성이 떨어진다는 데모데이 피드백(#534)으로
                메인 화면에도 바로가기를 둔다. 설정 안의 진입점은 유지한다. */}
            <Link to="/customer/deliveries" className="btn btn--secondary">
              <History size={20} />
              <span>배송내역</span>
            </Link>
            <Link to="/customer/points" className="btn btn--secondary">
              <WalletCards size={20} />
              <span>포인트 기록보기</span>
            </Link>
          </div>
        </div>
      </main>
    </div>
  )
}
