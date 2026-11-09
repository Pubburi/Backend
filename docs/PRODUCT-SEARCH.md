# 현재 상품 검색·필수 필터 계약

`GET /api/products`는 기본 DB 조회와 명시한 엔진 읽기를 같은 공개 계약으로 제공한다. [부분 OpenAPI](openapi/product-search.json)와 실행
`/v3/api-docs`가 제공자 계약이며 기존 ApiResponse·ProductResponse 일곱 필드와 PageResponse를
유지한다. 기본 Maven JAR은 PostgreSQL/JPA이며 명시 MySQL baseline도 같은 계약을 유지한다. 실제 DB 선택은 기존 local selector를 따른다. 한국어 질의 품질·추천 재정렬과 기본 DB 전환은 별도다.

## 상품명 자동완성

`GET /api/products/suggestions?q=...`는 현재 DB의 상품명 접두어 후보를 최대8개 반환한다.
응답은 `ApiResponse.data.items`의 `{id,name,type,price}`이며 price는 현재 원화 가격이다.
DB에 설치된 이름 collation의 오름차순, 동률일 때 ID 오름차순이다. 이름이 같아도 서로 다른
상품은 같은 항목으로 합치지 않는다. 선택한 ID로 기존 상품 상세를 열 수 있다.

`q`는 한 번만 허용한다. 유니코드 White_Space의 앞뒤 공백을 제거한 뒤 NFC로 정규화한다.
정규화 결과가 0–1 코드 포인트면 조회 없이 빈 목록을 반환한다. 100을 넘거나 제어문자,
누락·중복·추가 query가 있으면400이다. 바인딩한 LIKE의 접두어 뒤에만 와일드카드를 붙이므로
입력의 `%`, `_`, `!`, 역슬래시는 문자 그대로 처리한다. 이름 안쪽이나 주종은 검색하지 않는다.
읽기 실패는500이며 성공·오류 응답에는 캐시를 사용하지 않는다. 기존 상품 목록의 q/필터/정렬은
그대로 유지한다. 개인화·유사어·오타 허용은 적용하지 않는다.

