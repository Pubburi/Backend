# 본인 계정 장바구니

F10은 PG/JPA에서 계정별 카트를 보관한다. 비회원 기기 카트는 서버에 자동 전송하지 않으며
로그인 뒤 사용자가 명시한 병합을 처리한다. 공개 추천 행동·실제 구매 성과로 쓰지 않는다.

모든 요청은 현재 세션과 `X-Pubburi-Account-Owner: encodeURIComponent(현재 계정 ID)`를 요구한다.
관리자도 다른 계정 카트를 선택할 수 없다. 미로그인·회수된 epoch·관측 계정 불일치는401,
헤더 누락/중복/비canonical 인코딩은400이다. 모든 성공·오류는 `private, no-store`, `Vary: Cookie`다.

## API와 값

모든 성공은 HTTP200의 `ApiResponse.data`이며 일반 응답은 아래 cart다.

```json
{"version":0,"items":[]}
```

items는 productId 오름차순의 `{productId,productName,currentProduct,quantity}` 배열이다.
productName은 담을 때의 이름 snapshot이며 currentProduct는 같은 읽기 snapshot의 현재 Product7
또는 null이다. 상품이 삭제돼도 원래 ID·이름·수량을 보존해 명시적으로 제거할 수 있다.
가격은 currentProduct만 사용하며 null을0이나 옛 가격으로 바꾸지 않는다. 서버가 가격을 고정하는
시점은 checkout의 기존 주문 트랜잭션이다. 기존 `/api/orders` 계약은 변경하지 않는다.

| 메서드 | 경로 | 입력·반환 |
|---|---|---|
| GET | `/api/users/me/cart` | 본인 cart. 미생성 계정은 version0/items[]이며 GET은 쓰지 않음 |
| PUT | `/api/users/me/cart/items/{productId}` | `{quantity,expectedVersion}` 절대 수량 교체·cart 반환 |
| DELETE | `/api/users/me/cart/items/{productId}?expectedVersion=n` | 해당 행 제거·cart 반환 |
| DELETE | `/api/users/me/cart?expectedVersion=n` | 전체 행만 제거·revision/receipt 보존·cart 반환 |
| POST | `/api/users/me/cart/merge` | `{operationId,expectedVersion,items:[{productId,quantity}]}` |
| POST | `/api/users/me/cart/checkout` | merge 필드와 `orderType,orderTable` |
| GET | `/api/users/me/cart/operations/{operationId}` | 본인 기존 receipt 읽기·기존 Result 반환, 없으면404 |

수량은1–99의 정수, 카트와 operation 입력은 최대100행이다. POST items는 비어 있을 수 없다.
중복 productId·문자열/boolean/소수 정수 강제 변환·추가/생략 body 필드·추가/중복 query는400이다.
operationId는 canonical UUID, expectedVersion은0–9007199254740991 정수이며 DELETE query도 필수다.
orderType은 ONLINE/OFFLINE/TAKEOUT 중 하나, orderTable은 필수 nullable 문자열로 최대20 Unicode
code point다. 빈 문자열·공백 원문을 보존하고 NUL·단독 surrogate를 거절한다.
POST/PUT body는64KiB 이내다. 선언 길이 초과413, 길이 없는 stream 상한 초과는 읽기를 중단해400이다.

새 상품 PUT의 현재 상품 부재는404다. merge의 삭제 상품·합산99 초과·카트100행 초과는 전체409이며
부분 합산/cap을 하지 않는다. checkout의 미보유/초과 수량·삭제 상품도 전체409다. stale revision과
같은 operation UUID의 다른 kind/intent는409다. DB 실패를 빈 카트/성공으로 바꾸지 않는다.
빈 계정의 첫 성공 변이는 version1부터 시작하며 모든 성공 PUT/DELETE/clear/새 operation은
no-op이어도 revision을 한 번 올린다. replay와 실패/rollback은 올리지 않는다. revision은 카트
행의 변경 token이며 별도로 바뀌는 현재 상품 DTO의 ETag가 아니다.

## 병합·주문·재시도

두 POST는 `{operationId,replayed,cart,orderId}`를 반환한다. merge의 orderId는 null이며 checkout은
생성한 주문 ID다. 같은 owner/UUID/kind/intent를 다시 보내면 합산/주문/소비를 반복하지 않고
`replayed:true`, 저장된 orderId와 현재 cart를 반환한다. receipt 판별이 revision 검수보다 먼저다.
형식 검증은 재시도에도 유지하며 hash는 정규화한 items·주문 입력에 결속하고 expectedVersion은 제외한다.

응답 유실은 같은 UUID와 같은 intent로 확인한다. 새 UUID를 자동 생성해 재주문하지 않는다.
fresh CAS409는 입력을 보존한 채 현재 cart를 조회하고 명시적으로 다시 실행한다. 늦은 응답은
계정/세션 세대와 cart revision을 확인해 소비하며, 성공 확인 전 비회원 원문을 제거하지 않는다.
미확정 operation이 남은 채 계정/세션 세대가 바뀌면 같은 ID를 보존하고 위 GET으로 먼저 확인한다.
본인 receipt가 있으면 `replayed:true`와 저장 orderId·현재 cart를 반환하고, 타계정/재가입의 부재는404다.
GET은 수량/revision/주문/receipt를 변경하지 않으며 DB 실패를404로 바꾸지 않는다. 부재가 확인돼도
새 계정 수명에 POST를 자동 재생하거나 새 UUID로 자동 보상하지 않고 명시 실행을 요구한다.

회원→cart 잠금·epoch 재검수 후 작업하며 상품은 ID순으로 잠근다. checkout은 기존 주문 생성의
서버 가격 snapshot·업무 이벤트/스탬프와 제출한 수량의 카트 소비·receipt를 같은 TX에 묶는다.
실패하면 전부 rollback하며 전송하지 않은 카트 수량을 지우지 않는다. clear 뒤에도 header/revision과
receipt를 회원 수명까지 보존해 ACK 유실의 재시도가 비운 카트를 부활시키지 않는다.

회원 삭제는 카트/receipt를 CASCADE하고 재가입에 상속하지 않는다. V6은 새 sequence 없이3표를
추가하며 runtime은 필요한 열만 갱신한다. 초기 source import/cutover는 PG-only cart3표가 비어 있어야
한다. MySQL 원본·v1/v2 source는 바꾸지 않고 MySQL baseline의 GET base만404/CART_NOT_SUPPORTED로 기기 카트 지원을 명시하며 다른404/503/401은 대체 모드를 허용하지 않는다. V3–V5 백업은 원형 복구를
유지하며 V6 runtime 시작 전 명시 migration/grant가 필요하다. PG 쓰기 뒤 복구는 cart/receipt까지
전체 보존해야 하며 자동 초기화·원본 덮어쓰기·자동 DB 선택을 제공하지 않는다.
