# Backend 테스트와 CI

`./scripts/check.sh`는 저장소 비밀정보/생성물 검사, 로컬 도구/안전 게이트 테스트와 Java 17
Maven 단위 테스트·패키징을 실행한다. DB가 없는 개발 환경에서도 이 경로를 사용할 수 있다.

PR·main push·수동 실행의 `backend-ci`는 별도 MySQL 8.4 서비스에 `data.sql`을 초기화하고
패키징한 실제 jar를 기동한다. 포트가 이미 사용 중이면 다른 서버를 테스트하지 않고 실패한다.
새 프로세스의 시작 로그·PID와 HTTP 응답을 확인한 뒤 API 검증을 수행한다.
실행 jar가 없거나 여러 버전이면 실패하므로 오래된 산출물을 정리하고 다시 패키징한다.

## HTTP 계약과 DB 검증

상품 검색 outbox는 [생산 계약](SEARCH-OUTBOX.md)의 업무 TX와 카운터/불변 payload를 검증한다.
단위는 현재 투영의 승인 필드·null/[]/태그 정렬·삭제/상한/저장 실패를 검사하고, 폐기 HTTP는 실제
생성·수정·특성·주문수·삭제 버전과 DB trigger 실패의 상품/특성/주문 rollback을 확인한다.
관리 CLI의 backend_product_events 입력을 소비할 수 있는지도 검사하며 자동 전송·검색 품질로
확대하지 않는다. 수동007의 기존 전체 데이터 보존·현재상태 backfill은 별도 전용 DB에서 확인한다.
CI의 다음 엔진 단계는 `PUBBURI_SEARCH_OUTBOX_INPUT`으로 이 HTTP가 만든 private 가상 batch를
받는다. 실제 CLI 재생·삭제 뒤 old version 차단·재시작 뒤 유지가 필수 성공 조건이다. 원문 입력은
artifact에 넣지 않으며 입력은 검증용 private 복사본으로 고정한다.

로컬 색인은 `python3 scripts/smoke_search.py`로 고유 label/새 volume·동적 loopback 포트의
전용 엔진을 만들고 검증/재시작/정지·정리한다. 먼저 `docker build -t pubburi-search:3.9.0-nori-v1 search`로
pinned base/nori를 준비한다. 기존 서버·DB/볼륨을 채택하지 않으며 결과는 target/search-smoke-report.json,
원문 engine 로그/입력·CID/port/PID는 ignored에만 남긴다. 필수 backend-ci에도 같은 실행을 포함한다.
가상 ID 이력의 중복/역전·동일 버전/동시 충돌·strict schema·tombstone/GET 차이·volume 재시작·
부분 실패 receipt를 확인하며 outbox·실제 업무 동기화·검색 품질/시간 성능으로 확대하지 않는다.
[실제 조건과 초기 실패](plans/2026-11-01-local-search-replay.md)를 따른다.
실제 HTTPResponse fixture는 Content-Length보다 짧은 완결 JSON EOF를 거절하고 chunked/본문
상한·전체 deadline을 유지한다. 검증 본문 성공 뒤의 로그 IO 실패도 소유 자원 정리를 막지 않으며
비소유/삭제 실패 보호·실패 상태와 partial receipt를 별도 단위 경계로 확인한다.
추가 실제 header trickle fixture는 idle timeout보다 짧은0.08초 간격의 헤더도 절대 기한0.15초에
중단하는지 확인한다. nonblocking connect 대기·읽지 않는 peer에 막힌 요청 body·Connection: close
본문과 기한이 다른 두 동시 요청도 검사한다. 반환 시 요청별 watchdog 종료·소유 socket/취소용
descriptor 해제를 확인하며 기한 실패는 partial receipt를 complete로 바꾸지 않는다. 짧은 제어
실험의 경과값을 실제 서비스 응답 속도/운영 오류율로 표현하지 않는다.