기존 `PUBBURI_SEARCH_ENABLED=true`를 명시하면 같은 source-bound OpenSearch의 `name.raw`
[literal prefix](https://docs.opensearch.org/latest/query-dsl/term/prefix/)로 삭제 표식을 제외한 ID 후보만
읽는다. source/물리 UUID·전체700ms·128KiB·부분 응답 거절은 아래 공개 검색과 공유한다.
원격 호출 뒤 현재 DB의 같은 접두어/이름·ID 정렬/최대8개를 다시 읽어 **반환 ID순이 모두 같을 때만**
ENGINE_VERIFIED로 분류한다. 이름 변경·삭제·상위 후보 누락·collation 차이·엔진 장애는
DATABASE_FALLBACK이며 응답 필드는 항상 현재 DB다. 비활성 또는 조회가 필요 없는 짧은 입력은
DATABASE다. 이 분류는 성공의 `X-Pubburi-Search-Source` 헤더만 추가하고 JSON은 유지한다.
최대8개 밖의 전체 색인 일치를 보증하지 않으며 DB 확인을 유지하므로 속도 개선으로 세지 않는다.

## 조건과 단위

| 파라미터 | 의미·입력 |
|---|---|
| type / q | 기존 주종 정확 일치 / 이름·주종의 MySQL LIKE 검색. 앞뒤 공백과 빈 값의 기존 처리를 유지 |
| minPriceKrw / maxPriceKrw | 포함하는 원화 가격 범위. 0..2,147,483,647 정수, ASCII 숫자 1–10자리 |
| minAbvPercent / maxAbvPercent | 포함하는 도수 범위. 0..100, ASCII 소수 표기로 소수점 아래 최대 두 자리. 지수·NaN·무한대 제외 |
| tasteTag / aromaTag | 각각 반복 query로 최대 20개, 각 1–40자. trim·소문자 정규화 뒤 중복 거절. 쉼표는 문자열의 일부 |
| sort | 기존 popular / priceAsc / priceDesc / name. 미지정·지원하지 않는 값은 기존 인기순 |
| page / size | 기존 기본 page1/size12·최대 size100과 0/음수의 기본값 정규화 유지 |

정규화된 `(page - 1) * size`는 상품 목록의 int OFFSET 계약인0..2,147,483,647에 들어가야 한다.
long으로 먼저 계산해 범위를 넘으면400 BAD_REQUEST로 거절하며, 0이나 양수로 래핑된 경우도
같이 차단한다. 지원되는 높은 페이지는 기존처럼 조회하고 결과가 없으면 빈 목록을 반환한다.
이는 다른 목록 API나 공통 PageCriteria 정책을 전역 변경하는 계약은 아니다.

추가한 범위는 미지정만 무제약이다. 실제 숫자 `0`은 unknown이나 미지정으로 바꾸지 않는다.
빈 숫자·잘못된 문법/범위·역전된 최소/최대·빈/긴/중복 태그는 고정 ApiResponse의400
`BAD_REQUEST`다. 파라미터 이름은 위 이름을 사용하며 짧은 단위 없는 alias는 제공하지 않는다.
q의 기존 `%`/`_` LIKE 의미를 그대로 유지하며 태그의 정확 일치와 혼동하지 않는다.

지정한 가격·도수·주종·q·태그 조건은 **모두 AND**다. 같은 종류의 태그도 요청한 **모든 값**을
만족해야 한다. `tasteTag=dry&tasteTag=sweet`와 `tasteTag=dry%2Csweet`는 각각 두 태그와
쉼표가 들어간 한 태그이므로 다르다. F03의 관리자 명시값/known 상태를 사용하며 미확인 맛·향을
생성하거나 유사어로 바꾸지 않는다. 특성 행이 없거나 null(unknown)·검수한 빈 배열[]이면 양성
태그 조건을 만족하지 않는다. 태그 조건을 생략하면 그런 상품도 제외하지 않는다.
sourceName·known 표시 자체가 외부 사실이나 사용 권리의 승인을 뜻하지 않는다.

기존 abv 컬럼은 FLOAT다. 상품 값을 소수 두 자리로 반올림하지 않고 경계 파라미터만
`CAST(? AS FLOAT)`로 저장형과 맞춘다. 따라서 기존5.555 상품을 min5.56의 조건에 반올림해
포함시키지 않는다. [공식 CAST](https://dev.mysql.com/doc/refman/8.4/en/cast-functions.html)와
[비교 변환](https://dev.mysql.com/doc/refman/8.4/en/type-conversion.html)의 근삿값 규칙을 따른다.
이는 컬럼을 DECIMAL로 바꾼 것이나 완전한 십진 정밀도 보증은 아니다. 기존 상품 응답의 도수
표현을 바꾸거나 소수 세 자리의 저장값을 임의 보정하지 않는다.

## 페이지·현재 데이터

items와 total은 같은 공통 필터 SQL을 사용한다. 태그별 EXISTS로 확인해 여러 태그가 상품 행을
증식시키거나 total을 부풀리지 않는다. 각 정렬은 기존 id 오름차순 tie-break를 유지한다.
한 요청의 목록/개수는 짧은 read-only REPEATABLE READ 트랜잭션의 같은 스냅샷에서 읽으며
이 DB 읽기는 원격 호출을 포함하지 않는다. 다른 페이지/재조회 요청까지 같은 상품 상태를 고정하는 보장은 아니다.

결과가 없으면 items=[], total=0, totalPages=0, hasNext=false다. DB 오류는500이며 빈 성공으로
숨기지 않는다. 가격/도수는 현재 업무 컬럼을 그대로 사용한다. 판매 상태/재고 필드가 없으므로
판매·매진·원천 사실 확인을 완료했다고 주장하지 않는다. 이 GET은 상품/특성/주문수·계정 취향을
수정하지 않고 추천 서버·외부 키·AWS 없이 동작한다.

## 검색 결과 0건의 대체 제안

현재 DB에서 원래 `q`의 **total=0**인 경우에만 `X-Pubburi-Search-Hint`를 선택적으로 반환한다.
값은 `ALIAS:` 또는 `TYPO:` 뒤에 UTF-8 percent encoding한 제안 검색어를 붙인다. 공백은 `%20`이다.
`약주`의 별칭은 검수 원천 주종 매핑의 `약 · 청주`다. 오타 후보는 완성형 한글 3–20자에서
한 글자를 바꿔 나오는 기존 주종이 정확히 하나일 때만 허용한다. 예를 들어 `과실쥬`는 `과실주`를
제안할 수 있다. 정확한 기존 주종, 여러 후보로 갈리는 입력, 짧은 입력, LIKE 와일드카드나 혼합
문자가 있는 입력에는 오타 제안을 붙이지 않는다. 별칭에 일치하면 그 별칭 하나를 검증한다.

제안은 같은 주종·가격·도수·맛/향 AND 조건과 정렬로 현재 DB의 첫 페이지 한 건을 확인한다.
같은 조건의 상품이 있을 때만 헤더가 붙고 JSON·total·페이지·엔진 분류에는 원래 질의가 반영된다.
선택적 확인 조회만 실패하면 제안 헤더 없이 이미 조회한 원래 페이지를 반환한다.
페이지가 범위를 벗어났지만 total이 양수인 경우에도 원래 페이지를 반환한다. 제안을 선택하면
기존 상품 검색을 새 `q`로 다시 실행한다.

## 명시 엔진 읽기와 현재 DB fallback

`PUBBURI_SEARCH_ENABLED=true`, `PUBBURI_SEARCH_ENDPOINT`, `PUBBURI_SEARCH_INDEX`를 명시하면
같은 GET이 source-bound 로컬 OpenSearch를 읽는다. 기본 false는 종전 DB 경로 그대로이며 기본
기동/Compose에 엔진을 추가하지 않는다. origin은 운영자가 지정한 numeric loopback HTTP,
index는 기존 products-v1 physical 이름만 허용한다. 요청으로 host/index/source를 받지 않는다.

현재 DB의 기존 relay source UUID를 실제 DAO에서 읽고, 물리 index의 owner/schema/data-kind/
source와 UUID를 검색 전후 확인한다. 앱은 색인 생성/변경·relay binding/ACK/source reset을 하지 않는다.
검색은 deleted=false와 모든 필수 범위·주종·known 태그 AND, 기존 네 정렬/ID tie와 정확 total을
요청한다. 엔진 source 문서는 받지 않으며 임의 후보/부분 shard/timeout·부정확 total은 거절한다.
connect200ms·metadata/검색/재확인을 합친 전체700ms·응답별128KiB와 중복 JSON/추가 문서/
redirect 거부를 적용한다. 엔진 원격 대기는 업무 DB 트랜잭션 밖이다.

그 뒤 기존 canonical 서비스의 짧은 RR items/count를 읽는다. **현재 페이지의 ID순과 total이 모두
같을 때만** 엔진 확인으로 분류하고, 응답 필드는 현재 DB의 Product7을 쓴다. 누락·지연·삭제·
필수조건/정렬·collation 차이·엔진 장애에는 같은 모든 조건의 DB 응답을 반환한다. DB 예외는
500이며 빈 성공으로 바꾸지 않는다. 엔진이 빈 결과라도 현재 DB와 같아야 확인 결과다.
이 단계는 DB 확인 쿼리를 유지하고 원격 비용을 추가한다. 속도/DB 부하 감소나 검색 품질 개선을
주장하지 않으며 현재 페이지/total의 일치를 전체 색인의 정합성 증명으로 확대하지 않는다.

JSON은 그대로이고 `X-Pubburi-Search-Source`는 DATABASE(비활성), ENGINE_VERIFIED(현재 페이지/
total 일치), DATABASE_FALLBACK(오류/불일치/미지원)으로 구분한다. q의 기존 LIKE `%`/`_`/backslash,
100 UTF-16 단위를 넘는 q와 from+size>10000은 DB 의미를 그대로 사용한다. 그 밖의 q는 keyword
필드의 literal wildcard 후보를 만들고 DB와 대조한다. Nori 의미 검색/동의어/오타로 q를 바꾸지 않는다.
엔진에서 지원하지 않는 collation/비용 정책도 필터를 제거하지 않고 DB로 대체한다.

[wildcard의 의미/비용 제한](https://docs.opensearch.org/latest/query-dsl/term/wildcard/),
[페이지 범위](https://docs.opensearch.org/latest/search-plugins/searching-data/paginate/),
[검색 API](https://docs.opensearch.org/latest/api-reference/search-apis/search/)를 따른다.
명시한 index는 기존 [source-bound relay](SEARCH-RELAY.md)로 준비하며 공개 앱이 자동 재색인하거나
유실된 index/alias를 복구하지 않는다. [구현/검증 기록](https://github.com/Pubburi/Backend/blob/41eeffb3257a6b26d6ba530d58f67ff0bc443c11/docs/plans/2026-11-04-public-search-fallback.md)을 따른다.

예를 들어 다음은 원화/도수와 명시 태그를 모두 만족하는 상품의 첫 페이지를 조회한다. 태그는
계약 설명용 값이며 실제 상품의 맛을 주장하지 않는다.

~~~text
/api/products?minPriceKrw=1000&maxPriceKrw=20000&minAbvPercent=0&maxAbvPercent=10&tasteTag=dry&tasteTag=sweet&aromaTag=floral&sort=priceAsc&page=1&size=12
~~~

검증·제한은 [작업 계획](https://github.com/Pubburi/Backend/blob/de7f80747932996678577b0ce9dfd779667846ed/docs/plans/2026-11-01-product-search-filters.md)과 [TESTING](TESTING.md)에 둔다.
현재는 두 DB의 필수조건·이름 자동완성과 명시 엔진 읽기/현재 DB 대체를 제공한다. 전체 F02·재색인/유실 복구·
기본 스택 전환과 부하/검색 품질 인수 완료를 뜻하지 않는다.
