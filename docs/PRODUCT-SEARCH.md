# 현재 상품 검색·필수 필터 계약

`GET /api/products`는 공개 MySQL 조회다. [부분 OpenAPI](openapi/product-search.json)와 실행
`/v3/api-docs`가 제공자 계약이며 기존 ApiResponse·ProductResponse 일곱 필드와 PageResponse를
유지한다. OpenSearch·자동완성·한국어 분석·추천 재정렬을 구현한 API로 표시하지 않는다.

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
원격 호출이 없다. 다른 페이지/재조회 요청까지 같은 상품 상태를 고정하는 보장은 아니다.

결과가 없으면 items=[], total=0, totalPages=0, hasNext=false다. DB 오류는500이며 빈 성공으로
숨기지 않는다. 가격/도수는 현재 업무 컬럼을 그대로 사용한다. 판매 상태/재고 필드가 없으므로
판매·매진·원천 사실 확인을 완료했다고 주장하지 않는다. 이 GET은 상품/특성/주문수·계정 취향을
수정하지 않고 추천 서버·외부 키·AWS 없이 동작한다.

예를 들어 다음은 원화/도수와 명시 태그를 모두 만족하는 상품의 첫 페이지를 조회한다. 태그는
계약 설명용 값이며 실제 상품의 맛을 주장하지 않는다.

~~~text
/api/products?minPriceKrw=1000&maxPriceKrw=20000&minAbvPercent=0&maxAbvPercent=10&tasteTag=dry&tasteTag=sweet&aromaTag=floral&sort=priceAsc&page=1&size=12
~~~

검증·제한은 [작업 계획](plans/2026-11-01-product-search-filters.md)과 [TESTING](TESTING.md)에 둔다.
현 구현은 S01/F02의 MySQL 필수조건 증가분이며 검색 엔진 색인/동기화·전체 F02와 부하/검색 품질
인수 완료를 뜻하지 않는다.
