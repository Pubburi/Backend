# 본인 계정 장바구니

F10은 PG/JPA에서 계정별 카트를 보관한다. 비회원 기기 카트는 서버에 자동 전송하지 않으며
로그인 뒤 사용자가 명시한 병합을 처리한다. 공개 추천 행동·실제 구매 성과로 쓰지 않는다.

모든 요청은 현재 세션과 `X-Pubburi-Account-Owner: encodeURIComponent(현재 계정 ID)`를 요구한다.
관리자도 다른 계정 카트를 선택할 수 없다. 미로그인·회수된 epoch·관측 계정 불일치는401,
헤더 누락/중복/비canonical 인코딩은400이다. 모든 성공·오류는 `private, no-store`, `Vary: Cookie`다.

## API와 값

모든 성공은 HTTP200의 `ApiResponse.data`이며 카트 조회/단일 행 변경의 응답은 아래 cart다.

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
| POST | `/api/users/me/cart/operations/{operationId}/resolve` | `{kind,items,orderType,orderTable}`로 원 intent 결과 확인 또는 영속 retirement |
| POST | `/api/users/me/cart/recommendations` | `{expectedVersion,limit?}`로 현재 카트의 검수 맥락 추천 조회·아래 별도 응답 |

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

## 현재 카트의 검수 맥락 추천

추천 POST는 현재 계정 헤더와 필수 `expectedVersion`, 선택 `limit`(정수1–12, 기본4)을 받는다.
query·추가/중복 JSON·boolean/문자열/소수/범위 밖 revision 또는 limit은400이다.
클라이언트의 상품 ID·수량·계정·취향·카탈로그는 받지 않는다. 서버의 현재 카트를 읽기만 하며
카트 header/행/revision·receipt·주문·금액을 생성하거나 변경하지 않는다.

