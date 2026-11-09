# 본인 명시취향의 검수 B1 추천

`POST /api/users/me/recommendations/content`는 로그인한 **현재 본인**의 canonical 저장 취향을
기존 [B1 content](https://github.com/Pubburi/Recommendation/blob/main/docs/CONTENT-API.md)에 연결한다.
요청 JSON은 `{"limit":4,"includeActivity":false}`다. 생략 시 limit은4, includeActivity는 false다.
limit은 정수1–12, includeActivity는 JSON boolean이며 null·문자열·다른 필드·모든 query를400으로 거절한다.

같은 요청에서 현재 본인 ID를 `encodeURIComponent`한 `X-Pubburi-Feedback-Owner` 헤더를 보내면
명시 PostgreSQL의 검수 container-DEMO 또는 로컬 검수 catalog에서 저장한 맞음/관심 없음을 반영한다. 헤더는 계정을
선택하지 않으며 현재 세션과 다르면401이다. 헤더가 없거나 지원하지 않는 스코프, 매핑된 선택이
없는 경우에는 기존 취향 추천을 사용한다. 요청 본문에는 선택이나 참조 ID를 받지 않는다.

저장 선택은 검수 매핑에 있는 본인 행만 조회하고 provider 호출 전후 같은 방식으로 비교한다.
관심 없음은 같은 검수 상품군까지 제외하고, 맞음은 기준 상품군을 제외한 대안의 사실 비교에 쓴다.
종류별21개 이상 또는 provider 응답 오류/중단에는 UNAVAILABLE이며 제외를 생략해 재시도하지 않는다.
유효한 응답만 `feedbackEvidence`의 `rankingMode`, `excludedNotInterestedCount`,
`positiveReferences`를 제공한다. 제외 수는 hard 조건 적용 전 검수 카탈로그의 해당 행 수다.
`positiveReferences`에는 결과에 실제 사용된 기준 ID와 현재 이름만 포함한다. 기준 상품 설명의
`comparison`은 `reference_product`와 실제 `product_id`다. 활동 근거는 profile로 선정된 결과에
기여했을 때만 제공한다. 양수 근거가 부족한 경우 결과를 0점 후보로 채우지 않는다.
계정/역할/epoch·profile/preferences·catalog/제외/host는 요청에서 선택하지 않는다.

성공 ApiResponse data에는 source(PROVIDER/UNAVAILABLE), catalogScope(REVIEWED_DEMO / REVIEWED_FAMILY_BROWSE_DEMO / REVIEWED_LOCAL_CATALOG),
result, products 네 필드가 기본이다. PROVIDER의 result는 검증한 기존 B1/R04 typed 응답이며
products는 result.items와 같은 ID순의 **현재 Product7**이다. UNAVAILABLE은 result=null과
products=[]다. 초기 권위가 없거나 거절되면 기존 REVIEWED_DEMO이며, 유효한 로컬 권위의 제공자 장애는
REVIEWED_LOCAL_CATALOG를 유지한다. family scope는 승인된 family PROVIDER에만 표시한다.
제공자의 정상 빈 items/fallback은 PROVIDER로 보존하며 장애와 구분한다. 선택한 활동 주종이 실제
반환 상품의 양수 `text` 주종 기여에 쓰인 경우에만 `activityEvidence`에 `favoriteCategories`,
`highlyRatedCategories` 배열을 제공한다. 근거가 없거나 UNAVAILABLE이면 이 필드를 생략한다.
모든 응답은 private, no-store·Vary Cookie다. 점수는 만족 확률/품질 성과가 아니며 결과를 저장하지 않는다.

## 본인 탐색 추천

`POST /api/users/me/recommendations/exploration`은 현재 본인의 저장 취향과
[탐색 제공자](https://github.com/Pubburi/Recommendation/blob/main/docs/EXPLORATION-API.md)를 연결한다.
요청은 `{}` 또는 `{"limit":4}`다. limit은 기본4·정수1–12이고, 본인 ID를 `encodeURIComponent`한
`X-Pubburi-Feedback-Owner`가 필요하다. 쿼리·추가 JSON 필드·중복 키는400, 계정 불일치/만료는401이다.
본인 명시 취향·필수 가격/도수 조건과 `관심 없음`의 검수 상품군 전체를 반영한다. 활동과 `맞음`은
기존 취향 추천에서 소비한다. provider 호출 전후에 계정 수명·조건·관심 없음·현재 업무 사실·권위 pin을
별도 읽기 트랜잭션으로 대조한다. 변경은409, 제공 불가는 UNAVAILABLE로 구분하며 업무 쓰기는 없다.

`data`는 `source`, `catalogScope`, `result`, `products`다. 스코프는 REVIEWED_DEMO 또는
REVIEWED_LOCAL_CATALOG다. result는 B1의 열 필드와 `exploration_reason`, 같은 순서의
`selections:[{product_id,basis}]`를 담는다. 첫 `highest_relevance_anchor` 이후 `observed_text` 또는
`unknown_conservative`다. 관찰 불가를 다양성 관측으로 해석하지 않는다. 선택은 양수 점수·서로 다른
검수 상품군에 한정하고, products는 그 순서의 현재 Product7이다. 탐색 순서를 B1 점수로 재정렬하지
않는다. 기본 relevance 정책은 가중치0.7·최소 점수0이다. 정상 기준선은 `baseline_fallback`과 빈 배열이며
양수 근거가 없을 때 B0를 섞지 않는다. `below_relevance_floor`는 제공자 계약의 별도 상태이며 현재
고정 정책에서는 발생하지 않는다. 검수 부족·제공자 장애·계약 불일치는 UNAVAILABLE, result=null,
products=[]다. 캐시는 private, no-store·Vary Cookie다.

## 상품 상세의 유사 상품

현재 계정 카트를 기준으로 한 별도 [맥락 추천 계약](CART.md#현재-카트의-검수-맥락-추천)은
카트 revision/현재 업무 사실·hard 조건·관심 없음에 결속한다. 이 본인 취향 요청을 카트 ID로 대체하지 않는다.

공개 `GET /api/products/{id}/similar`는 현재 기준 상품에 대해 최대 4개의 `ProductResponse`를
ApiResponse `data.items`에 반환한다. 존재하지 않는 기준은 404다. 응답 캐시는 `no-store`다.
선택 query `includeExplanations=true`는 같은 순서의 `data.explanations:[{productId,explanation}]`를
함께 반환한다. 생략/false는 기존 wire와 제공자 입력을 보존하며 explanations 필드를 보내지 않는다.
query는 단일 true/false 리터럴만 허용하고 중복·추가·다른 값은400이다. true의 정상 성공에는 빈 결과나
모델 장애/미매핑이어도 explanations=[]가 존재한다. 설명은 기존 R04의 정확한 실제 기준 ID,
주종/확보 태그·도수/용량과 단위·양수 기여/상위3·생략 수를 대조한다. 잘못된 설명을 붙인 상품은
링크만 남겨 부분 성공시키지 않고 items/explanations를 함께 비운다. 설명은 원문 텍스트이며 HTML이 아니다.
설명에 보이는 상위3 기여의 합이 전체 점수나 만족 확률을 뜻하지 않으며 새 점수 필드는 제공하지 않는다.
기여와 같은 기여값의 상위 근거 순서는 제공자와 같은 Unicode code point 순서다. 원문 태그를
UTF-16 순서로 재배치하거나 표시를 위해 정규화하지 않는다.
호출은 container `REVIEWED_DEMO` 또는 `REVIEWED_LOCAL_CATALOG`의 검수 상품에 한정한다.
유효한 로컬 기준에만 선택 필드 `data.catalogScope:"REVIEWED_LOCAL_CATALOG"`가 붙으며 기존
`items`는 그대로다. family-browse 전용/미매핑 상품에는 이 필드가 없고 빈 배열이다. 모델 실패도
빈 배열이지만 로컬 기준의 scope는 유지한다. 로컬 원천/현재 사실의 불일치 또는 호출 중 변경은409다.
기존 DEMO의 만료/상품·특성 변경은 기존대로200 빈 배열이다.

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

같은 업무 snapshot에서 각각 검수/발행한 여러 원천은 기존 PATH/SHA에 **로컬 합성 manifest**를
고정해 한 B1 요청으로 소비할 수 있다. 최상위 필드는 `schema_version:1`,
`algorithm:"checked-backend-demo-bundle-set-v1"`, `bundles`뿐이다. 각 원소는 원본 bundle 파일의
`path`, `sha256`만 가진다. manifest 자체와 각 원본의 전체 바이트 SHA를 모두 확인한다.
path는 manifest 디렉터리 기준 상대 경로이며 그 트리 밖이나 파일/중간 디렉터리의 심볼릭 링크는
거절한다. 원본 파일/수령증을 새 JSON으로 재직렬화해 해시하거나 새 만료를 부여하지 않는다.

2–16개 원본의 `business_snapshot_sha256`, `snapshot_origin`, `evidence_kind`, `data_scope`가
모두 같아야 한다. 원천 catalog/매핑/산출물 hash는 각 원본에 그대로 결속되므로 서로 달라도 된다.
각 원본을 아래 기존 v1 규칙으로 검수하고 backend ID와 catalog_record_id의 전역 중복을 거절한다.
전체 후보는1000개, manifest와 모든 원본의 바이트 합은10MiB 이하이며 원본별 상한으로 우회하지
못한다. 입력 순서와 원문 sku_group을 보존하고 이름으로 상품군을 합치거나 나누지 않는다.
전체 만료는 원래 만료 중 가장 이른 시각이다. 하나라도 불일치/누락/만료/파일 변경이면 일부만
사용하지 않고 UNAVAILABLE이다. 단일 v1·명시 family opt-in·기존 응답 scope와 현재 DB의 전체
후보/특성 재검수는 그대로다. 이 조합은 새 원천 승인이나 서비스 SKU 승격이 아니다.

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

### 안정 원천과 현재 업무 값의 분리

비상업 로컬 aT catalog는 위 DEMO와 별도 입력이다. Recommendation이 검수·발행한
`at-reviewed-local-catalog-v1`의 원문을 `PUBBURI_REVIEWED_CATALOG_PATH`/`SHA256`으로,
Backend의 명시 매핑을 `PUBBURI_REVIEWED_MAPPING_PATH`/`SHA256`으로 각각 고정한다.
네 값 중 일부만 설정하거나 기존 CONTENT_CATALOG 입력/family opt-in과 함께 설정하면 거절하며
DEMO로 되돌려 권위를 약화하지 않는다. 요청에서 설정/상품 매핑을 선택하지 않는다.

매핑의 정확한 최상위 필드는 `schema_version:1`, `algorithm:"reviewed-local-catalog-mapping-v1"`,
`authority_sha256`, `catalog_version`, `source_version`, `business_source_id`, `records`다.
`business_source_id`는 현재 승인 import의 불변 source UUID다. records는 원 authority와 같은
순서의 `{catalog_record_id,backend_product_id,sku_group}`이며 모든 원 record를 정확히 한 번
매핑한다. 업무 ID 중복/누락·다른 source UUID·원 source_variant_group과 다른 그룹은 거절한다.
이름/순서 유사도로 다른 업무 상품을 추정하지 않는다. 두 파일은 원문 SHA·정확한 typed shape와
합계10MiB/1000행 상한을 확인하며, 권위 pin은 두 원 SHA에 함께 결속한다.

안정 원천에는 원 dataset/version·capture·검수 시각·근거 SHA와 이름/제조사·주종·단일 도수/용량만
있다. 이 원천 사실에는 업무 snapshot의5분 만료를 붙이지 않으며 기존 DEMO 만료는 그대로다.
원 `약주`는 명시 map으로만 `약 · 청주`에 대응한다. 현재 업무 이름/주종/도수와 feature 용량이
안정 사실과 같아야 하며, 하나라도 불일치하면 전체를409로 거절한다. 원 authority 자체가 깨진
경우 초기 UNAVAILABLE이며 요청 중 pin이 바뀌면409다. 원천 재감사/권리 선택은 Recommendation
소유이며 원자료나 이미지/설명/맛·향을 Backend가 추가 승인하지 않는다.

각 요청의 짧은 RR에서 **현재 시연 가격**과 업무행 존재 여부를 조합한다. 가격은 aT 판매가가
아니며 `available`은 판매/재고 확인이 아니다. 삭제된 명시 매핑은 알려진 원 사실/그룹을 유지한 채
available=false·price_krw=null로 전달해 후보에서 제외하고 나머지 유효 후보를 보존한다. DB 태그·
설명·이미지와 generic UI fallback 이미지는 모델 원천에 넣지 않는다. 호출 전후 전체 원 ID로
현재 사실/가격/삭제·hard 조건·관심 없음·epoch와 두 원 pin을 다시 대조하며 변화는409/401이다.
게시된 실제 상품 ID로만 상세/피드백/카트에 연결하며 원 raw행을 자동 서비스 상품으로 승격하지 않는다.

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

[기존 DEMO 검증/한계](https://github.com/Pubburi/Backend/blob/6fda9d3c2fb42e7d118e15602e601389f92695dd/docs/plans/2026-11-04-owned-content-recommendations.md)는 당시 고정 근거로 보존한다.
로컬 검수 catalog 연결은 실판매/재고·사람의 만족도·딥러닝 우위·기본 PostgreSQL cutover 완료를 뜻하지 않는다.