상품 page offset은 음수/0/양수 래핑과 첫 지원 범위 초과를 SQL 전에400으로 거절한다.
MockMvc와 실제 상품 서비스/fake DAO는 미지원 조건의 DAO 호출0·정상/최대 근처·0/음수/
size clamp·필터/정렬·메타를 확인한다. 필수 HTTP도 네 미지원 조건과 정상화 동등성·런타임 문서의
지원 범위를 검사한다. 실제 MySQL의 여덟 전후 조건과 서버500/잘못된200은
[정확성 원값](measurements/2026-11-01-product-page-offset.md)에 구분한다. 클라이언트400을 전체
오류율/응답 속도 개선으로 바꾸지 않으며 다른 API/공통 페이지 정책은 변경하지 않는다.

상품 목록의 필수 필터는 잘못된 가격/도수 범위·숫자 표기·태그 중복/빈/개수를 SQL 전에 거절한다.
MVC는 literal comma와 단위형 파라미터·기존 페이지/응답을, MyBatis BoundSql은 목록/COUNT의
동일 AND 조건·값 바인딩을 확인한다. 표준 폐기 HTTP는 합성 상품5개에서 도수5.1/5.55/5.555·0,
가격0경계·unknown/검수[]·태그all-match·정렬/페이지/total·잘못된 입력과 삭제를 검사한다.
실행 OpenAPI의 숫자 범위·반복 태그 form/explode·JSON 응답도 필수다. 실제 DB 결과·제한은
[필터 작업 계획](plans/2026-11-01-product-search-filters.md)에 구분하며 OpenSearch/성능 인수로 확대하지 않는다.

기본 추천 연결은 private loopback ephemeral upstream에서 정상 B0 계약·비정상/중복/추가 JSON·
잘못된 ID/algorithm·비JSON/503/redirect·연결 거부·본문32KiB 초과를 검사한다. 헤더 뒤 끝나지 않는
chunked body도 전체 완료 deadline으로 종료하고 소유 subscription 취소를 확인한다. 이 소량
실패 주입은 서비스 지연/처리량 측정이나 전체 부하 검증이 아니다. DAO 경계는 최신 가격 배치
재조회·삭제 후 current fallback·DB 오류/빈 결과 구분을 확인한다.

실행 OpenAPI는 기본 목록의 items/source 두 필드와 기존 Product7을 확인한다. 필수 HTTP는
size 기본4/1/12·정수/범위400·현재 상품 필드·ID순을 확인한다. CI는 추천 서버를 기동하지 않고
FALLBACK도 정상 목록임을 검사한다. 실제 3서버 검증은 [연결 계획](plans/2026-11-01-default-recommendation-integration.md)에 구분한다.

Jackson의 주 회귀는 새 스레드의 실제 default shared pool에 60,000자 문자열을 먼저 읽은 뒤
기본 name 상한 50,000자·키 140,000자·128자 chunk에서 추가 누적 성장을 거부하는지 확인한다.
보조 회귀의 상한64/키2,048은 fresh non-recycling 조건을 명시한다. primed 큰 버퍼에서는 같은
작은 키를 전부 소비할 수 있으므로 모든 경로의 즉시 중단으로 해석하지 않는다.
소비한 문자 수는 고정 파서 경계이며 전체 요청 메모리·DoS/지연 결과가 아니다. 별도 10B/13B
문서 길이 대조는 이전에도 통과한 경계다. MockMvc/필수 폐기 HTTP는 UTF-8·ISO-8859-1·UTF-16의
정상 DTO/로그인, 60,000자 필드명 오류의 고정 400·원문 비노출·기존 세션 불변을 확인한다.

Maven의 MultipartParserSecurityTests는 실제 embedded Tomcat 라이브러리로 정상 part header와
2,111B header의 512B 기본 상한 거부를 검사한다. 포트·DB·대량 메모리 부하 없이 파싱 경계만
검증하며 실제 HTTP의 전후 오류율이나 전체 DoS 방어 완료로 표현하지 않는다.
[의존성 점검 범위](DEPENDENCY-SECURITY.md)의 jar/실행 smoke 호환성과 함께 확인한다.
추가 MockMvc는 일반 MultipartException의 고정 JSON 400·크기 초과 하위 타입의 413·원인 상세
비노출·기존 미지원 Content-Type 415를 확인한다. 폐기 환경의 필수 HTTP smoke에도 2KB 수준의
소량 multipart header 초과의 실제 크기 예외 413과 정상 형태의 미지원 미디어 415를 포함한다.

