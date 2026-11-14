# 승인된 source artifact의 PostgreSQL 복사

DB02의 독립 이관 도구다. [읽기 전용 export](SOURCE-SNAPSHOT.md)로 만든 승인 파일을 **빈
PostgreSQL 검증 DB**에 복사하고 같은 TX에서 전체 값/ID 예약/수령증을 검수한다. 현재 API의
MySQL/MyBatis·기존001–009와 Flyway V1–V3는 보존한다. V4의 추천 피드백·V5의 비공개 notebook·V6의 cart/operation receipt·V7의 내부 알림은 PG에서 새로
기록하는 데이터다. 승인 MySQL v1/v2 파일에는 이 데이터가 없으므로 import와 원본 일치 검수는
대상 피드백/notebook/cart3표/알림이 비어 있을 때만 허용한다. PG에서 생성한 데이터는 [PG 백업·복구](LOCAL-DEVELOPMENT.md)의
검증된 스키마·데이터 단위를 사용한다. JPA API(DB03), Psycopg3 relay(DB05),
서비스 cutover/역이관(DB06)까지 완료한 것은 아니다.

## 실행 조건

Java17 독립 모듈을 빌드하고 [환경 예시](../tools/postgresql-migration/.env.example)를 따른다.
대상은 명시 CID/label/loopback publication을 가진 `*_ci` 복사 DB다. 실제 PostgreSQL18.6 UTF8,
CA/SAN verify-full·SCRAM·비superuser·이번 marker를 검사한다. schema 설치는 기존 명령과
분리하며 import 명령이 create/update/clean·기존 데이터 병합을 자동 수행하지 않는다.

~~~bash
./mvnw --batch-mode --no-transfer-progress -f tools/postgresql-migration/pom.xml verify
python3 scripts/postgresql_schema.py migrate
python3 scripts/postgresql_import.py import-source
python3 scripts/postgresql_import.py verify-import
python3 scripts/postgresql_import.py verify-cutover
~~~

원본과 대상의 쓰기·relay·DDL이 중단된 작업 창에서 `PUBBURI_PG_IMPORT_WRITES_STOPPED=1`을
명시한다. import만 `PUBBURI_PG_MUTATING=1`이 필요하고 verify는 READ ONLY다.
`PUBBURI_SOURCE_SNAPSHOT_DIR`은 비공개 승인 artifact이며 `PUBBURI_PG_IMPORT_SHA256`,
`PUBBURI_PG_IMPORT_SOURCE_ID`, `PUBBURI_PG_IMPORT_WINDOW_UUID`를 해당 승인 파일에 맞춰
핀한다. 디렉터리의 파일을 다시 읽어 승인 뒤 내용을 바꾸는 대신 검수한 바이트/트리를 한 번
잡아 사용한다. 자식에는 대상 전용 자격/입력 설정만 전달하며 source reader·admin/API 자격은
전달하지 않는다. SQL/비밀번호/행 원문은 CLI 응답에 출력하지 않는다.

`verify-cutover`만 현재 원본의 소유 CID/label·TLS reader와 새 READ ONLY RR snapshot을 추가로
연다. 승인 파일의 server/source/window·문자 collation·16/17표 전체 typed cell·7 AUTO_INCREMENT를
현재 source와 비교하고, target의 기존 verify-import도 같은 승인 트리로 검사한다. 일반 UPDATE,
원본/대상 행 변경·window 변경·rollback으로 소비한 ID·PG-only 피드백은 초기 일치 검수를 통과하지
못한다. DB/sequence/receipt를 쓰거나 source를 덮어쓰지 않는다. 두 DB의 실제 writer/relay/DDL
정지가 계속 전제이며 READ ONLY나 QUIESCED marker는 다른 writer를 물리적으로 막지 않는다.

이 hash/수령증은 **서명·권리 승인·현재 source writer 정지의 증명**이 아니다. import-source/verify-import는 source에
재접속하지 않는다. 원본 파일 보관/접근 통제와 실제 작업 창 유지는 운영자의 별도 책임이며
환경 선언만으로 모든 writer/관리자 직접 변경을 물리적으로 통제했다고 주장하지 않는다.

## 단일 트랜잭션

