#!/usr/bin/env python3
"""라이더 콜 목록(정렬 3종·필터 4종·페이지네이션) 시연용 WAITING 주문 시딩 SQL 생성기.

회원가입 API를 거치지 않고 SQL을 직접 만든다 — 원격(배포) 환경은 회원가입 API의 인증번호
debugCode가 local 프로파일에서만 채워져 API로는 계정을 못 만들기 때문이다(backend/loadtest의
ec2-seed.sh와 같은 이유). 이 스크립트는 부하테스트가 아니라 데모 데이터 시딩 전용이라
backend/loadtest/와는 완전히 분리해 둔다.

두 좌표 클러스터(교육장·양재사옥)를 매번 같이 만든다 — 라이더 콜 목록은 "지금 라이더 위치"
기준으로 픽업 거리 필터가 걸리므로, 실행하는 사람이 어느 위치에 있든 그 위치 기준 데이터만
자연스럽게 필터에 걸리고 나머지는 조용히 무시된다. 그래서 로컬/원격을 구분하는 것과 별개로,
위치는 파라미터화하지 않는다.

같은 고객이 진행 중(WAITING~DELIVERING) 배송요청을 2건 이상 가질 수 없다(uk_delivery_active_
customer)는 도메인 제약 때문에, 주문 하나마다 고객 계정을 하나씩 새로 만든다.

사용법:
    python3 generate_call_list_seed.py --run-id 20260820153000 --count-per-cluster 16 > seed.sql

--run-id 를 생략하면 현재 시각(초)으로 자동 생성한다 — 여러 번 실행해도(오늘 검증용, 내일 실제용)
계정명이 겹치지 않게 하기 위함이다.
"""

import argparse
import hashlib
import math
import time
import uuid

# bcrypt("loadtest1234") — backend/loadtest/remote/ec2-seed.sh 가 쓰는 해시를 그대로 재사용한다
# (해시는 환경 무관, 알고리즘·평문만 같으면 어디서 만들었든 유효하다). 이 데모 계정으로 실제
# 로그인할 일은 없지만 형식은 맞춰 둔다.
PASSWORD_HASH = "$2a$10$4d5LgvscIbN0TaAuQ9Afk.0HA9JxCDlL6f02CC9k/3Vtiq4EBXMA6"

ITEM_TYPES = ["DOCUMENT", "SMALL_PARCEL", "MEDIUM_PARCEL", "LARGE_PARCEL", "FOOD"]

# 픽업 좌표를 중심에서 얼마나 떨어뜨릴지(미터) — RADIUS_OPTIONS(1/3/5/10km)마다 걸리는 주문이
# 다르게 나오도록 각 구간을 넘나드는 값으로 고른다.
PICKUP_OFFSETS_M = [400, 900, 1800, 2800, 4500, 8000]
BEARINGS = [(1, 0), (0, 1), (-1, 0), (0, -1)]  # 북/동/남/서

# 픽업→도착지 거리(미터) — DISTANCE_MAX_OPTIONS(5/10/20km, 전체)마다 걸리는 주문이 다르게
# 나오도록 각 구간을 넘나드는 값으로 고른다. straight_distance_meters에 그대로 쓴다.
DELIVERY_DISTANCES_M = [3000, 7000, 14000, 25000]

# 예상 요금(P) — FARE_MIN_OPTIONS(10,000/30,000/50,000 이상, 전체)마다 걸리는 주문이 다르게
# 나오도록 각 구간을 넘나드는 값으로 고른다. base_fare(3000) + distance_fare = total_fare
# (order_fare_snapshot의 ck_order_fare_total CHECK를 만족해야 한다).
TOTAL_FARES = [4000, 8000, 15000, 22000, 32000, 45000, 58000, 72000]
BASE_FARE = 3000

CLUSTERS = [
    # (라벨, 위도, 경도, 사람이 읽을 이름)
    ("edu", 37.4913, 127.0315, "강남대로62길 23(교육장)"),
    ("hyundai", 37.4640, 127.0423, "현대자동차 양재사옥"),
]


def offset_latlng(lat, lng, bearing, distance_m):
    """중심 좌표에서 bearing 방향으로 distance_m 만큼 떨어진 좌표(위도/경도)를 반환한다."""
    dlat_m, dlng_m = bearing
    lat_delta = (dlat_m * distance_m) / 111_320
    lng_delta = (dlng_m * distance_m) / (111_320 * math.cos(math.radians(lat)))
    return round(lat + lat_delta, 7), round(lng + lng_delta, 7)


def fake_phone_number(seed_text):
    """member.phone_number는 UNIQUE만 걸려 있고 형식 CHECK가 없다(SQL 직접 INSERT라 회원가입
    API의 형식 검증을 안 거친다) — ec2-seed.sh와 같은 방식으로 결정적 해시 기반 값을 쓴다."""
    return "d" + hashlib.md5(seed_text.encode()).hexdigest()[:15]