기본 `./scripts/smoke.sh`는 카탈로그 크기와 무관한 페이지 불변식·응답 필드, 없는 상품 404, 비인증 401,
입력 검증 400과 ApiResponse/오류 필드를 확인한다. 계정·주문을 생성하지 않는다.

실제 `/v3/api-docs`의 새 상품 특성 GET/PUT·요청/응답 필드·nullable 의미와 `/docs` Swagger UI
HTML도 확인한다. 단위/정적 명세가 통과해도 런타임 문서 생성이 500이면 필수 job은 실패한다.

CI의 변이 모드는 다음 조건을 모두 확인한 뒤에만 고유 테스트 계정·주문을 만든다.

- `PUBBURI_SMOKE_MUTATING=1`을 명시한다.
- JDBC URL은 loopback MySQL이고 `PUBBURI_DB_NAME`과 같은 `*_ci` 스키마를 가리킨다.
- `PUBBURI_SMOKE_CONTAINER_ID`의 공개 MySQL 포트가 JDBC URL의 포트와 일치한다.
- 같은 폐기 DB의 `pubburi_ci_smoke_guard(token)`에 이번 실행의 `PUBBURI_SMOKE_RUN_TOKEN`이 존재한다.

CI marker는 GitHub run ID/attempt로 실행마다 구분한다. 변이 경로는 초기화한 `data.sql`의
상품이 존재하고 테스트용 missing ID가 없음을 별도로 확인한다. 기본 읽기 전용 경로는 빈 카탈로그도 허용한다.

그 뒤 실제 HTTP 세션 유지/로그아웃, 일반 회원 관리자 접근 403·타인 접근 차단,
주문 입력 검증·중복 상품 수량 정규화·성공 주문의 상세/상품 주문수/회원 스탬프 반영을 확인한다.
유효 상품 다음에 없는 상품을 넣는 실패 주문은 현재 서비스의 주문 헤더 INSERT와 첫 상품
UPDATE 뒤에 예외를 일으킨다. 실패 전후 주문 목록·상품 주문수·프로필/스탬프가 동일한지
비교해 그 경로의 실제 DB rollback을 검증한다.

신규 주문의 `totalPrice`·`priceBasis`와 상세 단가/합계를 확인하고, 같은 폐기 DB의 상품
가격·이름을 변경한 뒤 단건/목록 주문 응답이 유지되는지 검증한다. 변경한 상품은 finally에서
복원하며 SQL 변경 전에도 동일 컨테이너·DB·run marker 안전 조건을 다시 확인한다.

삭제 이력 경로는 새 관리자 fixture·상품·구매자를 만든다. 관리자 fixture 승격은 같은 폐기 DB
안전 게이트와 `ci_admin_`+UUID 이름 제한을 확인하고 새 로그인으로 역할을 읽는다. 기존 관리자
계정은 수정하지 않는다. 실제 관리자 HTTP로 상품·회원 삭제를 실행해 상세/금액 보존과 참조 null,
회원 자유 입력 메모 제거, 인증된 타인 접근 403·삭제 세션 401, 관리자 단건/페이지 보존을 확인한다.
같은 ID로 새 계정을 만든 뒤에도 과거 주문 목록은 비어 있고 이전 주문 접근은 거부돼야 한다.

세션 경로는 독립 cookie jar로 권한 강등의 기존 관리자 세션 403, 삭제 구세션 401,
삭제 때 조회하지 않은 세션의 동일 ID 재가입 뒤 401/새 로그인 200, 비밀번호 변경 뒤 구세션
401와 구/새 비밀번호 로그인, 최신 이름/스탬프 응답을 확인한다. 관리자 본인 비밀번호 수정은
현재 세션을 유지하고 다른 기존 세션은 거부하는 경계도 확인한다. epoch/hash를 UserResponse에 노출하지 않는다.

