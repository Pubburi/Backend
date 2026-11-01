# S01/F02 MySQL 필수 필터 증가분

기준 main `5e4dc006`, 작업 codex/product-search-filters. 기존 목록의 Product7/PageResponse와
type/q·정렬·페이지 규칙 위에 가격·도수·명시 맛/향 필수조건을 추가한다. [제공자 계약](../PRODUCT-SEARCH.md)을
정본으로 사용하며 다른 레포의 UI·데이터/학습을 복제하거나 완료로 처리하지 않는다.

## 선택과 범위

단위를 드러내는 minPriceKrw/maxPriceKrw·minAbvPercent/maxAbvPercent와 반복 tasteTag/aromaTag를
선택했다. 미지정과 실제0을 구분하며 범위/문법·태그 정규화/중복을 SQL 전에 검증한다. 태그는
같은 종류 안에서도 all-match이며 종류 사이·기존 검색과도 AND다. F03의 known 표시를 요구해
특성 미저장·unknown/null·검수[]를 긍정 태그로 추정하지 않는다. 쉼표가 있는 합법적 태그를
CSV 변환하지 않도록 query 원문 값 배열을 사용한다.

JOIN 뒤 DISTINCT나 전체 상품을 메모리에서 필터링하는 대안 대신 상관 EXISTS를 사용한다.
목록/개수의 공통 SQL fragment와 짧은 read-only RR 스냅샷으로 같은 요청의 total 조건을 유지한다.
기존 가격/이름 정렬의 id tie-break와 미지원 sort의 인기순 처리는 유지한다. 새 스키마·seed 전환이나
DB 초기화는 없고 원격 서비스도 호출하지 않는다. LIKE·태그 조건의 비용을 줄였다는 실측 주장은 없다.

FLOAT 원본을 DECIMAL(…,2)로 반올림하면 기존5.555를 min5.56에 잘못 포함시킬 수 있다.
공식 MySQL8.4 CAST/비교 문서를 확인하고 파라미터만 FLOAT 저장형으로 변환하는 방법을 선택했다.
기존 응답 정밀도·저장값과 근삿값 한계는 보존한다. 실제 MySQL의5.1/5.55/5.555 및 min=max
경계 검증을 조정자에게 인계했다.

## 검증·인계

최종 `./scripts/check.sh`의 Java17 Maven120개·패키징, Python 도구/안전 게이트28개와 저장소
검사가 통과했다. 단위/MVC는 반복 태그의 쉼표/정규화·unknown에 대한 SQL 정책·잘못된 가격/도수·
역전/빈/중복/긴/많은 태그의 조기400, 현재 Product7/PageResponse를 확인한다. MyBatis BoundSql은
목록과 COUNT의 동일 조건·bound 파라미터·정렬을 확인한다. 이는 실제 DB 실행이나 동시 변경
스냅샷 검증을 대신하지 않는다. 실행 OpenAPI는 단위형 이름·숫자 범위·반복 form/explode 태그·
JSON PageResponse를 검사한다.

표준 변이 smoke에 고유 합성 상품5개를 실제 관리자 API로 생성해 가격/도수·검수/빈/미확인 태그·
AND·literal comma·정렬/페이지·잘못된 입력14개와 삭제를 검사하는 경로를 추가했다. 도수0과
maxPriceKrw0의 빈 결과를 구분한다. 이 데이터는 검증 입력이며 주류 원천 사실/실사용자 성과가 아니다.
쓰기 전에 기존 disposable DB/container/run marker 게이트를 모두 요구한다.

불변 jar SHA-256: `3fbb61317289c8ea6036efa2243898a21faa5b1c21ed45deed66cd30e28be767`.
조정자는 같은 jar·전용 MySQL8.4의 전체 `./scripts/smoke.sh` 12범주를 통과했다. 새 필터의 실제
관리자 생성/특성 입력·공개 검색과5.1/5.55/5.555·0 경계·AND/unknown/[]/literal comma·페이지/
정렬·잘못된 입력14개/삭제·런타임 문서를 확인했다. 기존 세션·주문/rollback·삭제 이력·명시취향·
찜과 기본 목록도 유지됐다. 이 범주/입력 수를 서비스 성능이나 실사용자 오류율 개선으로 해석하지 않는다.
DB 동시 변경 중 count/items 스냅샷의 제어 실험이나 전체 부하 검증을 수행했다고 주장하지 않는다.
기존 DB·공유 포트는 사용하지 않았고 원문 로컬 로그/개인 경로/자격정보를 공개 근거로 옮기지 않는다.

재현은 폐기 DB의 안전 marker를 준비한 뒤 `PUBBURI_SMOKE_MUTATING=1 ./scripts/smoke.sh`를
실행한다. source/tag는 합성 검증값이며 외부 데이터 수집이나 원천 권리 승인과 무관하다.

## 독립 결함·사례 비교·남은 작업

별도로 PageCriteria의 page=2,147,483,647·size100이 int offset -200을 만드는 조건을 정적으로
관찰했다. 입력 범위 수정을 잠시 작성했지만 필터와 독립된 목적이므로 이번 소스/테스트에서
제거하고 제안만 ignored에 보존했다. 이후 조정자가 기준 불변 jar23f5920…·전용 MySQL에서
page1/size100의200과 극단 페이지의500 INTERNAL_SERVER_ERROR를 각 한 번 확인했다.
이는 필터 증분에서 미수정이었던 별도 BE08 경계이며 이번 필터의 개선으로 합산하지 않는다.
후속 구현·재측정은 [BE08 계획](2026-11-01-product-page-offset.md)을 따른다.

새 필터의 정책/정합성 설계는 현재 README의 권한 회수·주문 금액/이력 유실 같은 실제 결함 전후보다
강한 교체 근거가 아니므로 네 사례와 순서를 유지한다. 결과는 신규 기능 검증이며 오류 감소율이나
속도/모델 품질로 표현하지 않는다. OpenSearch/S02–S05·태그 UI·개인화·전체 F02·성능/부하 인수는
후속이다. 최신 CI·완료 Codex·미해결 지적 없음·Squash/main CI와 브랜치 정리는 조정자 게이트다.
