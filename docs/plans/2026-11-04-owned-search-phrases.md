# R06 본인 취향 snapshot의 실제 문구 caller

기준 main `ac2b0cd`에는 공개 B0와 본인 취향 저장/조회가 있지만 문구 caller는 없다.
Recommendation의 명시 문구 제공자와 canonical 취향 서비스를 연결하는 한 목적 증분이다.
DB05 공개 검색은 페이지/count·네 정렬·stale/삭제 재조회·필수필터와 PG fallback의 계약을 함께
닫아야 한다. 이번에는 이미 제공자가 있는 필수 R06의 실제 Backend 연결을 먼저 완료하고
공개 엔진 검색을 선택 최적화나 완료 상태로 바꾸지 않는다.

## 구현 전 기준

- strict private POST 한 경로만 추가한다. 계정/취향/epoch/원격 주소를 입력으로 받지 않는다.
- 원래 canonical epoch-bound RR snapshot과 AccessGuard를 재사용하고 원격 대기는 TX 밖이다.
  원격 뒤 의미 변화409·수명401·DB 예외500·upstream UNAVAILABLE를 구분한다.
- soft 비승격·모든 hard·null/[]/0/scale·원자 태그·정확 분기와 query pairs를 consumer에서 검증한다.
  잘못된 wire나 장애를 로컬 문구/완화된 검색으로 대체하지 않는다. 최종 검색400도 보존한다.
- Java17·/api·ApiResponse·기존 MySQL/JPA·V1/V2·source UUID/불변 JSON/ACK는 보존한다.
  새 DAO/schema/GRANT·새 모델/순위/카탈로그·상품 검색 실행은 없다.
- 기존75 실PG/업무·실제 packaged MySQL 회귀를 유지한다. 새 private 소유·타입·기한/상한·
  provider 변조·원격 중 취향/epoch/삭제재가입/세션 교체를 검사하고 실제 제공자를 두 DB와 대조한다.

[공개 계약](../SEARCH-PHRASES.md)은 제공자의 typed wire를 참조한다. 게스트는 고정 상품 링크,
로그인 무취향은 원래 제공자의 기본 문구다. 부분 caller로 전체 R06/AI02·DB 전환을 완료하지 않는다.

## 검증 기록

초기 target10개 중 transport1개는 HTTP fixture handler의 DecimalNode/DoubleNode 기대 비교로
실패했다. 전송된 값은 수치상 같았으며 원문 JSON의13.70/0.00·계정 비노출을 직접 대조하도록
테스트 기대를 교정했다. 제품 transport/기한을 바꾸거나 오류를 skip하지 않았다.

초기64KiB candidate의 기본 검사와 실제 PG79통합+단위3은 통과했고 준비2 skip은 별도다.
지원 최대40 UTF-16 atom을 JSON escaping으로 표현하면 정상5문구가64KiB를 넘을 수 있어
128KiB로 보완했다. 초기 JAR·class 결속과 전체 XML/로그를 보존하고 최종 실행과 합산하지 않는다.
최종 exact-but-unusable Unicode 조건400과 tampered wire UNAVAILABLE도 추가한다.

최대 문자열 한 개의 JSON 표현은 최대242바이트(quote2+6×40), 조건 배열40개는9720바이트,
query pair40개는 각 최대306바이트로12240바이트다. 문구당 나머지 고정 필드/숫자/주종의 보수적
2048바이트와5문구·루트/진단4096바이트를 합쳐124136바이트로128KiB 안에 든다.
임의 whitespace padding까지 무한히 허용하는 계약은 아니며 제공자의 compact 정상 wire와
실제 UTF-8 body 경계를 검증한다. 이 계산/짧은 기한 실패 주입은 서비스 시간 성능이 아니다.

최종 기본 검사·실제 PG/두 packaged JAR·원래 Recommendation 제공자 대조는 소유한 폐기 DB/
동적 loopback에서 수행하고 관측한 최종 결과만 추가한다. 원본80/계정/불변 queue·수명은
보존하고 새 자체 계정/상품·실패 DDL/JVM/자식만 정리한다.

