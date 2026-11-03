# DB03 공개 기본 목록의 JPA 연결

기준 main `3b1fd62`, 작업 `codex/jpa-default-recommendations`다. 기존 비개인 B0의 공개
GET `/api/recommendations/default`를 opt-in JPA 제공자에 연결한다. 원래 Controller·서비스·
RecommendationConfig/BaselineHttpClient를 사용하며 새 추천 모델/기능으로 중복 집계하지 않는다.
기본 MySQL·공유 구현·root POM·V1/V2·Java17·OSIV off/validate는 유지한다.

## 구현 전 계약과 검증 경계

- 응답은 기존 Product7 items와 BASELINE/FALLBACK 두 필드다. 정수 size1–12/기본4·현재 첫1000
  ID순 후보·성공 선택의 현재 상품 배치 재조회·삭제 시 현재 DB의 ID순 fallback을 유지한다.
- 기존 JpaProductDao/JpaProductQueries가 이미 두 읽기를 실제 projection으로 제공한다. 새 DAO/
  스키마·표/sequence/runtime GRANT는 없다. 원격 대기를 DB 트랜잭션으로 감싸지 않는다.
- 원격에는 ID/available=true·빈 constraints·limit만 보낸다. 세션·사용자/취향/찜·상품명/가격은
  보내지 않는다. available은 후보 행의 존재이며 판매/재고/개인화를 뜻하지 않는다.
- 운영자 origin만 허용하고 연결200ms·전체 본문700ms·32KiB·정확한 B0 계약·추적/재시도 없음과
  취소 요청을 원래 gateway에서 유지한다. 빈 DB는 빈 fallback, DB 오류는500이다.

조정자가 frozen before 산출물과 폐기 PG18.6/TLS 정상/실패/browser DB를 준비한다. 원본과 이
세 DB의 복사 source 상품은 읽기 전용으로 보존한다. B0는 첫 ID만 선택하므로 별도 네 번째
scratch DB에도 같은 승인 snapshot345행/16표/7고수위를 검수한 뒤 조정자가 원래 관리자 DELETE
API로 그 복사본의 상품80개만 제거한다. 원래 삭제 producer/이력 처리를 사용하며 ID를 reset하지 않는다.
worker는 이 명시 authoring 범위의 새 자체 상품으로 실제 prefix/current/delete/빈 결과를 확인한다.
CI도 별도 세 번째 JPA scratch의 opt-in 준비 테스트로 같은 경계를 재현한다.
worker는 예약한 DB와 자체 ephemeral HTTP fixture만 사용하며 실제 B0/React 통합과 구분한다.
ID/epoch/source UUID·불변 queue는 초기화하지 않는다. scratch의 t_product SELECT만 임시 회수/
finally 복원해 DB 읽기 실패를 검증한다. 생산 SQL inspector나 서비스 대체는 사용하지 않는다.
실제 검증의 fixture 범위·경합 조건·미실행 경계와 JAR을 실행 후 기록한다. 단위/DB 없는 skip을
실제 PG 성공으로 세지 않는다. 시간/본문의 통제 실험을 응답 성능·모델 품질로 만들지 않는다.

## 결과와 남은 범위

Java17 모듈 단위/패키징 뒤 실제 PG18.6/TLS·별도 최소 runtime에서 전체67 통합(기존58·
정상5/scratch4)과 단위3이 통과했다. 이미 별도로 준비한 fixture/빈 카탈로그 준비2개는 skip이며
성공에 합산하지 않았다. 실제 JPA 후보1000 상한·정렬·현재 Product7·공개/size/ID만의 전송과
원격 호출 시 활성 DB TX가 없음을 확인했다. 잘못된 B0/503/redirect/비JSON과32,768B 수용/
32,769B 거절·미완료 본문의 기한도 원래 실제 HTTP client에서 확인했다.

처음 전체 실행은 cap fixture의 starts_with(name,prefix) 조회/정리가 비결정 ICU name collation에서
0A000으로 거절돼 정상 테스트 하나가 오류였다. 제품 쿼리는 바꾸지 않고 ASCII 자체 접두어의
테스트 조회/정리에만 COLLATE C를 명시했다. 생성한1003개 ID·속성/접두어를 확인해 그 행만
정리하고 전체를 다시 실행했다. 초기 로그/보고서와 정리 근거를 보존하며 제품 결함/성능으로 세지 않는다.

scratch의 새 상품을 고른 실제 HTTP 응답 대기 중 원래 관리자 API로 이름/가격을 변경하자
마지막 배치 재조회가 변경한 Product7을 반환했다. 선택 상품을 삭제한 경우에는 나머지 현재
상품의 ID순 FALLBACK이었다. 빈 카탈로그는 원격을 호출하지 않았다. scratch의 SELECT만 잠시
회수해 최초/현재/fallback 세 읽기 오류가500임을 확인하고 매번 finally 복원한 뒤 정상 응답을
재확인했다. 생산 SQL/서비스나 원본 상품을 변경하지 않았으며 제어한 순서를 일반 동시 부하로 확대하지 않는다.

검증 JAR SHA256은 `d30238bd106f7b9e6ada67ae48c884affb10b122dbcc489f4eae1a34899580b0`다.
독립적인 원래 MySQL JAR와 같은 새 JPA JAR를 실제 FastAPI B0/각각의 폐기 DB에 연결해
기본 size/1·4·12와 invalid0·13·x·1.1의8응답 상태·전체 JSON이 같았고 정규화는 없었다.
상품12개의 전체 Product7 참조도 같았다. 기존 JPA의 같은8경로404는 별도로 고정한 미제공
before이며 원래 MySQL에 없던 새 기능/과거 운영 장애로 포장하지 않는다. 해당 대조는 업무 쓰기 없이 수행했다.

Java17/Python3.12 전체 `./scripts/check.sh`가 통과했고 재빌드 JAR의 SHA도 같았다. 단위와 실제 DB
skip을 합산하지 않았다. 같은 JAR의 packaged smoke는 실제 OpenAPI33경로/40작업, 자체 소유
bound/non-listening upstream의 현재 DB FALLBACK/size·재시작과 기존 취향/찜/후기/계정/주문/
상품 흐름·잘못된 기동 거절을 확인했다. JVM6개와 해당 소켓을 모두 종료/닫았다.
정상/실패/scratch 감사에서 자체 행·실패 trigger/function 잔여는0, runtime 상품 SELECT는 모두
복원됐고 제품 수는80/80/0이었다. 불변 queue는 정리하지 않았다. CI scratch 준비 테스트는
이번 로컬 전체 실행에서는 사전 준비 상태로 skip했으며 필수 CI의 별도 명시 단계에서 실행한다.

Frontend의 추가 React/JPA 조합 인수는 별도로 관리하며 이 Backend 결과에 미실행 성공으로
넣지 않는다. 기본 실행 경로나
전체 DB03–06·Psycopg3 relay·검색 대체/cutover와 구분한다. B1/account-feedback과 R02–R05의
개인화 연결은 이번 범위가 아니다. 11월22일 DB/React·12월31일 전체20개 MVP·기존 PR 게이트를 유지한다.

README 네 사례와 기존 원격 수명/현재 DB 경계를 비교한다. 더 강한 새 독립 영향/원인 근거가
없으면 본문/순서를 보존하고 새 제공자 연결을 과거 장애 수정이나 실측 최적화로 바꾸지 않는다.
