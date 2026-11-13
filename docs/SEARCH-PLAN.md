# 상품 검색 API·색인 동기화 계획

2026-10-31 승인된 구현 계획과 현재 증가분이다. [로컬 엔진·명시 ID 재생](LOCAL-SEARCH.md)을
구현하고 [업무 outbox 생산](SEARCH-OUTBOX.md) 및 [명시 실행 relay](SEARCH-RELAY.md)를 연결했다.
기본 기동 자동 실행·재색인 세대 전환·기본 스택 전환과 S02 전체 완료는 구분한다.
R01–R08·F01–F12 전체20개 MVP의 구현·연결·필수 검증 기한은 2026-12-31 Asia/Seoul이다.
11월22일 DB/React·12월13일 P1 목표를 유지하고 P2는 12월14–31일에 완료한다.
Notion의 기존 할 일을 모두 완료하면 곧바로 별도 최적화를 한 건씩 진행한다. 최종 기한은 2027-01-15 Asia/Seoul이다.
S01–S05는 F02·R06·R02의 기술 작업이며 R 8개 + F 12개 = 20개 기능과 중복 합산하지 않는다.

최신 확정 업무 DB 목표는 [PostgreSQL18.6/JPA 전환](POSTGRESQL-MIGRATION.md)이다. 아래 기존
MySQL 구현/측정은 이관 전 기준으로 보존하며 PostgreSQL/JPA/Psycopg3의 outbox·현재 상품
재조회·필수조건 대체 검색을 DB03–DB06에서 재검증한다. schema 도입만으로 검색 전환을 완료하지 않는다.
[PostgreSQL 명시 relay 증분](https://github.com/Pubburi/Backend/blob/ac2b0cd15f77f85feffaefbd74b1a228e2839399/docs/plans/2026-11-04-postgresql-search-relay.md)은 기존 MySQL 경로와
같은 Engine/CLI에 Psycopg Store를 연결한다. 이어 [공개 검색 증분](https://github.com/Pubburi/Backend/blob/41eeffb3257a6b26d6ba530d58f67ff0bc443c11/docs/plans/2026-11-04-public-search-fallback.md)은
기존 GET의 opt-in 엔진 읽기를 현재 DB의 RR 페이지/total과 대조한다. 모든 오류/불일치는 같은
필수조건의 PostgreSQL/MySQL 응답으로 대체한다. 기본 스택·재색인/유실 복구·전체 DB05–06은 후속이다.

Backend는 검색 API·업무 상품 원본·색인 동기화·추천 서버 연동·장애 대체 검색을 소유한다.
주류 데이터 수집·정제·특성 설계·추천 모델 학습·평가의 정본은
[Recommendation 데이터·모델 계획](https://github.com/Pubburi/Recommendation/blob/main/docs/DATA-AND-MODEL-PLAN.md)이다.
Backend는 해당 추천 구현을 중복 보관하지 않고 필요한 상품/취향 계약과 호출 경계를 관리한다.

## 검색 범위와 연동 책임

- 목표 PostgreSQL은 업무 상품 원본이고 OpenSearch는 재생 가능한 검색 색인이다. 현재 MySQL 원본은 이관 전까지 보존한다. 상품 변경·색인 동기화·검색 API·인증·장애 복구는 Backend가 담당한다.
- Frontend는 React 검색 화면·자동완성·필터·URL 상태·최신 요청 반영을 담당한다.
- Recommendation은 검색 조건을 통과한 후보의 점수·이유를 계약에 따라 반환한다. Backend는 반환 결과의 최신 상품 상태와 필수 조건을 다시 확인한다.
- OpenSearch는 로컬 Docker로 실행한다. 구현 시 엔진/한국어 플러그인 버전을 맞춰 고정하고 AWS 연결을 요구하지 않는다.
- `/api`·ApiResponse·세션·기존 페이지/정렬 계약을 보존한다. 개인화 후보 범위와 페이지/total의 의미는 API 계약에서 명시한다.
- 현재 상품에는 name/type/price/abv/img/order_count가 있다. 맛·향·설명·용량 검색은 F03에서 출처 검수한 필드에만 적용한다.
- 자동완성·한국어 분석·검수 동의어·제한된 오타 허용을 각각 검증한다. 형태소 분석만으로 초성/로마자/모든 오타가 해결됐다고 하지 않는다.
- 자유 문장의 의미 해석·벡터/LLM 검색은 이번 범위에 자동 포함하지 않는다. R06은 선택한 문구를 명시적 검색 조건으로 연결한다.

## 검색 기술 작업

목표일은 기존 마일스톤 안에 배치한 내부 목표다. S01의 MySQL 필수조건 계약은
[상품 검색](PRODUCT-SEARCH.md)과 [진행 기록](https://github.com/Pubburi/Backend/blob/de7f80747932996678577b0ce9dfd779667846ed/docs/plans/2026-11-01-product-search-filters.md)에서 다루며
S01 전체·OpenSearch와 S02–S05 완료로 표시하지 않는다.

| ID | 담당 | 우선순위·목표일 | 선행 조건 | 완료 기준 |
|---|---|---|---|---|
| S01 | BE·AI / 조정자 | P0 · 2026-11-08 | C03와 공동 | 질의·필터·정렬·개인화 후보 범위·페이지/total·빈 결과/장애 계약과 스키마 정의, 기존 API 호환 검증 |
| S02 | BE | P1 · 로컬 2026-11-22 / 동기화 11-29 | S01, 검수 카탈로그·F03 | 버전 고정 로컬 엔진, ID 기반 색인, 상품 변경과 원자적으로 기록하는 outbox 또는 동등한 재생 가능 방식. 중복·순서 역전·재시도·삭제/중지·재시작·재색인 검증 |
| S03 | BE·FE | P1 · 2026-12-06 | S02·F03·FE03 | 상품 검색·자동완성·검수 동의어·오타 대응, 주종/가격/도수/확보 태그 필터, 기존 정렬·URL 복원·최신 요청 보장 |
| S04 | BE·FE·AI | P1 · 2026-12-13 | S03·R01·R02 | R06 조건 연결, 조건을 통과한 후보에 대한 추천 서버 호출, 최신 DB의 가격/판매 상태/제외 조건 재확인, 신규 취향·추천 장애의 기본 정렬 |
| S05 | BE·FE·AI | 필수 인수 2026-12-31 / 추가 비교·최적화 목표 2027-01-15까지 | S03·S04, C04 필수 인수·Notion 기존 할 일 완료 뒤 C05 | MVP 전 엔진 지연/중단·색인 누락/지연·재처리·재색인 중 변경과 필수 조건을 유지하는 대체 검색을 검증한다. 목표 PostgreSQL의 동등성을 확인하고 이관 전 MySQL 기준을 보존한다. 기존 할 일을 마치면 고정 정답 질의로 검색 품질과 같은 조건의 응답 시간을 비교하고 확대 회귀를 진행한다. |

추천에 필요한 모델 fit·계약 연결·권한/최신 상품·제외/필수 조건·장애 fallback 회귀는
12월31일 전 완료 대상이다. 학습/평가 상세는 Recommendation 정본에 두고 Backend는 실제
호출·재조회·대체 흐름을 확인한다. C05는 전체20개 MVP의 main 병합과 세 실제 서버의 필수 회귀를 포함해
Notion의 기존 할 일을 모두 완료하면 시작한다.

## 동기화·재색인·장애 경계

S02는 로컬 기반부터 준비하고 실제 상품 동기화는 F03을 충족한 뒤 연결한다.
원천 버전·삭제 상태를 보존해 오래된 갱신으로 상품이 부활하지 않게 한다.
bulk의 개별 실패를 처리하고 재색인은 변경 재생을 완료한 뒤 alias를 원자적으로 전환한다.
검색엔진의 버전 번호만으로 영구적인 삭제 보호가 된다고 가정하지 않는다.

개인화가 상위 일부 후보에만 적용되면 그 범위와 페이지 의미를 명시한다.
추천 서버가 돌려준 후보는 최신 DB의 가격·판매 상태·필수/제외 조건을 다시 확인한다.
추천 서버 장애에는 기본 검색 정렬을, 검색엔진 장애에는 현재 선택한 업무 DB의 같은 필수조건 검색을 제공한다.
대체 검색도 가격/도수/확보 태그 등 필수 조건을 유지하며 현재 LIKE 쿼리로 무조건 되돌리지 않는다.

## 검색 검증과 인계

외부 사용자를 기다리지 않고 고정된 검색 질의·시연 입력으로 계약과 실패 동작을 검증한다.
가격/도수/주종·제외·누락/상충 조건, 최신 요청, 중복/삭제 상품, 빈 결과, 엔진/추천 서버 지연과 장애를 포함한다.
질의의 기대 조건은 검색 점수와 독립적으로 고정하고 사실로 확인할 수 없는 맛/향을 정답으로 만들지 않는다.
검색 성능은 같은 입력·카탈로그·부하·환경의 MySQL 기준선과 변경 후 원값/분포를 비교해 기록한다.
시연 주문수나 검색 결과를 실제 이용자 인기·만족도·구매 전환 성과로 해석하지 않는다.

현재 구현은 가격/도수·명시 태그 필수조건과 페이지/정렬을 두 DB에서 보존하며 기존 공개 GET의
명시 엔진 읽기/현재 DB 대조·대체를 제공한다. 현재 DB 확인 쿼리를 유지하므로 성능 개선으로 보지 않는다. [상품명 자동완성](PRODUCT-SEARCH.md#상품명-자동완성)은
현재 두 DB의 이름 접두어를 별도 공개 API로 제공하고 명시한 엔진의 반환 ID순을 현재 DB와 대조한다.
삭제·변경·누락·source/부분 응답 오류에는 같은 DB 후보로 대체한다. [빈 검색 결과 제안](PRODUCT-SEARCH.md#검색-결과-0건의-대체-제안)은
검수 별칭과 한 글자 주종 오타를 같은 필수 조건으로 확인해 명시 선택에 제공한다.
선택적 OpenSearch3.9.0/nori·strict ID 문서/버전·삭제 표식·명시 재생은
[S02 기록](https://github.com/Pubburi/Backend/blob/e7a957babd094442d9e027174bb795aefccc4219/docs/plans/2026-11-01-local-search-replay.md)에 둔다. 원자적 업무 버전/outbox 생산은
[생산 증가분](https://github.com/Pubburi/Backend/blob/53c15c06327f4518b9c269ac95ccdd68b3f05c81/docs/plans/2026-11-01-product-search-outbox.md), 개별 claim/ack·lease 재전달은
[relay 증가분](https://github.com/Pubburi/Backend/blob/53c15c06327f4518b9c269ac95ccdd68b3f05c81/docs/plans/2026-11-01-durable-search-relay.md)이다.
[PostgreSQL 검색 세대 전환](SEARCH-RELAY.md#postgresql-검색-세대-전환)은 쓰기 중단·전체 이력 검증·별칭 이동/DB 갱신 사이의 재실행을 제공한다. 보존 정리·개인화 검색·성능 개선은 후속이다.
R06의 [본인 문구 caller](SEARCH-PHRASES.md)는 현재 명시 취향과 별도 선택만 기존 제공자에
전달하며 원격 뒤 현재 snapshot/수명을 확인한다. 조건 검증·장애/빈 결과는 구분하지만 상품을
검색하지 않는다. React의 명시 선택/문구 클릭·실제 검색/빈 결과와 S04 전체는 별도 후속이다.
추천 데이터·학습·평가와 관련된 DAT01–DAT04·AI01–AI04의 상세 진행은 Recommendation 정본에서 관리한다.

기술 근거: [로컬 Docker](https://docs.opensearch.org/latest/install-and-configure/install-opensearch/docker/),
[자동완성](https://docs.opensearch.org/latest/search-plugins/searching-data/autocomplete/),
[필터](https://docs.opensearch.org/latest/search-plugins/filter-search/).

[R02/R04 본인 B1 caller](CONTENT-RECOMMENDATIONS.md)는 현재 취향·검수 container-DEMO와
현재 업무 사실/필수조건을 기존 content·typed 설명에 연결하는 부분 증분이다. 기존 공개 B0/R06와
독립이며 개인화 검색 순위·전체 서비스 catalog/품질·R02/R04/S04 완료로 세지 않는다.
