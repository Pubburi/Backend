# 본인 서비스 내부 알림 — F12

명시 PostgreSQL 런타임은 데모 주문의 관리자 완료 처리와 관리자의 타인 후기 삭제를 같은
업무 트랜잭션에서 알림으로 저장한다. 사용자 임의 생성 API·외부 push·메일은 제공하지 않는다.

| 사건 | 생성 조건 | 보존 내용 |
|---|---|---|
| `ORDER_COMPLETED` | 현재 관리자가 현존 주문의 `completed != 'Y'`를 Y로 바꿀 때 현재 소유자가 있음 | 원 주문 ID |
| `REVIEW_REMOVED` | 현재 관리자가 자신이 작성하지 않은 현존 후기를 명시 삭제 | 원 후기 사건 ID·상품 ID·당시 상품명 원문 |

기존 null 완료 상태도 새 완료 전이이며 소유자가 있으면 알림을 생성한다. 이미 Y인 주문의
반복 호출은 성공하지만 쓰기·새 알림이 없다. 과거 완료 주문을 소급 생성하지 않는다. 본인 후기
삭제와 상품/회원 cascade는 알림을 생성하지 않는다. 후기 본문·관리자 이름은 알림에 복제하지 않는다.

현재 actor의 역할·epoch와 수신자 계정을 잠그고 원 업무 행을 다시 확인한다. 원 FK의 대소문자 등
표기가 달라도 DB가 반환한 canonical PK로 본인·수신자를 판단하며 원문 FK를 다시 쓰지 않는다.
이 canonical ID를 정렬·중복 제거한 뒤 잠그고 현재 수신자를 재확인한다. 사용자 잠금은 ID순,
그 뒤 주문/후기 잠금 순이다. 한 회원/사건 종류/원 ID의 unique 제약으로 중복을 막고 알림 저장
실패에는 완료/후기 삭제도 rollback한다. 클라이언트 화면 진입만으로 읽음 처리하지 않는다.

## 본인 API

세 API는 현재 세션의 본인만 사용한다. `X-Pubburi-Account-Owner`는 현재 canonical ID의
`encodeURIComponent` 결과를 정확히 하나 요구한다. 계정을 선택하는 값이 아니며 누락/잘못된
인코딩은400, 세션과 불일치는401이다. 비로그인/만료 epoch도401이다. 관리자 타인 읽기 예외는 없다.
모든 성공·실패는 `private, no-store`, `Vary: Cookie`다. 본문은 기존64KiB private 요청 경계를 따른다.

`GET /api/users/me/notifications?page=1&size=20`의 ApiResponse `data`:

```json
{
  "items": [{
    "id": "00000000-0000-4000-8000-000000000001",
    "type": "ORDER_COMPLETED",
    "createdAt": 1794110400000,
    "readAt": null,
    "orderId": 1,
    "productId": null,
    "productName": null,
    "targetAvailable": true
  }],
  "page": 1, "size": 20, "total": 1, "totalPages": 1, "hasNext": false,
  "unreadCount": 1
}
```

기존 PageResponse 여섯 필드에 **계정 전체**의 unreadCount를 더한다. 같은 짧은 RR에서 목록·
총수·미읽음 수·현재 링크 가능 여부를 읽는다. 생성 시각 내림차순·동점 UUID 내림차순이다.
page는 양정수, size는1–100이며 기본1/20이다. offset이 int 범위를 넘으면400이다. 중복/알 수
없는 query·숫자 문자열 변형은400이다. null 필드를 생략하지 않고 시각은 epoch milliseconds다.

`PUT /api/users/me/notifications/{uuid}/read`는 본문/query 없이 해당 한 건을 명시 읽음 처리한다.
`POST /api/users/me/notifications/read`는 정확한 `{"ids":["canonical-uuid"]}`만 받는다.
ids는1–100개의 중복 없는 canonical 소문자 UUID다. 빈 목록·중복·추가 필드·중복 JSON key·
다른 타입은400이다. 보이는 페이지의 ID를 제출하며 미래 알림까지 읽는 read-all 의미가 아니다.

두 읽음 API의 성공은200, data는 `{"items":[{"id":"canonical-uuid","readAt":1794110400000}],"unreadCount":0}`다.
items는 요청 ID순이다. 현재 계정에 하나라도 없으면(타인 ID 포함)404이고 전체가 변경되지 않는다.
반복/동시 요청은 최초 readAt을 보존하며 새 사건과 읽음을 계정 잠금으로 직렬화한다.
응답 직전에도 현재 세션을 다시 확인하므로 후행401이 이미 저장한 읽음을 취소한다는 뜻은 아니다.
같은 ID의 읽음 재시도는 멱등이다. DB 장애는500이며 빈 목록/404로 숨기지 않는다.

## 대상·계정 수명과 로컬 이전

주문/상품 ID와 당시 상품명은 사건 snapshot이며 원 대상이 삭제돼도 알림을 보존한다.
`targetAvailable`은 조회 시점의 현재 본인 주문/현재 상품 존재만 뜻한다. false면 링크를 만들지
않고 보존된 내용을 표시한다. 상품명 변경에도 snapshot 원문을 바꾸지 않는다. 회원 삭제는
알림을 cascade하며 동일 ID 재가입은 과거 알림/UUID를 상속하지 않는다.

Flyway V7의 `t_user_notification` 한 표와 UUID를 사용하고 기존7개 sequence를 늘리지 않는다.
runtime은 SELECT/INSERT와 UPDATE(read_at)만 갖고 키·사건·snapshot·생성 시각 수정/삭제 권한은
없다. 기존 V3–V6 복구 인벤토리를 보존하고 V7의 알림·읽음도 새 PG 복구에 포함한다.
초기 MySQL source/import/cutover는 PG 전용 알림이 있으면 거절하며 원본을 덮어쓰지 않는다.

명시 MySQL baseline은 알림을 생성/저장하지 않는다. 오직 base GET의404
`error.code="NOTIFICATIONS_NOT_SUPPORTED"`가 미지원 신호이며 일반404/500을 같은 의미로
처리하지 않는다. 신규 API/fixture 성공은 전체 DB06 전환 완료가 아니다.
