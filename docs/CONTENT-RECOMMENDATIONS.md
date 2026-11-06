# 본인 명시취향의 검수 DEMO B1 추천

`POST /api/users/me/recommendations/content`는 로그인한 **현재 본인**의 canonical 저장 취향을
기존 [B1 content](https://github.com/Pubburi/Recommendation/blob/main/docs/CONTENT-API.md)에 연결한다.
요청 JSON은 `{"limit":4,"includeActivity":false}`다. 생략 시 limit은4, includeActivity는 false다.
limit은 정수1–12, includeActivity는 JSON boolean이며 null·문자열·다른 필드·모든 query를400으로 거절한다.
계정/역할/epoch·profile/preferences·catalog/제외/host는 요청에서 선택하지 않는다.

성공 ApiResponse data에는 source(PROVIDER/UNAVAILABLE), catalogScope(REVIEWED_DEMO / REVIEWED_FAMILY_BROWSE_DEMO),
result, products 네 필드가 기본이다. PROVIDER의 result는 검증한 기존 B1/R04 typed 응답이며
products는 result.items와 같은 ID순의 **현재 Product7**이다. UNAVAILABLE은 result=null과
products=[]이며 기존 REVIEWED_DEMO를 유지한다. 새 family scope는 승인된 family PROVIDER에만 표시한다.
제공자의 정상 빈 items/fallback은 PROVIDER로 보존하며 장애와 구분한다. 선택한 활동 주종이 실제
반환 상품의 양수 `text` 주종 기여에 쓰인 경우에만 `activityEvidence`에 `favoriteCategories`,
`highlyRatedCategories` 배열을 제공한다. 근거가 없거나 UNAVAILABLE이면 이 필드를 생략한다.
모든 응답은 private, no-store·Vary Cookie다. 점수는 만족 확률/품질 성과가 아니며 결과를 저장하지 않는다.

## 상품 상세의 유사 상품

공개 `GET /api/products/{id}/similar`는 현재 기준 상품에 대해 최대 4개의 `ProductResponse`를
ApiResponse `data.items`에 반환한다. 존재하지 않는 기준은 404다. 응답 캐시는 `no-store`다.
호출은 기존 container `REVIEWED_DEMO`의 검수 상품에만 한정한다. family-browse 전용이나 미승인
상품, 모델 실패, 만료 또는 상품/특성 변경은 200 빈 배열로 처리한다.

Python의 [유사 후보 계약](https://github.com/Pubburi/Recommendation/blob/main/docs/SIMILAR-API.md)에
고정 catalog·기준 ID·limit만 전송한다. 응답의 양수 기여와 후보·정렬을 검사해 기준/같은 그룹,
삭제·판매 불가·중복 또는 미검수 ID를 반환하지 않는다. 상품/특성 binding은 원격 호출 앞뒤의
별도 읽기 트랜잭션에서 현재 DB와 대조한다. 최종 상품 정보는 두 번째 읽기에서 가져온다.

## 검수 catalog와 현재 업무 값

운영자는 Recommendation의 [공식 checked bundle](https://github.com/Pubburi/Recommendation/blob/main/docs/BACKEND-CATALOG-BUNDLE.md)을
검증/발행한 뒤 `PUBBURI_CONTENT_CATALOG_PATH`, `PUBBURI_CONTENT_CATALOG_SHA256`으로 고정한다.
기본 빈 설정은 일반 API/B0/R06의 기동을 유지하고 이 경로만 UNAVAILABLE로 처리한다.
전체 바이트 pin·정확 shape/명시 ID·그룹·원 공개 응답 binding과 유효기한을 확인한다. 원 권위/
매핑 검증과 모델 fit은 Recommendation 소유이며 Java가 Python의 authority loader를 복제하지 않는다.

기본은 container DEMO이며 family bundle을 거절한다.
`PUBBURI_CONTENT_FAMILY_BROWSE_DEMO_ENABLED=true`를 명시한 경우에만 별도 family-browse DEMO도
받는다. 두 runtime의 속성은 `pubburi.recommendation.content-family-browse-demo-enabled`이고 기본 false다.
요청 body/query로 이 설정이나 scope를 선택할 수 없다. v1 top9/checked14/record7 필드는 그대로다.

| data_scope | evidence_kind | snapshot_origin | 각 record eligibility_scope |
|---|---|---|---|
| reviewed_mapped_business_demo_only | authored_regression_fixture | demo_fixture | public_browse_row_exists |
| reviewed_mapped_business_demo_only | authored_regression_fixture | owned_demo_fixture | public_browse_row_exists |
| reviewed_mapped_business_demo_only | reviewed_public_source | existing_local_catalog | public_browse_row_exists |
| reviewed_source_family_browse_demo_only | authored_regression_fixture | demo_fixture | reviewed_source_family_browse_demo_only |
| reviewed_source_family_browse_demo_only | reviewed_public_source | owned_family_browse_demo | reviewed_source_family_browse_demo_only |

이 다섯 조합과 catalog/records의 ID순서만 허용하며 한 bundle에서 scope를 섞지 않는다.
container는 알려진 volume을, family는 catalog volume_ml/taste_tags/aroma_tags와 원 features의
volumeMl/description/tasteTags/aromaTags 모두 null을 요구한다. 검수한 빈 배열[]도 unknown으로
바꾸지 않는다. family ABV는 알려진 원 Product와 정확히 같아야 한다. DB 태그/설명을 source 사실로
승격하거나 용량을 추정하지 않는다. family의 새 응답 enum은 REVIEWED_FAMILY_BROWSE_DEMO이며
열람 전용이다. 이름/현재 Product7·DEMO 가격을 일반 판매 SKU·용량·재고 승인으로 해석하거나
상품 상세/장바구니·찜/비교에 자동 승격하지 않는다. 기존 REVIEWED_DEMO의 의미는 그대로다.

bundle은 일반 서비스 catalog 승인이 아니다. source11/업무 DEMO/상품군 browse의 권위 구분을
유지하고 기존80을 이름/순서로 자동 매핑/fit하지 않는다. sourceName/URL·사용자 취향은 권리 승인이
아니다. available도 판매/재고 검증이 아니라 원래 명시 browse 조건이다. 기록된 원 응답은 Java에서
다른 숫자 직렬화로 다시 해시하지 않고 parsed public 필드를 semantic exact로 대조한다.
null/[]·배열 순서·문자 atom은 유지하고 유한 숫자는 수치가 같아야 한다.

한 짧은 RR에서 현재 본인 취향·mapped 상품/특성·태그를 배치로 읽는다. 현재 공개 필드가 원 승인
binding과 다르면409이고 제공자에 보내지 않는다. 원격 대기 뒤 같은 RR 읽기를 다시 수행해
취향/상품/특성 변화·삭제도409로 거절한다. 미설정/핀 불일치/만료/historical은 전송하지 않는다.
유효기한은 원 business capture 종료+5분이며 새 current bundle 발행은 원 소유자의 검증을 요구한다.

## 모델·설명과 실패 경계

원래 catalog와 현재 전체 preferences, constraints={}, limit, include_explanations=true를
고정 upstream `/v1/recommendations/content`에 보낸다. `includeActivity=true`이고 저장 주종이
null일 때만 같은 RR에서 현재 계정·epoch의 찜과 상품별 최신 후기(id 내림차순) 중 4–5점에
해당하는 상품을 두 배치 조회로 읽는다. 승인 목록의 available 상품에 한해 정확한 표준 주종을
출처별로 중복 제거·정렬한다. 명시한 주종 배열과 빈 배열[]은 그대로 사용한다.

주종 근거가 있으면 `activity_categories`에 `favorite_categories`, `highly_rated_categories`만
추가한다. 저장 preferences·태그·도수·hard 조건은 그대로 전송하며, 계정 ID·해시/epoch·cookie·
인증 헤더·활동 상품 ID·후기 원문·점수는 보내지 않는다. 제공자는 두 배열의 합집합을 soft 주종에
적용하며 Backend는 같은 합집합으로 응답 근거를 검사한다. 원격 대기 뒤 출처별 주종 집합을 다시
비교하며 달라졌으면409다.

응답의 후보 ID/중복/수·현재 필수조건/available와 고정 제외 없음·진단/기여 합·R04 profile 출처·
실제 명시 사실/단위/상위3/생략 수/fallback을 확인한다. 가격/임의 이름을 점수 근거로 받거나
잘못된 reference/없는 atom을 사용자 취향으로 표시하지 않는다. 점수 함수를 재계산하는 검증은 아니다.

연결200ms·원격 전체3초, 누적 요청512KiB/응답256KiB·strict 중복/추가 JSON·redirect 거부를
적용한다. 재시도하지 않으며 완료하지 않은 future/body subscription의 취소를 요청한다.
이 예산은 DB/전체 API 시간의 성능 보장이나 이미 실행 중인 제공자 CPU fit의 취소 보장이 아니다.
기존 공개 B0/R06의700ms는 그대로다. 원격/검증 실패에는 UNAVAILABLE이며 약한 B0 추천으로
개인화한 것처럼 응답하지 않는다. 공개 기본 목록은 별도 기존 경로로 사용할 수 있다.

입력400·비로그인/credential/삭제재가입/세션 교체401·의미 변화409·DB 예외500을 구분한다.
원문 파일/계정/응답 오류를 오류 메시지에 반사하지 않는다. 마지막 읽기 이후의 수정까지 잠그는
보장은 아니며 주문 가격은 원래 주문 시점 스냅샷을 사용한다.

[검증/한계](https://github.com/Pubburi/Backend/blob/6fda9d3c2fb42e7d118e15602e601389f92695dd/docs/plans/2026-11-04-owned-content-recommendations.md)를 따르며 전체 R02/R04·서비스
SKU 승인/학습 품질·기본 PostgreSQL cutover 완료로 확대하지 않는다.