최종 소스의 전체 기본 검사는 Python 도구95·기본 API Java161·이관 단위18/JPA 단위3으로
통과했다. 기본 check의 실DB skip은 실제 통과와 별도다. 별도 PostgreSQL18.6/TLS·최소 runtime의
실제80통합+단위3이 통과했고 준비2 skip을 구분했다. 초기79와 최종80은 합산하지 않는다.
추가한 다섯 회귀는 원격 밖 TX·수명/취향 변화와 exact Unicode 조건400·변조UNAVAILABLE를
실제 소유 행/HTTP로 확인한다. 동일 최종 JPA JAR의 packaged 검사도36경로/43작업·재시작·
재로그인·기동 거절과 기존 업무/새 caller를 통과했다. 기존35/42에서 한 POST만 추가했다.

기본 MySQL packaged 전체12개 검증 묶음도 통과했다. 첫 실행은 새 body400을 정상으로 받은
뒤 ApiClient의 error_code 기본값을 성공 응답으로 간주하는 assertion에서 실패했다. 기존 helper
규칙대로 VALIDATION_FAILED/BAD_REQUEST 기대를 명시하고 같은 JAR 전체 회귀를 재실행했다.
첫 로그/보고/정리 근거를 별도 보존했고 제품 오류/자연 오류율로 세지 않았다.

동결 Recommendation main `17a99`의 실제 서버와 두 최종 JAR로11조건의 HTTP status·전체 JSON이
정규화/시간 제외 없이 같았다. 무취향/skip·선택 없는 soft 비승격·hard·원자 쉼표/개행 태그·주종
정확 분기/생략 수·목표 충돌·unknown/known empty·실제0 범위를 확인했다. 각 문구의 실제 공개
검색 ID/total은 현재 전체 Product7/특성에서 별도로 계산한 AND 조건과 같았다. 상품 수 null과
빈 결과를 보존하며 새 상품의 가격0을 제공한다고 주장하지 않는다(기존 상품 쓰기 최소1원 유지).
최대 유효 태그40개·5문구의 실제 제공자 body는 양쪽102525 UTF-8바이트였고128KiB 안에서 통과했다.

실제 하네스의 첫 상품은 기존 최소 가격에 어긋난0원으로 INSERT 전에400이었다. 자체 계정과
프로세스를 정리하고 fixture만1000원으로 수정했다. 다음 독립 제공자 비교는 GET JSON을 Python
float로 읽어 Decimal scale을 잃은 다른 입력을 보냈다. 같은 JAR/입력에서 scale을 잃은 반례를
별도 고정하고 원문 Decimal scale로 두 번째 요청을 직렬화해 최종11조건을 확인했다. 초기 로그/
실패 보고를 보존했고 이 두 하네스 교정에서 제품 소스/JAR는 바꾸지 않았다.
별도 sandbox Python3.9의 기존 HTTP bind 거절/정리 대기는 cwd/출력으로 소유를 확인한 PID만
종료했고, 같은 도구95개는 허용 loopback의 Python3.12에서 통과했다. 환경 실패와 제품 실패를 구분한다.

최종 정상/실패/scratch는 자체 추가 계정·상품·실패 trigger/function0, 상품80/80/0과 runtime
SELECT 복원·source UUID 불변을 확인했다. PG 원본80의 전체 상품 필드는 기존 MySQL 원값과
float32 binary까지 같았다. MySQL 전체 회귀의 새20계정/3주문·자체 상품과 별도 실제 R06의
자체 계정/두 상품은 정리했고 기존 MySQL 사용자·event/delivery/target은 exact였다.
소유 JPA/MySQL/Recommendation 자식과 API listener는 종료했다. 불변 version/event/delivery를
삭제하거나 source/ACK/sequence를 reset하지 않았으며 컨테이너 최종 정리는 조정자 소유다.

최신 head 필수 CI·완료 Codex·미해결0/main 병합은 후속 게이트다. 실제 React R06 소비·문구 선택→
검색/빈 결과 인수와 전체 R06/AI02·공개 엔진 검색/PG fallback·DB05–06/cutover는 미완료다.

README 네 사례를 비교했다. 이번 새 소비자 연결은 과거 운영 실패나 측정 성능을 증명하지 않으므로
독립적으로 더 강한 원인·영향 근거가 있는 기존 네 본문과 순서를 보존한다.
전체20개 MVP12월31일·DB/React11월22일, 추가 개선은 MVP 뒤1월1일부터라는 기준을 유지한다.