상품 특성은 기존 상품 응답을 유지하면서 공개 조회/현재 관리자 교체를 확인한다. null unknown과
[] 검수 상태, 출처 필수·URL/길이/정수/중복 검증, 정규화·반복 PUT·{} 전체 초기화, 잘못된 입력 뒤
기존 값 불변을 검증한다. 직접 생성한 상품을 삭제한 뒤 특성 GET/PUT 404와 두 특성 테이블의 잔존
행 0을 같은 폐기 DB에서 확인한다. 합성 fixture 특성을 실제 상품 사실/수집·모델 성과로 사용하지 않는다.
정상 관리자 쿠키의 text/plain PUT은 415, 지원하지 않는 Accept의 GET은 406 JSON 오류 envelope를
반환하고 저장 특성은 그대로인지 확인한다. 미디어 오류를 내부 오류 500으로 숨기지 않는다.

명시취향은 두 일반 계정과 관리자의 본인 격리, body/query 소유자 미신뢰·비공개 응답, 상태와
null/[]/0·soft 목표/hard 범위 분리, 엄격 타입·역전/중복/주종 검증 뒤 저장 불변을 확인한다.
삭제 뒤 두 취향 테이블 0행·같은 ID 재가입 NOT_STARTED·조회하지 않고 보관한 구쿠키 401도 검증한다.
실행 OpenAPI의 본인 경로·상태 enum·세 루트/그룹별 네 필드·nullable을 필수 문서 경로에서 확인한다.

계정 찜은 본인/타계정/관리자 격리·다른 세션의 같은 목록, 반복 PUT/DELETE와 최초 추가 순서,
페이지·현재 상품 일곱 필드·상품/회원 삭제 cascade·같은 ID 재가입 fresh/구쿠키 401을 확인한다.
찜으로 상품 주문수/가격을 바꾸지 않으며 잘못된 ID/페이지·없는 상품 뒤 기존 저장이 유지돼야 한다.
지원하지 않는 POST는 405·Allow(PUT/DELETE), 지원하지 않는 Accept는 406 JSON이며 상태는 그대로다.
실행 문서는 본인 GET·PUT/DELETE·페이지/상품/관계 스키마·본문 없음·타인 경로 없음을 검사한다.
단건 상태 GET도 비인증/만료 401과 active false를 구분하며 저장 전false→PUT true→DELETE false,
없는/삭제 상품 false·타계정/관리자 본인 격리·Allow GET·기존 페이지/저장 불변을 검증한다.

이것은 모든 단계의 장애·동시성·멱등성과 이미 인증된 요청의 취소까지 완료했다는 뜻이 아니다.
특히 스탬프 저장 이후 장애와 동시/중복 요청은 BE06/C04의 추가 검증으로 남는다.

## 로컬 검증

변이 검증은 전용 폐기 MySQL 컨테이너와 초기 시드가 있는 `*_ci` DB에서만 실행한다.
기존 개발·사용자 DB를 초기화하거나 테스트 대상에 넣지 않는다. 실행자는 고유 토큰으로
marker 테이블을 준비하고 DB/포트/컨테이너 환경을 설정한 뒤 `./scripts/smoke.sh`를 실행한다.
API 테스트는 새 계정/주문을 남기므로 검증 후 폐기 컨테이너를 종료·제거한다. 기존 볼륨은 사용하지 않는다.

CI는 Maven `target/surefire-reports/*.xml`과 자격정보/요청 원문이 없는
`target/api-smoke-report.json`을 성공/실패와 관계없이 아티팩트로 7일 보존한다.
개인 설정·DB 원본·서버 원문 로그는 업로드하지 않는다. 테스트 개수나 CI 시간은 앱 성능 지표가 아니다.
