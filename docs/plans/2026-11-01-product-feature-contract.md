# C03/BE04/F03 상품 특성 저장·조회 증가분

기준은 main `1846b59c3e9df0b004ac8eb821d7eb27c7aeaab6`다. BE03 이후 현재 계정·권한 검사를
그대로 사용해 맛/향·용량·설명·출처를 저장하는 별도 업무 API를 추가한다. 기존 8개 주종과 상품의
7필드 응답·페이지/정렬·주문 스냅샷·추천 서버 경계는 유지한다. 전체 F03/BE04/C03 완료가 아니다.

## 범위·계약 결정

- 공개 GET `/api/products/{id}/features`, 현재 관리자 PUT `/api/admin/products/{id}/features`.
  기존 상품 API 응답에 미확인 필드를 섞지 않는다. [계약](../PRODUCT-FEATURES.md)·[OpenAPI](../openapi/product-features.json).
- null은 unknown, []는 검수했으나 선언할 태그 없음. []도 검수 선언이라 sourceName을 요구한다.
  용량은 정수/null, 설명/출처 공백은 null이며 맛/향을 가격·주종·이름에서 추정하지 않는다.
- 전체 PUT에서 누락 필드는 unknown으로 교체하고 {}는 특성과 출처를 지운다. 정규화 중복과
  유효하지 않은 요청을 쓰기 전에 거부한다. 태그는 trim·Locale.ROOT 소문자·정렬된 집합이다.
- sourceName/URL/note는 공개 provenance 기록이지 권리 승인/원문 감사/학습 허가가 아니다.
  URL은 userinfo 없는 HTTP(S)만 저장하며 서버 fetch는 없다. 데이터/학습의 상세 정본은 Recommendation 소유다.
- known 플래그가 있는 헤더와 태그 행을 분리한다. JSON 컬럼은 이 증가분에서는 피하고 향후
  태그 조회/필터의 관계를 드러내는 별도 테이블을 선택했다. product lock 뒤 전체 교체해 삭제/
  동시 교체와 직렬화하고, 별도 revision 충돌 검사는 후속이다.
- 상품 삭제는 활성 특성/태그만 CASCADE 삭제하고 이미 만든 주문의 스냅샷·금액은 보존한다.
  상품 특성을 과거 주문에 소급 복제하지 않는다.
- 수동 004는 001→002→003 위에 두 빈 테이블만 추가한다. 기존 데이터 초기화/속성 채움·
  자동 마이그레이션·전체 DDL rollback/재실행 멱등성을 주장하지 않는다.

## 현재 검증

Java 17 단위/MockMvc·전체 MVC 문서 55개와 Maven verify/package를 통과했다. 공개 unknown 응답의 명시 null,
401/403·미존재404, 필드·출처·URL·정수 타입 검증, 정규화 중복, 전체 clear와 태그 쓰기 실패의
예외 경계를 포함한다. 가짜 DAO 실패를 실제 DB rollback 성과로 표현하지 않는다.

Python 도구·안전 게이트 22개, 구문·repository·diff 검사와 새 시드/004의 테이블 정의 일치를 확인했다.
표준 변이 smoke에 product_feature_contract를 추가했다. generated CI 계정·상품에서 unknown/
known []·반복 PUT·입력 실패 뒤 기존값·{}clear·삭제 후 두 특성 테이블 0행을 확인하도록 했다.
실행 전에 같은 폐기 DB·포트·컨테이너·marker를 확인하며 원본/사용자 DB를 사용하지 않는다.

최종 불변 jar SHA-256: `3cfebed46b980090063e7f648a880dccf0f22c8952cf0cca27015a853938ea83`.
004 SQL SHA-256: `42f27fb0d3b748131fcf17282145c4103c37cb197fb0cb971a918676a5a8b3cf`.
조정자가 별도 불변 jar·새/전환 폐기 DB로 실제 HTTP/SQL·rollback·삭제 이력을 검증했다.
최종 표준 smoke도 통과했다. PR/head 리뷰·CI·병합·main CI는 별도 게이트이며 완료로 추정하지 않는다.