1. source 승인/형식/타입/바이트 hash·UUID/window를 검사하고 target Flyway checksum을 검수한다.
2. 실제 target 연결/marker를 다시 확인하고16업무표와 V2 수령증 표를 ACCESS EXCLUSIVE로 잠근다.
3. 현재 컬럼 타입/길이·nullable/정밀도·PK/FK/parent schema·index·identity/sequence 속성·예정
   C/ICU collation을 검사한다. 실제 ICU provider/locale·nondeterministic 설정도 확인한다.
4. **모든 업무표와 수령증이 비어 있어야** 한다. UPDATE/UPSERT/ON CONFLICT DO NOTHING으로
   충돌을 숨기거나 기존 표/row를 지우지 않는다. 별도 Flyway V2는 import metadata만 추가한다.
5. 모든 컬럼과 명시 ID를 parameter로 INSERT한다. 정수/금액·UUID/epoch·삭제 참조 NULL·known
   flags/취향 숫자·FLOAT/DOUBLE raw bits·UTC 시각·JSON DB 반환 원문을 정확한 타입으로 바인딩한다.
6. 선택한16/17표의 **전체 행을 target에서 다시 읽어 canonical PK tuple별로 모든 셀을 대조**한다. source
   PK 정렬과 target ICU 정렬의 결과 순서가 같다고 가정하지 않는다. 중복/누락/정규화된 값은 실패다.
7. 7개 sequence는 고정 allowlist/소유 identity·증가1/범위/cache1/nocycle을 검수한다. 값 변경 없는
   CACHE1 ALTER로 TX 잠금을 잡은 뒤 실제 next를 읽는다. bare nextval은 업무표 잠금만 따르지
   않으므로 검사→RESTART 사이를 잠금으로 보호한다. source-next보다 이미 높으면 거절하며
   뒤로 옮기지 않는다. 삭제 후 남은 상품 version ID도 source 고수위 검수에 포함한다.
8. source-next로 transactional ALTER SEQUENCE RESTART를 적용하고 source/server/window UUID,
   승인 SHA/행수의 singleton 수령증을 같은 TX에 INSERT한 다음 commit한다.

