# F01 상품 상세용 본인 찜 상태 GET

기준 main `e1693a7`의 보안 패치/기존 찜 계약 위에 별도 단건 조회를 추가한다. 작업 브랜치는
codex/account-favorite-state다. 화면 상세의 초기 상태를 위해 전체 찜 목록을 받아 검색하거나
상품마다 목록/count를 반복 조회할 필요가 없도록 제공자 계약을 명확히 한다. 성능 실측 결과는 아니다.

## 계약·구현

- GET `/api/users/me/favorites/{productId}`는 기존 FavoriteMembershipResponse의 productId/favorited
  두 필드와 ApiResponse를 유지한다. 본문·타인 소유자 선택자는 없으며 관리자도 본인만 읽는다.
- active 계정의 관계 없음·없는 상품·삭제 상품은 200 false다. 상품 존재 확인 API로 사용하지 않는다.
  미로그인/계정 삭제/같은 ID 새 epoch/자격 회수는 401이며 false로 위장하지 않는다. 잘못된 ID는
  기존 positive int 계약의 400이다. DB 예외를 false로 숨기지 않는다.
- SQL 하나에서 현재 id+epoch의 사용자 행을 찾고 EXISTS로 관계를 조회한다. 사용자가 없으면
  조회 결과 null, 있으면 true/false라 같은 읽기 snapshot 안에서 수명과 상태를 구분한다.
  기존 AccessGuard의 현재 계정/세션 확인은 별도 조회이며 HTTP 전체 SQL 수를 한 번이라고 표시하지 않는다.
- t_user_favorite 기본키(user_id,product_id)를 두 값으로 조회한다. 스키마·전체 컬렉션·count·
  별도 상품 조회와 쓰기는 없다. GET은 created_at/정렬을 바꾸지 않는다. 화면 목록의 각 상품에
  단건 GET을 추가하는 N+1 설계를 도입한 것은 아니다.
- provider [정본](../ACCOUNT-FAVORITES.md)·[부분 OpenAPI](../openapi/account-favorites.json)와
  실행 `/v3/api-docs`에 단건 GET/무본문/두 필드를 반영한다. Allow GET/PUT/DELETE를 유지한다.

## 검증·인계

Java 17 Maven verify의 94개 단위/MockMvc·실제 MVC 문서 검사와 패키징, Python 도구/안전 게이트
27개·저장소/문법/diff 검사를 통과했다. 가짜 DAO는 true/false/null·전달된 id+epoch·쓰기/컬렉션 호출
없음·예외 전파를 확인하며 실제 DB의 index/동시성 근거로 쓰지 않는다. 컨트롤러는 관리자 본인·
비인증/만료·잘못된 ID·두 필드·누락 상품 false, 실행 명세는 GET 추가/본문 없음을 검증한다.

최종 불변 jar SHA-256: `0e1933a2daef4d0aa5549d27114e6ec5419bc958cf898f761c83a665ab76cd9f`.
조정자가 같은 불변 jar와 전용 MySQL 8.4의 실제 HTTP smoke 아홉 범주를 통과했다. 기존 주문·
권한·삭제 이력·계정 수명·특성·취향·찜과 새 상태 전환/만료·문서 계약을 유지했다. 실제 React
production build와 같은 jar/DB의 F01 연동도 API mock·외부 서비스 없이 통과했다. UI의 상세
검증은 [Frontend #15](https://github.com/Pubburi/Frontend/pull/15)의 소유 기록을 따른다. 이 결과는
FE03/C04 전체·추천 품질 완료가 아니다. 기존/공유 DB와 포트는 작업자가 사용하지 않았으며
SQL schema/seed/마이그레이션 변경은 없다.

같은 SELECT의 실제 MySQL EXPLAIN도 확인했다.

| 대상 | access_type | key | used_key_parts | rows 추정 |
|---|---|---|---|---|
| 사용자 u | const | PRIMARY | id | 1 |
| 관계 f | const | PRIMARY | user_id, product_id | 1 |

전용 시연 계정의 관계 한 개를 준비해 조회한 뒤 finally에서 삭제·원상 보존했다. 이것은 해당
입력의 옵티마이저 실행 계획/예상 행 수다. 실제 검사 행 수나 지연·속도 개선·처리량으로 환산하지
않는다. 대용량 카탈로그/다수 사용자 부하·모든 진행 요청 취소를 완료했다는 의미도 아니다.

CI 폐기 환경의 찜 흐름에 저장 전 false·PUT 뒤 true·DELETE 뒤 false·타회원/관리자 본인·
없는/삭제 상품 false·구쿠키/재가입 401·페이지 유지와 Allow GET을 추가했다. 실행은
[TESTING](../TESTING.md)의 폐기 컨테이너/marker와 `PUBBURI_SMOKE_MUTATING=1 ./scripts/smoke.sh`를 따른다.

README의 현재 기능에 단건 상태를 명시하고 기존 네 문제 해결 사례·순서는 보존한다. 새 조회
설계나 호출 수 설명을 실측 지연·처리량 개선으로 바꾸지 않는다. F01 전체 화면 연결·완료 여부는
조정자의 공통 기획/Frontend 검증을 따르며 Backend API 증가분만으로 전체 완료하지 않는다.