첫 artifact `1414f9df2bf3e1cdef5d38fde663f81ac7b3e7586db85d07654cce1176bd6fb1`는 실제 기능
검증을 통과했지만 `/v3/api-docs`가 ControllerAdviceBean 생성자 NoSuchMethodError로 500이었다.
Boot 3.5.0과 기존 springdoc 2.2.0 조합의 불일치를 공식 호환 표로 확인했다. 기존 API/Boot를
유지하고 springdoc의 호환 패치를 검증했다. nullable Schema 주석과 실제 문서/Swagger의 readonly
HTTP 검사를 필수 smoke에 추가했으며 새 jar로 다시 검증했다. 이 첫 artifact를 최종 성공으로
사용하지 않는다. 의존성 다운로드 첫 시도의 로컬 캐시 쓰기 sandbox 제한도 제품 실패로 세지 않는다.

두 번째 2.10.0 artifact `371f7be423e4cb20d097dda62f26aefdbd8a6e4895c2da990a124555d166082a`는
실제 기동에서 Swagger 리소스 패턴이 PathPatternParser의 `**` 뒤 패턴 금지와 충돌했다. 2.8.17도
DB 없는 full MVC context에서 같은 AbstractSwaggerConfigurer.combinePatterns 실패를 보였다.
전역 matcher를 우회하지 않고 [Boot 3.5.0 지원을 명시한 2.8.9](https://github.com/springdoc/springdoc-openapi/releases/tag/v2.8.9)로
고정했다. 테스트 DataSource는 연결 시 즉시 실패하도록 구성하고 TCP/DB 없이 실제 앱 MVC 매핑·
OpenAPI 생성·Swagger HTML을 검증했다. 이 회귀로 단순 단위/패키징이 놓친 기동 실패도 Maven에서 잡는다.

독립 검토의 태그 coercion 후보는 수정 전 MockMvc에서 `[1]/[true]/[null]/[{}]/[["nested"]]`에
각각 200/200/400/400/400으로 재현했다. 숫자/boolean을 String으로 바꿔 선언 태그로 통과시키는
경계였다. contentUsing/using 엄격 문자열 역직렬화로 수정한 뒤 같은 다섯 입력은 모두 400이며
서비스 호출은 0이다. 이것은 Servlet/JSON 단위 경계로, 실제 네트워크 HTTP 전후 실측이나 사람의
취향 오류율이 아니다. 설명/출처 스칼라 입력도 문자열만 허용하며 최종 실제 HTTP에서도 확인했다.

수동 004의 실제 기존 스키마 전환은 회원 6·상품 80·주문 3·상세 4의 UTF-8 바이트 동일과 새 두
테이블 0행을 보존했다. 초기 실제 HTTP에서는 null/[]·권한·반복 PUT·9개 invalid 입력의 400과
저장 상태 불변, 헤더 갱신/태그 삭제 뒤 DB trigger 실패 500의 원상복귀, {}clear, 상품 삭제 후
feature/tag 0행과 기존 주문 9,600원 보존을 확인했다. 이를 최종 결과와 구분했다.

중간 f919… 불변 jar의 JDK 17·MySQL 8.4 실제 HTTP에서 위 기능을 모두 다시 확인했다.
숫자/boolean 태그와 sourceName/description/sourceNote 타입을 포함한 17개 invalid 요청이 400이고
저장 값은 그대로였다. 의도한 DB trigger 실패는 헤더 쓰기·태그 삭제 이후 INSERT에서 발생했고
HTTP 500 뒤 기존 헤더와 태그 2행이 보존됐다. {}clear·상품 삭제 후 특성/태그 각 0행과 과거
주문 9,600원/상품 스냅샷도 유지했다. `/v3/api-docs`는 200이며 두 새 경로가 있다. 이는 신규
API의 고정 기능/실패 조건 검증이고 성능 개선·원천 권리 승인·모델 데이터 확보 성과가 아니다.

다만 f919…의 표준 smoke는 OpenAPI가 응답 media를 `*/*`로 선언해 JSON 계약 검사에서 실패했다.
새 컨트롤러 범위의 JSON produces/PUT consumes를 명시했다. 강화한 full MVC 회귀는 실제 생성
문서의 media·응답 8/요청 7필드·nullable 값을 검사하며, OpenAPI 3.1에서 legacy nullable 표기만으로
누락되던 null union도 Schema.types로 명시했다. checker를 완화하지 않고 새 3cf… jar로 다시 검증했다.
Mockito mock이 없는 이 테스트는 Servlet/DI listener와 연결을 거부하는 DataSource를 사용해
불필요한 ByteBuddy attach 없이 기본 환경에서 실행한다. 초기 기본 sandbox의 attach 실패는
제품 오류와 구분하고 full MVC/문서 검사를 생략하지 않았다.

기준 main1846b59/불변 b4c035… jar에서도 실제 `/v3/api-docs` 500을 별도 확인했다. 기존 호환성
문제였으며 신규 상품 API 추가가 이 500을 만들었다고 기록하지 않는다. 고정 문서 요청 1회 관측은
운영 장애율이나 성능 수치가 아니다.

### 최종 결과

3cf… 불변 jar의 실제 네트워크 HTTP/DB 재검증에서 17개 invalid 요청 400과 상태 보존,
의도한 DB 오류의 헤더/태그 rollback, 전체 clear, 삭제 후 특성/태그 0행·과거 주문 9,600원과
스냅샷 보존을 모두 다시 확인했다. 실제 OpenAPI HTTP 200과 JSON media·응답 8/요청 7필드·
일곱 nullable·Swagger UI를 포함한 표준 scripts/smoke.sh가 통과했다. 기존 세션/권한·가격·
롤백·익명 이력 회귀도 같은 실행에서 유지됐다. 004의 기존 6/80/3/4 행과 UTF-8 바이트 보존
결과는 동일 SQL로 유효하다. 실제 기능/실패 조건의 검증이며 지연·처리량 개선율은 산출하지 않는다.

로컬 재현은 Backend 루트의 `./scripts/check.sh`와 [TESTING](../TESTING.md)의 전용 폐기 DB/컨테이너/
marker·빈 포트 설정 뒤 `PUBBURI_SMOKE_MUTATING=1 ./scripts/smoke.sh`로 수행한다. 새/기존 스키마의
구분과 수동 004 절차는 [상품 특성 계약](../PRODUCT-FEATURES.md)을 따른다. 기존 DB를 테스트 대상으로
넣거나 data.sql로 초기화하지 않는다. PR 최신 head CI·완료된 리뷰·병합/main CI는 조정자 게이트로 남긴다.

그 뒤 추가한 text/plain Content-Type/Accept 검증에서 발견한 오류 분류/envelope 결함은
[별도 미디어 fix 기록](2026-11-01-product-feature-media.md)을 따른다. 최초 기능·문서 smoke 통과를
지원하지 않는 모든 프로토콜 입력까지 확인한 성과로 확대하지 않는다.

## 사례 비교·후속

이것은 없던 상품 특성 API를 추가한 기능이다. 기존 404→200을 결함 감소/성능 개선으로 세지 않는다.
신규 관계·unknown/검수·출처·권한·교체 원자성을 검증하지만 현재 README의 실제 계정 수명/주문
이력 보존 사례보다 강한 관측 문제 해결이 확보된 것은 아니다. 현재 네 사례와 순서를 유지한다.
후속 실제 rollback/동시 교체·삭제 경계에서 더 강한 근거를 얻으면 다시 비교한다.

관리자 편집 UI, 개별 필드의 원천 감사/검수 카탈로그 반영, 취향/찜/이벤트 저장·검색 색인·추천
호출은 각 담당의 후속이다. 레포마다 소유 코드·테스트·상세 문서를 유지하고 이 증가분을 전체
F03/BE04나 AI 모델 완료로 표시하지 않는다. 지연/처리량/추천 품질은 미측정이며 개선을 주장하지 않는다.
