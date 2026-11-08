# API Map

모든 backend API는 `/api` prefix를 사용하고, frontend는 [Frontend API client](https://github.com/Pubburi/Frontend/blob/main/src/services/api.js)를 통해 호출한다.

## Response Contract

성공/실패 응답은 공통 wrapper를 사용한다.

```json
{
  "data": {},
  "message": "ok",
  "error": null
}
```

실패 응답의 `error`는 `{ "code": "", "message": "", "fields": {} }` 형태다. 목록 API의 `data`는 `PageResponse<T>`이며 `{ items, page, size, total, totalPages, hasNext }`를 가진다. 없는 리소스의 수정/삭제는 `404`, 현재 관리자 자기 계정 삭제와 관계 데이터 충돌은 `409`를 반환한다.

인증이 필요한 API는 session cookie를 사용하며, frontend API client는 `credentials: include`를 유지한다.

보호 요청은 세션 snapshot의 권한 대신 DB의 현재 계정/역할을 확인한다. 삭제 계정·동일 ID 재가입의
옛 세션·비밀번호 변경으로 회수된 세션은 401, 현재 계정의 권한 부족은 403이다. auth/me는 최신
이름·스탬프·역할을 반환하며 UserResponse의 기존 다섯 필드는 유지한다. 내부 auth_epoch는 공개하지
않는다. DB 조회 장애를 비로그인으로 바꾸지 않는다. [세션/003 전환 계약](SESSION-AUTHORITY.md)을 따른다.

## Auth And Users

- `POST /api/auth/login`: 로그인
- `POST /api/auth/logout`: 로그아웃
- `GET /api/auth/me`: 현재 session 사용자 조회
- `PUT /api/users/me/profile`: 본인 이름만 수정, `{ "name": "변경 이름" }`
- `PUT /api/users/me/password`: 본인 현재 암호 검증 후 변경, `{ "currentPassword": "현재 암호", "newPassword": "새 암호" }`
- `GET /api/users/me/preferences`: 현재 계정만의 명시취향·설문 상태 조회
- `PUT /api/users/me/preferences`: 현재 계정만의 명시취향 전체 교체
- `POST /api/users/me/search-phrases`: 본인 현재 취향과 별도 검색 선택의 문구/정확 조건 호출
- `GET /api/users/me/favorites?page=1&size=20`: 본인 찜의 현재 상품 페이지
- `GET /api/users/me/favorites/{productId}`: 본인의 단건 찜 상태(없는 관계·없는/삭제 상품도 200 false)
- `PUT /api/users/me/favorites/{productId}`: 본인 찜 저장(멱등, 기존 추가 순서 유지)
- `DELETE /api/users/me/favorites/{productId}`: 본인 찜 해제(없는 관계·삭제 상품도 200)
- `POST /api/users`: 회원가입
- `GET /api/users/id-available?id={id}`: 아이디 중복 확인
- `GET /api/users/{id}/profile`: 사용자 프로필과 최근 주문 요약
- `GET /api/admin/users?page=1&size=20`: 관리자 사용자 목록
- `PUT /api/admin/users/{id}`: 관리자 사용자 수정
- `DELETE /api/admin/users/{id}`: 관리자 사용자 삭제

두 본인 수정은 일반 회원/관리자 모두 세션의 ID와 관측 epoch만 사용한다. 성공은 기존
`ApiResponse<Boolean>`의 `data:true`, HTTP200이다. 이름은 NotBlank/최대100 UTF-16 문자,
현재 암호는 NotBlank/최대100(짧은 기존 암호 허용), 새 암호는 NotBlank/4–100과 UTF-8 최대72바이트이며 문자열을
trim하지 않는다. 누락/null·잘못된 타입·추가 body 필드는 `400 VALIDATION_FAILED`, query는
지원하지 않아 `400 BAD_REQUEST`다. ID/role/stamps/epoch 등 소유·권한 필드를 받지 않는다.
유효 세션의 현재 암호 불일치는 `403 FORBIDDEN`이며 기존 자격과 세션을 유지한다. 비로그인·
삭제/재가입·회전된 epoch는 `401 UNAUTHORIZED`다. 새 로그인 성공과 구세션 회수는 구분한다.

서비스는 사용자 전체 잠금 뒤 epoch를 다시 대조한다. 이름 수정은 해시/epoch·역할·스탬프를
보존하고, 암호 변경은 잠금 안에서 현재 해시 확인 후 기존 hasher와 새 epoch를 저장한다.
commit 뒤 관측 ID/epoch와 여전히 같은 요청 세션만 갱신하고 다른 기존 세션은 다음 보호
요청에서401이다. 동시 로그아웃/다른 계정 로그인으로 바뀐 세션을 복원하지 않는다.
암호/해시/epoch를 응답에 넣지 않으며 새 schema/DAO·runtime 권한은 없다.
[F09 공급·검증 범위](https://github.com/Pubburi/Backend/blob/8f593168ed7dbfe629c70e20fcd0ea020d6fb366/docs/plans/2026-11-03-self-account-settings.md)는 실제 웹 인수와 구분한다.

현재 로그인한 관리자는 자기 계정을 삭제하거나 자신의 `ADMIN` 역할을 제거할 수 없다. 관리자 수정의 `role`은 `USER` 또는 `ADMIN`만 허용한다.

명시취향은 관리자도 본인만 접근한다. soft 주종/태그/목표 도수와 hard 예산/허용 도수를 분리하고
NOT_STARTED/SKIPPED/COMPLETED·null/[]/0을 구분한다. [본인 전용 계약](EXPLICIT-PREFERENCES.md)과
[OpenAPI](openapi/explicit-preferences.json)를 따르며 기존 타인 profile/관리자 회원 응답에는 포함하지 않는다.

본인 문구 호출은 strict `{searchOptInFields:[],limit:5}`만 받으며 저장 snapshot을 입력받지 않는다.
`PROVIDER/result`와 `UNAVAILABLE/null`, 의미 변화409·수명401·최종 조건400·DB 오류500을
구분하고 필수 조건을 뺀 검색으로 바꾸지 않는다. [호출 계약](SEARCH-PHRASES.md)을 따른다.

찜도 관리자 포함 본인 전용이다. 요청 본문/소유자 선택 없이 세션 계정에 저장하며, 기존 상품 응답의
현재 일곱 필드를 반환한다. [계정 찜 계약](ACCOUNT-FAVORITES.md)·[OpenAPI](openapi/account-favorites.json)의
페이지·반복 PUT·삭제/재가입·405 Allow 정책을 따른다. 찜 DELETE의 멱등 200은 일반 리소스 삭제 404와 구분한다.

PG 제공자의 본인 비공개 기록은 [Notebook 계약](NOTEBOOK.md)의 목록·상품별 조회·UUID CRUD와
명시 계정 guard·기대 revision·개인 원문/삭제 상품 보존을 따른다. MySQL baseline은 명시404이며
공개 후기·추천 입력으로 자동 전송하지 않는다.

PG 계정 카트는 [Cart 계약](CART.md)의 본인 조회·절대 수량/삭제·명시 guest 병합·원자 checkout과
UUID receipt 읽기를 제공한다. 기존 주문 API는 유지하고 일반404/DB 오류와 legacy의 명시
`CART_NOT_SUPPORTED`를 구분한다.

## Products

- `GET /api/recommendations/default?size=4`: 공개 비개인 기본 주류 목록, 기존 상품 배열과 BASELINE/FALLBACK
  ([정본](DEFAULT-RECOMMENDATIONS.md)·[OpenAPI](openapi/default-recommendations.json)). 추천 서버가 없어도
  현재 DB ID순 대체를 제공하고 DB 실패를 빈 성공으로 바꾸지 않는다.

- `GET /api/products?page=1&size=12&type=&q=&sort=popular`: 상품 목록, 검색, 카테고리, 정렬
- `GET /api/products/popular`: 인기 상품
- `GET /api/products/{id}`: 상품 상세
- `GET /api/products/{id}/features`: 출처가 기록된 상품 특성. 미확인 필드는 null
- `GET /api/products/{id}/similar`: 검수된 기준 상품의 유사 상품 최대 4개, 선택 `includeExplanations=true`의 실제 비교 이유. [추천 계약](CONTENT-RECOMMENDATIONS.md#상품-상세의-유사-상품)
- `POST /api/admin/products`: 관리자 상품 등록
- `PUT /api/admin/products/{id}`: 관리자 상품 수정
- `PUT /api/admin/products/{id}/features`: 관리자 특성 전체 교체. 누락/null은 unknown, {}는 초기화
- `DELETE /api/admin/products/{id}`: 관리자 상품 삭제

고객 상품 화면의 기존 query contract는 `type`, `q`, `sort(popular|priceAsc|priceDesc|name)`, `page`다.
Backend는 `minPriceKrw/maxPriceKrw`, `minAbvPercent/maxAbvPercent`, 반복 `tasteTag/aromaTag`의
필수 AND 필터도 제공한다. [단위·검수/unknown·페이지 계약](PRODUCT-SEARCH.md)과
[부분 OpenAPI](openapi/product-search.json)를 따른다. 명시 엔진 읽기도 같은 JSON을 반환하고
`X-Pubburi-Search-Source`는 DATABASE/ENGINE_VERIFIED/DATABASE_FALLBACK을 구분한다.
엔진의 ID/total과 현재 canonical DB를 대조하며 오류/불일치도 모든 필수조건을 유지한다.
기본 비활성·설정/한계는 [상품 검색](PRODUCT-SEARCH.md)을 따른다. 자동완성/의미 검색은 별도다.

기존 ProductResponse의 7필드/주종은 유지한다. 별도 특성의 volumeMl·설명·맛/향 태그·출처와
null/빈 배열·검증·삭제/원자 교체는 [특성 계약](PRODUCT-FEATURES.md)과 [OpenAPI](openapi/product-features.json)를 따른다.
sourceName은 알려진 값/빈 배열 검수에도 필요하지만 권리 승인을 뜻하지 않으며 sourceUrl을 fetch하지 않는다.

## Comments

- `GET /api/products/{productId}/comments?page=1&size=10`: 상품 댓글 목록
- `POST /api/comments`: 댓글 등록
- `PUT /api/comments/{id}`: 댓글 수정
- `DELETE /api/comments/{id}`: 댓글 삭제
- `GET /api/admin/comments?page=1&size=20`: 관리자 댓글 목록

공개 상품 후기 GET은 `sort=latest|rating_desc|rating_asc`를 받는다. 생략/latest는 기존 ID 내림차순,
평점순의 동점은 ID 내림차순이다. `includeSummary=true`일 때만 기존 여섯 페이지 필드에
`ratingSummary:{count,average}`를 더한다. 생략/false는 기존 여섯 필드와 후기 다섯 필드를 그대로
유지한다. 각 옵션은 한 번만 허용하며 잘못된 값·중복은400이다. 관리자 목록에는 적용하지 않는다.

count는 해당 상품의 전체 후기 수(total)이며 average도 현재 페이지가 아닌 전체 저장 FLOAT/REAL의
SQL AVG다. 유한 집계를 BigDecimal로 변환해 소수 둘째자리 HALF_UP으로 표시하고 저장된 후기
값을 변경·범위 필터·clamp하지 않는다. 부동소수 저장 오차가 반올림 경계에 영향을 줄 수 있다.
0건일 때만 average=null이고, 비어 있지 않은 집계의 비유한/잘못된 값은500이다. items/total/평균은
하나의 read-only REPEATABLE READ snapshot에서 읽는다. page/size 정규화·없는 상품의 빈200은
보존하고 int OFFSET을 넘는 페이지는400으로 거절한다. 선택 `includeTasteTags=true`는 각 후기 item에 정규화된 `tasteTags:string[]`만 더하며
생략/false·관리자·쓰기 응답의 기존 다섯 후기 필드는 유지한다. 잘못된 값·중복 옵션은400이다.
태그는 현재 페이지 ID들을 한 번에 읽어 같은 RR snapshot에서 조합한다. 태그가 없으면[]이다.

POST/PUT은 선택 `tasteTags` 배열(최대20개, 각 정규화 후40 UTF-16)을 받는다. Java17 strip,
Locale.ROOT 소문자, 정렬을 적용하고 공백·중복·null·비문자·U+0000·단독 surrogate·초과 입력은400이다. 쉼표/개행은
구분자가 아닌 한 태그 원문이다. POST 생략은[], PUT 생략은 기존 태그 보존, 명시[]는 비우기다.
태그·평점·본문은 한 TX이며 본인/관리자 권한과 상품/회원 삭제 cascade를 유지한다. 회원 의견을
검수 상품 특성·추천 정답·구매확인으로 사용하지 않는다. 전체 F04 인수는 별도 연동 검증을 따른다.

기존 MySQL은 백업·쓰기 중단 후 [009 additive SQL](migrations/009-comment-taste-tags.sql)을
한 번 적용한다. 새 폐기 DB bootstrap에는 같은 스키마가 포함된다. PG는 기존 V1/V2를 보존한
Flyway V3다. v1 이관의16표/과거 receipt는 유지하며 새 태그 테이블의 shape/빈 상태/읽기 권한이
확인되지 않거나 태그가 있으면 쓰기 전에 거절한다. 태그를 포함하는 명시 v2 [snapshot](SOURCE-SNAPSHOT.md)/[import](SOURCE-IMPORT.md)는 DB06의
원문 보존 증분이며 기본 전환·복구 인수는 미완료다.11/22 목표를 유지한다([결정](https://github.com/Pubburi/Backend/discussions/48)).

## Orders

- `POST /api/orders`: 주문 생성
- `GET /api/users/{userId}/orders?page=1&size=10`: 사용자 주문 목록
- `GET /api/orders/{id}`: 주문 상세
- `GET /api/admin/orders?page=1&size=20`: 관리자 주문 목록
- `PATCH /api/admin/orders/{id}/complete`: 주문 완료 처리
- `DELETE /api/admin/orders/{id}`: 주문 삭제

주문 생성 request의 `details`는 `{ productId, quantity }` 배열이다. backend는 같은 `productId`를 합산해 저장하며, `quantity <= 0`, `productId <= 0`, 존재하지 않는 상품은 `BAD_REQUEST`로 처리하고 주문·상세·주문 수·스탬프 변경을 전체 롤백한다.

주문 응답의 `details`는 상품명, 타입, 이미지, 단가, 합계를 포함한다. 목록/상세 API 모두 같은 `OrderResponse` 형태를 유지한다.

상품명/타입/이미지·`unitPrice`/`sumPrice`는 현재 상품 JOIN 대신 주문 시점 스냅샷이다.
주문에 `totalPrice`와 `priceBasis`가 추가된다. `ORDER_SNAPSHOT`은 신규 서버 확정값이고
`LEGACY_ESTIMATE`는 과거 단가를 복구할 수 없어 전환 시 고정한 추정값이다. 상세가 사라진
과거 주문 총액은 `null`로 표시한다. 기존 DB는 [전환 안내](ORDER-PRICE-SNAPSHOT.md)에 따라
마이그레이션해야 하며 초기화 SQL을 재실행하지 않는다. 가격 합계/수량이 정수 범위를 넘으면
`BAD_REQUEST`로 거부한다. 실제 결제는 별도 범위다.

상품 삭제 뒤 해당 상세의 `productId`는 `null`이며 상품명/주종/이미지·단가·수량·합계와
주문 총액은 남는다. 회원 삭제 뒤 주문의 `userId`와 자유 입력 `orderTable`은 `null`이다.
소유자가 없는 주문은 관리자만 단건/관리자 목록으로 조회할 수 있으며 같은 ID의 재가입 계정에
재연결하지 않는다. 신규 주문 요청의 상품 ID는 계속 양수여야 한다. OpenAPI 응답 스키마에도
nullable을 표시한다. [삭제 이력·전환 계약](ORDER-HISTORY-PRESERVATION.md)을 함께 따른다.

## Markets

- `GET /api/markets?page=1&size=12`: 매장 목록
- `GET /api/markets/{id}`: 매장 상세
- `POST /api/admin/markets`: 관리자 매장 등록
- `PUT /api/admin/markets/{id}`: 관리자 매장 수정
- `DELETE /api/admin/markets/{id}`: 관리자 매장 삭제

## Frontend 연결 화면

- 고객: 홈, 상품 목록, 상품 상세, 댓글, 장바구니, 주문, 로그인, 회원가입, 마이페이지, 매장
- 관리자: 상품 CRUD, 매장 CRUD, 주문 관리, 댓글 관리, 사용자 관리

## Response DTO Notes

- `ProfileResponse.grade`: `{ img, step, stepMax, to, title }`.
- `ProductResponse.img`: DB에는 기존 파일명이 남아 있을 수 있지만 frontend는 WebP path로 변환해 표시한다.
- `OrderResponse.details`: 주문 목록에서는 batch 조회로 채워지며, endpoint 응답 형태는 이전과 같다.

## 문서 갱신 체크리스트

- endpoint path, request DTO, response DTO, page 기본값이 바뀌면 이 문서를 갱신한다.
- frontend API client를 바꾸면 관련 화면/store도 함께 확인한다.
- API path가 그대로라도 내부 응답 필드, validation, 권한, pagination 기본값이 바뀌면 이 문서를 갱신한다.

## Owned content recommendations

- `POST /api/users/me/recommendations/content`: 로그인한 현재 본인 취향과 운영자가 고정한 검수
  container-DEMO catalog의 기존 B1/R04 caller. strict `{"limit":4}`(기본4·정수1–12)만 허용한다.
- ApiResponse data는 `source`, `catalogScope`, `result`, `products`. source는 PROVIDER/UNAVAILABLE,
  scope는 REVIEWED_DEMO다. 정상 result는 검증한 원 B1/R04, products는 같은 ID순의 현재 Product7이다.
  UNAVAILABLE은 result=null/products=[]이며 기본 B0 추천으로 꾸미지 않는다.
- private/no-store·query/owner/catalog/profile 주입400·수명401·현재 취향/승인 상품 변화409·DB500을
  구분한다. 원본80 자동 전송/fit·서비스 SKU 승인·전체 R02/R04는 아니다. [공급 계약](CONTENT-RECOMMENDATIONS.md)을 따른다.

## 서비스 내부 알림

[본인 F12 계약](NOTIFICATIONS.md)은 PG의 관리자 주문 완료/타인 후기 명시 삭제와 원자 저장하는 알림이다. 본인 GET 목록·개별 PUT 읽음·페이지 ID POST 읽음만 제공하며 임의 생성/외부 push는 없다. 기존 주문·후기 경로/응답과 MySQL baseline은 보존한다.
