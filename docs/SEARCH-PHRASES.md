# 본인 취향의 명시 검색 문구 호출

`POST /api/users/me/search-phrases`는 현재 세션의 명시 취향만 읽어
[Recommendation 제공자](https://github.com/Pubburi/Recommendation/blob/main/docs/SEARCH-PHRASES-API.md)를 호출한다.
상품 검색·추천 순위·취향 저장을 실행하지 않는다. 기본 MySQL과 명시 JPA 제공자가 같은
Controller/서비스/HTTP client를 사용하며 새 schema·DAO·DB 권한은 없다.

~~~json
{"searchOptInFields":["tasteTags"],"limit":5}
~~~

- 생략한 필드는 각각 `[]`, `5`다. 명시 null·문자열/boolean/실수 limit·1–5 밖의 값은400이다.
- 검색 선택은 `categories`, `tasteTags`, `aromaTags`, `targetAbvPercent`의 중복 없는 최대4개다.
  저장된 soft 선호를 검색 필수조건으로 쓰겠다는 별도 선택이며 취향 자체를 바꾸지 않는다.
- 본문은 이 두 필드만 받는다. query·임의 preferences/계정 ID/epoch·원격 URL은400이며
  관리자도 본인의 snapshot만 사용한다. 비로그인/만료·삭제 후 같은 ID 재가입은401이다.
- 게스트 기본 문구는 UI의 고정 `/products` 링크다. 비회원이 본인 API를 호출하거나 과거 계정의
  취향을 사용하지 않는다. 로그인한 무취향 계정은 제공자의 `all_products` 결과를 받을 수 있다.

## 응답과 실패

성공은 기존 `ApiResponse`의 data에 다음 두 필드를 둔다.

~~~json
{"source":"PROVIDER","result":{"algorithm":"r06-explicit-search-phrases-v1","items":[],"candidate_count":0,"omitted_count":0,"product_count":null,"diagnostics":[{"field":"tasteTags","code":"selected_unknown"}],"fallback_reason":"selected_preferences_unknown"}}
~~~

위 JSON은 모르는 맛 태그를 선택한 경우의 형태 설명이며 진단 목록은 제공자 계약을 따른다.
`result`는 제공자의 원래 snake_case typed wire다. 고정 label·typed conditions·ordered query_pairs,
분기/생략 수·unknown/known[]/실제0·Decimal scale·원자 태그·Unicode 재검증 표시를 유지한다.
`product_count`는 검색 미실행의 null이며0으로 채우지 않는다.

원격 미기동/timeout·상태/미디어 오류·redirect·잘못되거나 변조된 wire는
`200 {"source":"UNAVAILABLE","result":null}`이다. 로컬에서 문구를 만들거나 조건을 뺀 URL로
대체하지 않는다. 선택한 unknown/empty·목표/hard 충돌의 정상 제공자 빈 결과와 구분한다.
DB 조회 예외는500, 원격 중 취향 의미가 바뀌면409, 계정 epoch/요청 세션이 달라지면401이다.
소비자는 기존 입력/URL을 보존하고 현재 상태에서 명시 재요청한다. 자동 재시도나 무조건
`/products` 이동으로 저장 hard/선택 조건을 없애지 않는다.

원본 snapshot과 정확히 결속된 wire를 모두 검증한 뒤 공개 `ProductSearchFilters`를 적용한다.
예컨대 과거 저장 값의 `k`/`K`가 Java17의 최종 중복 검증에 걸리면 고정400을 반환한다.
이 경우도 태그를 제거하거나 빈200으로 고치지 않는다. 변조된 pair/wire는 이400으로 위장하지
않고 UNAVAILABLE로 처리한다. 응답·진단에 계정 ID/epoch/hash·입력 원문을 덧붙이지 않는다.
응답은 입력 오류와401/409를 포함해 `Cache-Control: private, no-store`, `Vary: Cookie`를 사용한다.

## 호출·검색 경계

원래 `UserPreferenceService.getOwned(id,epoch)`의 짧은 read-only RR에서 snapshot을 얻고 commit한
뒤 원격을 호출한다. 원격 이후 같은 canonical read로 현재 snapshot을 확인하며 의미가 달라진
결과를 반환하지 않는다. 마지막 AccessGuard 확인도 요청 세션의 ID/epoch가 그대로여야 한다.
재검증 직후의 새로운 변경까지 분산 원자적으로 막는 보장은 아니며 클라이언트도 현재 인증/
요청/화면 수명으로 응답을 소유해야 한다.

운영자가 지정한 기존 `PUBBURI_RECOMMENDATION_BASE_URL` origin만 사용한다. connect200ms·전체
응답 본문700ms, 누적 UTF-8 body128KiB·중복 JSON/추가 문서/깊이·이름/문자열 상한과 redirect
거부를 적용한다. Cookie·Authorization·계정 식별자·추정 profile은 전송하지 않는다. 세션/취향
검증의 DB 오류를 원격 장애로 감싸지 않는다.

consumer guard는 현재 snapshot의 모든 hard·별도 선택과 conditions/pairs·분기/개수·진단을
대조한다. 로컬 phrase generator/랭커/모델 fallback은 제공하지 않는다. 표준 query 인코더로
각 pair를 한 번만 인코딩해 공개 `GET /api/products`를 호출할 수 있다. 쉼표/개행은 태그 atom
내부에 남긴다. q/sort/page/size·기존 URL 조건을 적용/교체하는 선택은 웹 연결 계약에서 정하며
클릭 없이 검색을 실행하지 않는다. 실제 검색의 빈 결과/400·현재 상품 값은 해당 API가 판정한다.

전체 R06 웹 소비·문구 선택/실제 검색 인수, B1 개인화·공개 엔진 검색/PG fallback·DB05–06
cutover는 별도 미완료다. [작업/검증 기록](plans/2026-11-04-owned-search-phrases.md)을 따른다.
