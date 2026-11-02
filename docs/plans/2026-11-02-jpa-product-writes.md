# DB03·DB05 PostgreSQL 상품 쓰기와 원자 생산

기준 main `afc572a`, 작업 `codex/jpa-product-writes`다. 별도 JPA 제공자의 기존 상품/특성 읽기에
상품 생성·수정·삭제와 특성 전체 교체를 연결하고, 같은 트랜잭션의 version/outbox/delivery 생산을
함께 제공한다. 기본 MySQL API·루트 Java/POM·Flyway V1/V2·원본/기존 UUID·epoch·ACK는 보존한다.
relay/재색인·주문/개인 API·기본 실행 cutover와 전체 DB03–06 완료는 범위가 아니다.

## 구현 전 계약과 대안

- 원래 ProductRestController/ProductFeatureRestController·요청/응답 DTO·ProductServiceImpl·
  ProductFeatureServiceImpl·ProductSearchOutbox를 재사용한다. 이전 얇은 읽기 Controller는 제거한다.
  DAO를 성공 stub로 채우거나 상품 쓰기만 먼저 열어 변경 기록을 누락하지 않는다.
- 실제 JPA Repository로 상품/특성 CRUD와 태그 일괄 persist를 수행한다. 원래 Criteria DTO 읽기와
  AND 필터/count·정렬/페이지·REAL/unknown을 유지하고 특성 읽기의 RR override로 두 SELECT의
  snapshot을 보존한다. shared MySQL 서비스나 schema를 수정하지 않는다.
- 상품 전체 FOR UPDATE, version의 conflict-safe 생성/원자 증가·상한, 잠금 조회와 JSON 원문 INSERT·
  delivery 예약은 명시 native SQL로 의미를 제어한다. version·tombstone은 상품 FK 수명과 분리하고
  outbox JSON을 다시 만들거나 source UUID/target binding을 새로 생성하지 않는다.
- 일반 API 쓰기의 트랜잭션에서 상품→특성/태그→version→불변 event→PENDING 예약이 모두 commit/
  rollback돼야 한다. remote engine IO는 없다. event ID는 commit 순서/완료 watermark가 아니다.
- 최소 runtime은 상품/특성 DML, 태그 SELECT/INSERT/DELETE와 잠금에 필요한 UPDATE(tag), version SELECT/INSERT/UPDATE,
  outbox/delivery SELECT/INSERT와 상품/outbox identity USAGE만 추가한다. 이벤트 UPDATE/DELETE·
  태그 전체 UPDATE·product_id/kind UPDATE·version DELETE·delivery ACK/UPDATE, DDL/ownership/TRUNCATE·sequence reset은 허용하지 않는다.

## 실제 검증의 사전 경계

별도 소유 PG 정상/실패 DB만 사용하고 browser DB는 조정자가 담당한다. 기존 승인 typed snapshot을
복사한 원본 행은 수정하지 않는다. API로 만든 `ci_jpa_` 상품·허구 user/order/detail만 검증하며
이벤트/version/delivery는 테스트 정리를 위해 지우거나 바꾸지 않고 마지막 tmpfs 폐기로 정리한다.

확인할 동작은 비인증401/일반403·관리자 CRUD/특성, 입력 거절 뒤 불변, 상품/특성·태그·버전·event·
delivery 동시 rollback, 두 connection 수정 직렬화·최신 projection, 동일 ID 재사용 뒤 version 지속,
상품 삭제 후 원래 주문 스냅샷/금액과 FK null, raw JSON/원본 receipt·UUID/ACK 보존이다.
실패 trigger/function은 failure DB의 정확한 자체 product ID/prefix에만 적용하고 finally 제거한다.

새 설치/migrate/clean·원본 초기화는 실행하지 않는다. 전후 성능 과제가 아니므로 지연 개선이나
운영 오류율을 만들지 않는다. 로컬/실제 packaged JAR·필수 CI/최신 Codex·병합/main 게이트는
분리하며 아직 실행하지 않은 조건을 완료로 기록하지 않는다.

## README 비교

