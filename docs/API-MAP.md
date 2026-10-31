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
- `POST /api/users`: 회원가입
- `GET /api/users/id-available?id={id}`: 아이디 중복 확인
- `GET /api/users/{id}/profile`: 사용자 프로필과 최근 주문 요약
- `GET /api/admin/users?page=1&size=20`: 관리자 사용자 목록
- `PUT /api/admin/users/{id}`: 관리자 사용자 수정
- `DELETE /api/admin/users/{id}`: 관리자 사용자 삭제

현재 로그인한 관리자는 자기 계정을 삭제하거나 자신의 `ADMIN` 역할을 제거할 수 없다. 관리자 수정의 `role`은 `USER` 또는 `ADMIN`만 허용한다.

## Products

- `GET /api/products?page=1&size=12&type=&q=&sort=popular`: 상품 목록, 검색, 카테고리, 정렬
- `GET /api/products/popular`: 인기 상품
- `GET /api/products/{id}`: 상품 상세
- `GET /api/products/{id}/features`: 출처가 기록된 상품 특성. 미확인 필드는 null
- `POST /api/admin/products`: 관리자 상품 등록
- `PUT /api/admin/products/{id}`: 관리자 상품 수정
- `PUT /api/admin/products/{id}/features`: 관리자 특성 전체 교체. 누락/null은 unknown, {}는 초기화
- `DELETE /api/admin/products/{id}`: 관리자 상품 삭제

고객 상품 화면의 공식 query contract는 `type`, `q`, `sort(popular|priceAsc|priceDesc|name)`, `page`이며 frontend router가 잘못된 값을 기본값으로 정규화한다.

기존 ProductResponse의 7필드/주종은 유지한다. 별도 특성의 volumeMl·설명·맛/향 태그·출처와
null/빈 배열·검증·삭제/원자 교체는 [특성 계약](PRODUCT-FEATURES.md)과 [OpenAPI](openapi/product-features.json)를 따른다.
sourceName은 알려진 값/빈 배열 검수에도 필요하지만 권리 승인을 뜻하지 않으며 sourceUrl을 fetch하지 않는다.

## Comments

- `GET /api/products/{productId}/comments?page=1&size=10`: 상품 댓글 목록
- `POST /api/comments`: 댓글 등록
- `PUT /api/comments/{id}`: 댓글 수정
- `DELETE /api/comments/{id}`: 댓글 삭제
- `GET /api/admin/comments?page=1&size=20`: 관리자 댓글 목록

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