def build_sql(run_id, count_per_cluster):
    lines = []
    lines.append(f"-- 콜 목록 시연용 WAITING 주문 시딩 (run_id={run_id})")
    # mysql CLI 접속 기본 문자셋이 DB(utf8mb4)와 달리 latin1인 환경이 있다(docker exec로 직접
    # 확인함) — 이걸 안 맞추면 한글이 latin1로 잘못 해석돼 깨진 채로 저장된다. 클라이언트
    # 플래그(--default-character-set)로도 방어하지만, 이 SQL 자체에도 박아 둔다.
    lines.append("SET NAMES utf8mb4;")
    lines.append(f"SET @hash = '{PASSWORD_HASH}';")
    lines.append("")
    lines.append("-- 활성 요금 정책이 없으면 하나 만든다(uk_fare_policy_active라 있으면 건드리지 않는다).")
    lines.append("INSERT INTO fare_policy (")
    lines.append("    policy_version, base_fare, distance_unit_meters, distance_unit_fare,")
    lines.append("    max_delivery_distance_meters, status, effective_from")
    lines.append(")")
    lines.append("SELECT 'demo-seed-v1', 3000, 100, 130, 30000, 'ACTIVE', '2026-01-01 00:00:00.000'")
    lines.append("FROM DUAL")
    lines.append("WHERE NOT EXISTS (SELECT 1 FROM fare_policy WHERE status = 'ACTIVE');")
    lines.append("")
    lines.append("SELECT fare_policy_id, policy_version INTO @fp_id, @fp_version")
    lines.append("  FROM fare_policy WHERE status = 'ACTIVE' LIMIT 1;")
    lines.append("")

    for cluster_label, center_lat, center_lng, cluster_name in CLUSTERS:
        lines.append(f"-- === {cluster_name} 클러스터 ({count_per_cluster}건) ===")
        for i in range(1, count_per_cluster + 1):
            item_type = ITEM_TYPES[(i - 1) % len(ITEM_TYPES)]
            pickup_offset = PICKUP_OFFSETS_M[(i - 1) % len(PICKUP_OFFSETS_M)]
            bearing = BEARINGS[(i - 1) % len(BEARINGS)]
            pickup_lat, pickup_lng = offset_latlng(center_lat, center_lng, bearing, pickup_offset)

            delivery_distance = DELIVERY_DISTANCES_M[(i - 1) % len(DELIVERY_DISTANCES_M)]
            # 도착지는 픽업지 기준으로 배송거리만큼 반대 방향(북동 45도)에 둔다 —
            # straight_distance_meters로 저장하는 값과 지도상 위치가 대략 맞아 보이게 하기 위함.
            dest_lat, dest_lng = offset_latlng(
                pickup_lat, pickup_lng, (1, 1), delivery_distance
            )

            total_fare = TOTAL_FARES[(i - 1) % len(TOTAL_FARES)]
            distance_fare = total_fare - BASE_FARE

            cust_login = f"demo_cust_{run_id}_{cluster_label}_{i}"
            request_key = str(uuid.uuid4())
            phone = fake_phone_number(f"{run_id}-{cluster_label}-{i}")

            lines.append(
                f"INSERT INTO member (login_id, password_hash, name, phone_number, role, status) "
                f"VALUES ('{cust_login}', @hash, '데모고객{cluster_label}{i}', '{phone}', 'CUSTOMER', 'ACTIVE');"
            )
            lines.append(
                f"INSERT INTO point_wallet (member_id, balance) "
                f"SELECT member_id, 1000000 FROM member WHERE login_id = '{cust_login}';"
            )
            lines.append(
                "INSERT INTO delivery_order ("
                "customer_id, status, request_key, item_type, straight_distance_meters, "
                "pickup_road_address, pickup_detail_address, pickup_postal_code, pickup_latitude, pickup_longitude, "
                "destination_road_address, destination_detail_address, destination_postal_code, destination_latitude, destination_longitude, "
                "sender_name, sender_phone_number, recipient_name, recipient_phone_number"
                ")\n"
                f"SELECT member_id, 'WAITING', '{request_key}', '{item_type}', {delivery_distance}, "
                f"'{cluster_name} 인근 픽업지 {i}', '{i}층', '06236', {pickup_lat}, {pickup_lng}, "
                f"'{cluster_name} 인근 도착지 {i}', '{i}동 {i}호', '05551', {dest_lat}, {dest_lng}, "
                f"'데모고객{cluster_label}{i}', '{phone}', '데모수령인{cluster_label}{i}', '{phone}'\n"
                f"FROM member WHERE login_id = '{cust_login}';"
            )
            lines.append(
                "INSERT INTO order_fare_snapshot ("
                "order_id, fare_policy_id, fare_type, policy_version, calculation_distance_meters, "
                "base_fare, distance_fare, item_surcharge, total_fare"
                ")\n"
                f"SELECT o.order_id, @fp_id, 'ESTIMATE', @fp_version, {delivery_distance}, "
                f"{BASE_FARE}, {distance_fare}, 0, {total_fare}\n"
                "FROM delivery_order o JOIN member c ON c.member_id = o.customer_id "
                f"WHERE c.login_id = '{cust_login}';"
            )
            lines.append("")

    total = count_per_cluster * len(CLUSTERS)
    lines.append(f"-- 완료: 총 {total}건 (클러스터당 {count_per_cluster}건 x {len(CLUSTERS)}개 클러스터)")
    lines.append(
        "SELECT COUNT(*) AS seeded_waiting_orders FROM delivery_order "
        f"WHERE customer_id IN (SELECT member_id FROM member WHERE login_id LIKE 'demo\\_cust\\_{run_id}\\_%');"
    )
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", default=str(int(time.time())), help="계정명 충돌 방지용 실행 ID (기본: 현재 시각 초)")
    parser.add_argument("--count-per-cluster", type=int, default=16, help="클러스터(위치)당 생성할 주문 수 (기본 16)")
    args = parser.parse_args()

    print(build_sql(args.run_id, args.count_per_cluster))


if __name__ == "__main__":
    main()