[명시 ID INSERT](https://www.postgresql.org/docs/18/sql-insert.html),
[typed JDBC parameter](https://jdbc.postgresql.org/documentation/query/),
[transactional RESTART](https://www.postgresql.org/docs/18/sql-altersequence.html),
[실제 collation catalog](https://www.postgresql.org/docs/18/catalog-pg-collation.html)를 따른다.
setval/nextval을 원복 도구로 쓰지 않으며 gapless ID를 보장하지 않는다. target ICU/문자열 충돌은
원문을 병합/정규화하지 않고 PK/FK 오류로 전체 거절한다. 이 검사는 모든 Unicode 질의/정렬이나
향후 JPA 서비스 동등성의 검증을 대신하지 않는다.

## 실패·중복·결과 미확인

commit 전 INSERT/대조/제약/예약/수령증 실패는 같은 TX를 rollback한다. 중복 실행은 수령증/기존
데이터 때문에 거절하며 추가 쓰기·ID 예약을 하지 않는다. 실패 원인을 고친 **빈 대상**에서 같은
승인 artifact를 다시 실행할 수 있지만 자동 재시도나 기존 DB 초기화 명령은 없다.

| CLI 결과 | 의미·다음 조치 |
|---|---|
| passed | import의 commit ACK와 연결 종료, 또는 읽기 전용 대조 완료 |
| postgresql_source_import_rejected | 입력/대상/사전 commit 검수 실패. source/target 상태를 확인하고 수정 |
| postgresql_import_commit_status_unknown | commit 예외 또는 부모 deadline으로 결과를 확정하지 못함. 성공/rollback으로 추정하지 않고 verify-import |
| postgresql_import_committed_close_failed | commit ACK 후 연결 종료가 실패함. 이미 받은 ACK를 rollback으로 바꾸지 않고 verify-import |
| postgresql_import_verification_timed_out | 읽기 전용 대조가 deadline 안에 완료되지 않음. 성공으로 보지 않음 |

`verify-cutover`의 `postgresql_source_import_rejected`에는 다음 고정 `reason`이 함께 나올 수 있다.

| `reason` | 일치 검수를 막은 상태 |
|---|---|
| `source_cutover_identity_or_highwater_changed` | MySQL 원본 식별자, 작업 창, collation 또는 AUTO_INCREMENT가 승인본과 다름 |
| `source_cutover_rows_changed` / `source_cutover_value_changed` | MySQL 원본의 행 구성 또는 값이 승인본과 다름 |
| `postgresql_import_rows_missing` / `postgresql_import_value_mismatch` | PostgreSQL 복사본의 행이 누락됐거나 값이 승인본과 다름 |
| `postgresql_import_reservation_changed` | PostgreSQL에서 승인본과 다른 identity 값이 관측됨 |
| `postgresql_v1_review_tags_unsupported` | PostgreSQL 후기 태그가 v1 승인본의 범위를 벗어남 |
| `postgresql_import_feedback_not_empty` / `postgresql_import_notebook_not_empty` | PostgreSQL 추천 피드백 또는 개인 기록이 남아 있음 |
| `postgresql_import_cart_not_empty` / `postgresql_import_notifications_not_empty` | PostgreSQL 장바구니·요청 영수증 또는 내부 알림이 남아 있음 |

코드는 승인본과 같다고 볼 수 없는 첫 사유를 가리킨다. 기존 승인본에 없는 PG 업무 데이터는
[PG 백업·복구](LOCAL-DEVELOPMENT.md)를 사용해 보존한다. 이 검수는 PG→MySQL 역이관을 실행하지 않는다.

COMMIT 요청이 서버에 도달한 뒤 응답이 사라지면 rollback 호출만으로 결과를 확정할 수 없다.
[연결 결과 분류](../tools/postgresql-migration/src/main/java/com/pubburi/migration/ImportTransaction.java)는
commit 시도/확인을 자원 종료 바깥에서도 구분한다. 실제 네트워크 ACK 유실을 실험했다고
표현하지 않으며 연결 단위 주입으로 분류 경계를 확인한다.

승인 v1은 기존16표/receipt/모델·index hash를 유지하고 비어 있지 않은 후기 태그를 거절한다.
명시 export된 v2는17표의 원문 태그까지 INSERT/PK/FK/전체 셀 비교·row count에 포함한다.
importer는 검수한 파일의 version/format/hash로 표와 lock을 선택하며 V3가 미적용됐으면 쓰기 전에
멈춘다. 기존 V2 DB 수령증의 artifact SHA와 행수에 버전·태그 내용도 결합되므로 수령증 컬럼이나
과거 migration을 바꾸지 않는다. 저장 태그의 API 정규화·질의 collation 동등성은 추정하지 않으며
변경/누락된 태그는 verify-import가 실패한다. 이 증분은 DB06 기본 스택/복구·cutover 인수와 별개다.

verify-import는 쓰기가 중단된 같은 target/source 승인 조건에서 READ ONLY RR·ACCESS SHARE로
현재 수령증/전행/sequence 속성과 예약 상태를 대조한다. DB 데이터를 지우거나 새 commit으로
재전달하지 않는다. source와 같은 상태의 복사본 확인이며 실제 앱이 쓰기를 시작한 뒤 사용하는
일반 health 검사나 과거 성공 수령증만 보고 영구 데이터 동등성을 선언하는 기능이 아니다.
현재 writer/직접 nextval이 활동 중이면 sequence 관측을 원본과 같은 상태로 해석하지 않는다.

## 검증과 범위

[작업 기록](https://github.com/Pubburi/Backend/blob/3748f25718e13a2e76012d1a86c5a9fd36b75fe6/docs/plans/2026-11-02-postgresql-source-import.md)에 기본 copied source와 별도 authored
타입 fixture를 나눠 기록한다. raw snapshot/비밀번호 hash/개인값/SourceImportIntegration XML은
공개하지 않는다. required backend-ci는 기존 PG/source/MySQL HTTP/엔진을 유지하며 새 import와
복구·충돌·타입 조건을 같은 필수 job에서 실행한다. 전체 프로젝트 이관/모델 품질·성능 성과로
부풀리지 않는다.