기존 네 사례는 업무 원자성/전달·세션 수명·원자 복사·주문 이력 보존이다. 이번 증분의 실제 PG
근거와 비교하되 기존 MySQL 구현을 새 성과로 중복 주장하지 않고 더 강한 교체 근거가 없으면
본문/순서를 보존한다. 소스/측정/PR 증거는 해당 영역의 계획에 남긴다.

## 첫 실제 권한 실패와 조정

첫 actual HTTP 실행은 태그 `SELECT ... FOR UPDATE`에서 SQLSTATE42501을 재현했다. PostgreSQL은
행 잠금 SELECT에 대상표의 최소 한 컬럼 UPDATE 권한도 요구한다. [공식 SELECT 권한](https://www.postgresql.org/docs/18/sql-select.html)을 확인하고,
상품/특성 부모 잠금만으로 태그 잠금을 생략하는 대신 `UPDATE(tag)`만 허용하는 CI/guard 프로필을 구현했다. 원래 태그 전체
교체는 DELETE/INSERT이며 UPDATE SQL을 추가하지 않는다. `UPDATE(product_id/kind)`·표 전체 UPDATE,
이벤트/전달 상태 UPDATE/DELETE는 계속 기동/실행 경계에서 거절한다. 이 권한 실패를 운영 사고나
사용자 오류율 개선으로 표현하지 않는다. 실패한 요청은 새 상품과 version/event/delivery까지 롤백됐다.

실패 검사는 정상 DB의 광범위 prefix INSERT trigger에서 별도 failure DB의 정확 자체 행 조건으로
이전한다. 기존 매장 검사는 삭제하지 않고 `AFTER UPDATE ... IF NEW.id=<새 자체 ID> AND
NEW.name=<자체 이름+_failed>`로 보존한다. 상품 `AFTER INSERT`는 정확 자체 name,
outbox `AFTER INSERT`는 정확 자체 product_id, delivery `AFTER INSERT`는 NEW.event_id의 outbox가
그 자체 product_id일 때만 예외를 발생시킨다. 상품 수정/특성 교체/삭제와 FK 이력·version·이벤트·
PENDING 전체 상태를 각 요청 전후 비교하며 함수/trigger는 finally에서 제거한다.

## 검증과 일시 중단 이력

- 기본 `./scripts/check.sh`를 Java17/Python3.12에서 실행해 도구86·루트 Java140·PG 도구 단위18·
  JPA 단위3과 패키징이 통과했다. 기본 검사에서 실제 DB 검사의 skip은 완료로 세지 않는다.
- 첫 예약 PG18.6 정상/실패 실행은 인증/매장2·상품 읽기6·상한 rollback1·분리한 매장 실패
  rollback1이 통과했고 상품 쓰기7은 태그 잠금 권한42501 때문에 실패했다. 그 뒤 조정자가 모델
  capacity 오류로 일시 중단돼 추가 GRANT/실제 검증 전 상태를 보존했다. 고정 모델/추론을 바꾸거나
  실패를 재시도로 숨기지 않았다. 다음 신호에서 같은 lease/작업과 소유 DB로 재개했다.
- 조정자가 `UPDATE(tag)`를 추가한 뒤 실제 재조회는 tag=true, kind=false, product_id=false,
  테이블 UPDATE=false다. 같은 원인에 대한 권한 조정이며 원본 행·UUID/binding·ACK는 바꾸지 않는다.
- 같은 PG18.6 정상/실패 DB에서 전체17 통합(인증/매장2·읽기6·쓰기5·실패4)과 JPA 단위3이 통과했다.
  수령증/시연 계정 준비1은 이미 준비한 fixture여서 이 실행에서 skip했으며 성공 개수에 넣지 않는다.
- 실제 실행 JAR도14경로/16작업을 OpenAPI에서 확인했다. 비인증/일반계정 차단·관리자 상품/특성
  쓰기·엄격 입력·unknown/[]·재시작 후 가격/태그 보존·구세션401/재로그인·삭제/404가 통과했다.
  명시 실행/자동DDL/marker/source 불일치 기동 거절과 소유 JVM 정리도 확인했다.
- 정상/실패 DB의 새 시험 상품·주문과 실패 trigger/function이 남지 않았음을 별도 읽기 전용 검수로
  확인했다. 자체 version/event/delivery는 불변 기록이므로 삭제하지 않고 최종 폐기 DB 정리에 맡긴다.
- 검증한 JAR은 SHA256 `99af52d3f41237714aa44dfb1105e8f3466920b549ad27d17d2f2f5572efc776`로
  고정했다. 기본 MySQL JAR/POM·V1/V2의 SHA도 기준과 동일하다. 실행 archive에는
  MyBatis/MySQL/H2 runtime 라이브러리가 없으며 루트 MySQL 소스는 수정하지 않았다.


- 조정자의 독립 검수는 기준 MySQL JAR과 이 고정 JPA JAR, 동일한 원본80상품에서31개 GET의
  status/전체 JSON을 대조해31/31 동일했다. 목록/인기/상세/특성·필수 필터·네 정렬·LIKE 문자·
  가격/도수·잘못된 offset/404를 포함한다. 입력 명세 SHA256은
  `e38f31ae56591f658a634c18cf0a555ba93468e5d1e7d87d2689b003e63ebc12`다.
- 같은 새 허구 상품 ID81의19개 쓰기/인증 HTTP도 status/전체 JSON이 동일했고, 성공한 TX의
  새5개 이벤트(version1–5)의 JSON 내용과 PENDING·attempts0·claim/delivered null이5/5 동일했다.
  명세 SHA256은 `2feebafcbecd43cbada8281cdc15f3e058e7d4b859d7f60f99ec25c8c3975106`다.
  생성/특성/수정·중복 태그 거절·unknown 초기화·삭제와 없는 상품을 포함하며 삭제 후 불변
  이벤트도 남았다. 이것은 두 엔진의 이 부분 기능 계약 검수이고 전체 웹 인수/성능 측정이 아니다.

회귀 원문·JAR·자격·로컬 로그는 ignored 기록에만 둔다. 이 수량은 기능/정합성 검증이며 운영
오류율·서비스 지연/처리량 개선이 아니다. 모델·기획 주소나 개인 경로를 공개 근거에 넣지 않는다.
최신 PR CI/Codex·병합/main CI는 아직 별도 게이트이며 로컬 성공으로 대신하지 않는다.

## 핵심 코드와 남은 범위

[일반 상품 영속](../../tools/jpa-rehearsal/src/main/java/com/pubburi/rehearsal/persistence/JpaProductDao.java),
[특성/태그 교체](../../tools/jpa-rehearsal/src/main/java/com/pubburi/rehearsal/persistence/JpaProductFeatureDao.java),
[잠금·버전·JSON·PENDING](../../tools/jpa-rehearsal/src/main/java/com/pubburi/rehearsal/persistence/JpaProductSearchOutboxDao.java)은
[공통 생산 서비스](../../src/main/java/com/pubburi/pub/model/service/ProductSearchOutbox.java)와 같은 TX를 사용한다.
[실제 쓰기 검수](../../tools/jpa-rehearsal/src/test/java/com/pubburi/rehearsal/JpaProductWriteIntegrationTests.java)와
[실패 경계](../../tools/jpa-rehearsal/src/test/java/com/pubburi/rehearsal/JpaProductFailureIntegrationTests.java)는
Repo flush·native 잠금·FK/JSON/버전의 실제 의미를 확인한다.

DB03의 주문/후기·사용자 관리·취향/찜·추천 연동은 아직 이 제공자에 등록하지 않았다. DB05의
Psycopg3 relay/lease·UUID/ACK·검색 대체, DB04 전체 거래/계정 수명과 DB06 전체 웹/로컬/CI·
cutover/rollback도 별도다. 11월22일 마이그레이션/기능 보존 목표와 React 인수가 겹치는 일정
위험을 유지하며 이 부분 구현을 전체 DB01–06 또는 R/F20개 완료로 합산하지 않는다.