응답은 정확히 `source,catalogScope,cartVersion,references,unavailableReason,result,products`다.
`source`는 PROVIDER/UNAVAILABLE, scope는 REVIEWED_DEMO 또는 REVIEWED_LOCAL_CATALOG이며
family-browse를 판매 SKU로 승격하지 않는다. 로컬 권위/현재 시연 가격·삭제의 조합과 두 원 pin은
[검수 catalog 계약](CONTENT-RECOMMENDATIONS.md#안정-원천과-현재-업무-값의-분리)을 따른다.
`references`는 기존 GET 행순서 전체의 `{productId,name,quantity,state}`다.
name은 현재 이름이며 삭제 시 null이다. quantity는 현재1–99 정수로 보존하되 점수에 가중하지 않는다.

| state | 뜻 |
|---|---|
| READY | 현재 검수 container catalog에 있고 available인 기준 |
| DELETED | 현재 상품이 삭제됨; name=null |
| UNREVIEWED | 현재 상품은 있으나 승인 catalog에 매핑되지 않음 |
| UNAVAILABLE | 매핑된 catalog의 available=false |
| CATALOG_UNAVAILABLE | 승인 container catalog가 미설정/만료/거절됨 |

빈 카트·20행 초과·삭제/미검수/불가 기준을 잘라내거나 일부만 성공시키지 않는다. 모든 행이 READY인
1–20개일 때만 기존 [R07 제공자](https://github.com/Pubburi/Recommendation/blob/main/docs/CONTEXT-API.md)에
고유 기준 ID를 전송한다. 수량·계정·cookie·취향/후기 원문을 보내지 않는다. 저장 hard 가격/도수
조건과 현재 본인의 관심 없음에 속한 검수 SKU 그룹은 후보 제외로 유지한다. 관심 없음 기준도
맥락 사실로 읽을 수 있지만 해당 그룹을 결과로 반환하지 않는다. 모든 알려진 카트 기준 그룹도 제외한다.

UNAVAILABLE의 result는 null, products는[]이며 B0를 자동 삽입하지 않는다. 사유 우선순위는
`EMPTY_CART → REFERENCE_LIMIT → DELETED_ITEMS → CATALOG_UNAVAILABLE → UNREVIEWED_ITEMS →
UNAVAILABLE_REFERENCES → PROVIDER_UNAVAILABLE`다. 정상 PROVIDER는 unavailableReason=null이며
원래 R07 result와 items 같은 순서의 현재 Product7을 반환한다. 정상 빈 items/fallback_reason도
PROVIDER로 구분한다. matches의 실제 winning reference와 R04 comparison/기여·필수조건을 검수한다.
이 결과는 한 기준과 사실이 비슷한 대안이며 전체 카트의 조합 적합성·공동구매 통계가 아니다.

호출 전후 별도 짧은 RR로 현재 cart·저장 hard 조건·관심 없음·전체 검수 상품 binding을 확인한다.
원격 호출 중 DB TX/회원 잠금을 유지하지 않는다. revision/수량·상품/가격/특성·조건/관심 없음·bundle
변화는409, 계정/epoch 회수는401이며 원래 카트는 보존한다. provider/strict 응답 실패는 위
UNAVAILABLE, DB 오류는500이다. 최신 읽기 이후의 수정을 잠그는 보장은 아니며 주문 가격은
기존 checkout 시점에 확정한다. 연결200ms·원격 전체3초와 요청512KiB/응답256KiB 상한을 재사용한다.
본인 주문 맥락은 아래의 별도 조회로 제공한다.

## 현재 본인 데모 주문의 맥락 추천

`POST /api/users/me/orders/{id}/recommendations`는 같은 본인 헤더와 `{limit?}`만 받는다.
limit은 정수1–12/기본4이며 추가·중복 body/query·강제 타입 변환은400이다. 클라이언트가 주문
상품/수량·계정·snapshot을 선택하지 않는다. 본인 현재 epoch와 DB가 해석한 canonical 소유자를
확인하며 다른 계정/삭제된 소유자의 주문은 관리자에게도404다. 기존 일반 관리자 주문 조회는 유지한다.
기존 단건 주문 상세도 DB canonical 소유 ID를 반환해 본인 상세의 정확한 ID 검수를 통과시킨다.
case/accent alias인 원 FK와 과거 금액/이력은 DB에 그대로 보존한다.

정확한 응답은 `source,catalogScope,orderId,referenceCount,references,unavailableReason,result,products`다.
referenceCount는 전체 주문 상세 행 수다. 20행을 넘으면 상세를 일부 읽거나 잘라내지 않고 정확한
전체 수·references=[]·REFERENCE_LIMIT를 반환한다. 0행은 EMPTY_ORDER다. 1–20행의 references는
원 상세 ID순 `{detailId,productId,name,quantity,state}`이며 quantity는 원 양의 정수다(99로 줄이지 않음).
name은 현재 상품명이고 삭제된 productId/name은 null·DELETED다. 과거 주문 이름/가격/금액은
기존 주문 상세에 보존하며 현재 사실이나 모델 입력으로 복구하지 않는다.

모든 행이 READY일 때만 고유 현재 상품 ID를 기존 context 제공자에 전달한다. 같은 상품의 여러
상세 행과 수량은 원 응답에 보존하지만 모델 가중치로 합산하지 않는다. state와 scope는 위 카트
계약을 재사용하며 사유 순서는 `EMPTY_ORDER → REFERENCE_LIMIT → DELETED_ITEMS →
CATALOG_UNAVAILABLE → UNREVIEWED_ITEMS → UNAVAILABLE_REFERENCES → PROVIDER_UNAVAILABLE`다.
현재 hard 조건·관심 없음 상품군·모든 기준 상품군 제외, 실제 winning reference/R04 검수도 같다.

원격 호출 앞뒤의 별도 짧은 RR에서 소유/epoch·주문/상세·현재 상품/가격/특성·hard/negative와
원문 pin을 대조한다. 호출 중 완료/삭제를 포함한 주문 변화는409, 세션 회수는401이며 DB 오류를
정상 빈 추천으로 바꾸지 않는다. 주문·가격·카트·stamp·receipt·업무 사건은 전혀 쓰지 않는다.
정상 빈 provider 결과와 UNAVAILABLE를 구분하고 B0로 꾸미지 않는다. 모든 성공/오류는
private, no-store·Vary Cookie이며 body64KiB와 기존 원격 예산을 유지한다.
명시 MySQL baseline은404/ORDER_CONTEXT_NOT_SUPPORTED로 미지원을 알리며 업무/DB를 바꾸지 않는다.

## 병합·주문·재시도

operation 응답은 `{operationId,replayed,status,cart,orderId}`다. status는 APPLIED 또는 RETIRED이며
merge/checkout 정상 성공은 APPLIED다. merge의 orderId는 null이며 checkout은
생성한 주문 ID다. 같은 owner/UUID/kind/intent를 다시 보내면 합산/주문/소비를 반복하지 않고
`replayed:true`, 저장된 orderId와 현재 cart를 반환한다. receipt 판별이 revision 검수보다 먼저다.
형식 검증은 재시도에도 유지하며 hash는 정규화한 items·주문 입력에 결속하고 expectedVersion은 제외한다.

응답 유실은 같은 UUID와 같은 intent로 확인한다. 새 UUID를 자동 생성해 재주문하지 않는다.
fresh CAS409는 입력을 보존한 채 현재 cart를 조회하고 명시적으로 다시 실행한다. 늦은 응답은
계정/세션 세대와 cart revision을 확인해 소비하며, 성공 확인 전 비회원 원문을 제거하지 않는다.
미확정 operation이 남은 채 계정/세션 세대가 바뀌면 같은 ID를 보존하고 위 GET으로 먼저 확인한다.
본인 receipt가 있으면 `replayed:true`와 저장 orderId·현재 cart를 반환하고, 타계정/재가입의 부재는404다.
GET은 수량/revision/주문/receipt를 변경하지 않으며 DB 실패를404로 바꾸지 않는다. 404는 지연된
원 POST가 이후에도 적용되지 않는다는 증거가 아니므로 descriptor를 버리거나 새 UUID로 자동 보상하지 않는다.

명시 resolve는 kind(MERGE/CHECKOUT)·원 items·orderType/orderTable의 네 body 필드를 검수한다.
MERGE의 두 order 필드는 null이어야 하고 CHECKOUT은 기존 입력 검수를 유지한다. expectedVersion은
받지 않는다. 동일 회원 잠금에서 기존 같은 kind/hash가 있으면 현재 상태/저장 orderId/current cart를
변경 없이 replayed:true로 반환한다. 없으면 원 hash의 RETIRED receipt와 한 번 증가한 cart revision을
같은 TX에 저장하고 replayed:false/orderId:null을 반환한다. 반복 resolve는 revision을 올리지 않는다.

RETIRED UUID의 늦은 merge/checkout은 revision보다 먼저409/CART_OPERATION_RETIRED로 거절해
수량/주문을 적용하지 않는다. 다른 kind/intent의 UUID 재사용은409/CONFLICT다. RETIRED는 원 기기
입력을 소비한 성공이 아니므로 입력을 보존하고 새 작업은 사용자 명시 선택으로만 실행한다.
계정/세션 세대가 바뀐 후 자동 POST 재생·새 UUID 보상은 하지 않는다.

GET은 짧은 RR이며 쓰기는 READ_COMMITTED에서 회원→cart 잠금·epoch 재검수 후 작업한다.
잠금 대기 뒤 receipt/revision의 최신 commit을 확인하며 상품은 ID순으로 잠근다. checkout은 기존 주문 생성의
서버 가격 snapshot·업무 이벤트/스탬프와 제출한 수량의 카트 소비·receipt를 같은 TX에 묶는다.
실패하면 전부 rollback하며 전송하지 않은 카트 수량을 지우지 않는다. clear 뒤에도 header/revision과
receipt를 회원 수명까지 보존해 ACK 유실의 재시도가 비운 카트를 부활시키지 않는다.

회원 삭제는 카트/receipt를 CASCADE하고 재가입에 상속하지 않는다. V6은 새 sequence 없이3표를
추가하며 runtime은 필요한 열만 갱신한다. 초기 source import/cutover는 PG-only cart3표가 비어 있어야
한다. MySQL 원본·v1/v2 source는 바꾸지 않고 MySQL baseline의 GET base와 추천 POST는404/CART_NOT_SUPPORTED로 미지원을 명시한다. 기기 카트 대체는 기존 GET base의 marker로만 선택하며 다른404/503/401이나 추천 UNAVAILABLE은 대체 모드를 허용하지 않는다. V3–V5 백업은 원형 복구를
유지하며 V6 runtime 시작 전 명시 migration/grant가 필요하다. PG 쓰기 뒤 복구는 cart/receipt까지
전체 보존해야 하며 자동 초기화·원본 덮어쓰기·자동 DB 선택을 제공하지 않는다.
