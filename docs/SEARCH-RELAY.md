# 로컬 검색 outbox relay

이 relay는 [업무 생산](SEARCH-OUTBOX.md)의 이벤트를 개별 claim해 명시한 로컬 색인으로 보낸다.
`init`·`once`·`watch`를 직접 실행할 때만 동작하며 Java 서버/기본 통합 기동은 Python 의존성이나
검색 엔진을 요구하지 않는다. 공개 상품 검색은 현재 업무 DB로 기동한다. 별도 로컬 검색 세대
전환은 아래 운영 명령을 사용한다.

## 전달 계약

- 업무 변경·버전·불변 outbox와 `t_product_search_delivery`의 PENDING 생성은 같은 TX다.
  예약 INSERT 실패도 업무를 rollback한다. 전송 상태는 별도 표에 두며 원문을 수정하지 않는다.
- relay는 READ COMMITTED의 짧은 TX에서 due PENDING 또는 만료 IN_FLIGHT 한 행을
  `FOR UPDATE SKIP LOCKED`로 고른다. UUID token·DB 시각의 120초 lease·attempts를 기록하고
  commit한 후 HTTP를 실행한다. 원격 IO 동안 claim row/업무 row 잠금을 유지하지 않는다.
  [MySQL의 SKIP LOCKED](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)는
  일반 정합 조회를 대신하는 것이 아니라 이 queue의 잠긴 작업을 건너뛰는 용도로만 사용한다.
- ACK는 같은 token·미만료 lease와 원래 target fingerprint/source를 확인할 때만 DELIVERED로
  바뀐다. target은 ack TX 안에서 FOR SHARE로 확인한다. 다른 worker의 재claim 후 이전 token은
  ACK/재시도 상태를 바꾸지 못한다. 클라이언트 로컬 시계로 lease를 판정하지 않는다.
- 전송은 at-least-once다. 엔진이 받은 뒤 DB ack 전에 프로세스가 종료되면 lease 만료 후 같은
  이벤트를 다시 보낸다. strict external version의 applied·canonical duplicate·더 높은 canonical
  버전이 있는 stale_ignored만 ACK한다. 409라는 이유만으로 성공시키지 않는다.
- ack는 엔진의 조건부 쓰기 수락을 뜻하며 검색 refresh 완료가 아니다. relay는 refresh=false,
  기존 관리 CLI는 wait_for 기본을 유지한다. GET 정본은 realtime으로 확인한다.
- 429/5xx·연결/전체 기한/짧은 응답 같은 명시 transient는 token을 확인해 PENDING과 DB 시각
  backoff(2초부터 최대60초)를 남긴다. attempt는 포화 증가하며 실패를 폐기/가짜 ACK하지 않는다.
  영구4xx·schema/source/target/입력 불일치는 watch도 nonzero로 중단한다. 상태/고정 오류 코드만
  출력하고 원문·자격·서버 에러 본문은 출력하지 않는다.
- 최초 source/엔진/schema/target 검증은 watch 루프 진입 전 fail-fast다. 기동 직후 연결 거부나
  일시 장애도 nonzero로 종료하며 자동 기동 재시도를 제공하지 않는다. 위 재전달은 초기 검증을
  통과한 watch 루프 안의 지정 transient에만 적용한다. 상태를 확인한 뒤 명시적으로 다시 기동한다.
- event ID는 스캔 우선순위일 뿐 commit watermark가 아니다. 높은 ID를 먼저 처리해도 뒤늦게
  commit한 낮은 ID의 pending 행은 다음 탐색 대상이다. 전체 최대 ID로 처리 완료를 결정하지 않는다.

## source와 물리 target

008의 singleton source UUID는 DB의 원본 수명을 나타내며 relay 색인의 `_meta.source_id`와
일치해야 한다. 기존 무source 관리 CLI 계약은 유지하고 source-bound index에 붙을 때만
`--source-id`를 명시한다. relay는 명시 init 외 기존 다른 source 색인을 채택하지 않는다.
DB에는 source·cluster UUID·physical index UUID/name·schema/data kind의 fingerprint를 저장한다.
endpoint 포트는 identity가 아니므로 동일 volume 재시작의 포트 변경은 허용한다.

전달 전 metadata/UUID와 전달 뒤 metadata/UUID를 확인하고 DB ack의 target도 다시 잠금 확인한다.
alias 응답은 지정한 physical index여야 한다. 새 물리 index로 교체되면 과거 ACK를 재사용하지 않고
중단한다. 이 관찰 검사들이 엔진과 DB를 분산 TX로 묶거나 마지막 검사와 ack 사이의 엔진 변경을
원자적으로 막는 것은 아니다. 관리 중 index/alias/source 변경의 단독 소유가 필요하며, ack 뒤
색인이 유실돼도 자동 복원하지 않는다. DB 복제는 source UUID/ack도 복사하므로 별도 원본으로
운영하려면 명시적인 source/재색인 복구 계획이 필요하다. 임의 UUID/ACK reset은 제공하지 않는다.

## 설치·기동

Python3.12의 별도 환경에 [고정 의존성](../tools/search-relay/requirements.lock)을 설치한다.
MySQL의 PyMySQL1.2.3과 PostgreSQL의 Psycopg/binary3.3.7을 공식 release·PyPI hash로 고정했다.
Java 기본 기동에 추가하지 않는다. 두 DB는 같은 Relay/Engine/CLI를 사용하며 JDBC URL이 Store를 선택한다.

~~~bash
uv venv --python 3.12 .venv-relay
uv pip sync --require-hashes --python .venv-relay/bin/python tools/search-relay/requirements.lock
~~~

기존 DB는 쓰기와 relay를 중단하고 백업한 뒤 [008](migrations/008-search-outbox-delivery.sql)을
한 번 적용한다. 기존 이벤트마다 PENDING을 만들고 새로운 source UUID를 발급한다. 기존 표는
UPDATE/DELETE하지 않는다. DDL은 implicit commit이며 중간 실패/기존 표 재실행을 자동 수습하지
않는다. 기존 outbox/버전과 새 표/행 개수를 대조하고 DML rollback 여부를 확인한 뒤 기동한다.
빈 새 DB는 현재 data.sql을 사용하며 기존 볼륨에 초기화 SQL을 다시 실행하지 않는다.

DB는 numeric loopback JDBC MySQL/PostgreSQL URL과 별도 환경의 PUBBURI_DB_USER/PASSWORD를 사용한다.
root 검증 자격이나 비밀번호를 argv에 넣지 않는다. 운영 계정은 outbox SELECT와 delivery/target의
SELECT·UPDATE면 된다. 앱의 producer INSERT 권한과 분리할 수 있다.

relay는 **명시한 로컬 MySQL CA chain을 검증하는 TLS**를 필수로 한다. 소유한 Compose DB의
공개 `/var/lib/mysql/ca.pem`만 ignored 파일로 복사해 PUBBURI_RELAY_DB_CA에 지정한다. private key는
복사하지 않는다. 자동 생성 서버 인증서 이름이127.0.0.1과 일치하지 않아 hostname 검증은 끄며,
동일 CA가 발급한 다른 peer를 이름으로 구분하지 못하는 한계가 있다. numeric loopback/소유 DB에만
사용하고 외부 DB는 거절한다. CA 없거나 읽기 오류·다른 CA·non-TLS는 실패하며 plaintext로 fallback하지 않는다.
[MySQL 드라이버 연결 옵션](https://pymysql.readthedocs.io/en/latest/modules/connections.html)의
ssl_verify_cert/ssl_verify_identity를 각각 명시했으며, 자동 생성 인증서에 hostname 검증까지
성공했다고 표현하지 않는다.

~~~bash
export PUBBURI_RELAY_DB_CA="$PWD/.pubburi/mysql-ca.pem"
# PUBBURI_DB_URL / PUBBURI_DB_USER / PUBBURI_DB_PASSWORD는 본인의 로컬 환경에서 설정
.venv-relay/bin/python scripts/search_relay.py init --endpoint http://127.0.0.1:9200 --index pubburi-products-v1-live
.venv-relay/bin/python scripts/search_relay.py once --endpoint http://127.0.0.1:9200 --index pubburi-products-v1-live
.venv-relay/bin/python scripts/search_relay.py watch --endpoint http://127.0.0.1:9200 --index pubburi-products-v1-live
~~~

## PostgreSQL 명시 선택

relay 전용 환경의 `PUBBURI_DB_URL=jdbc:postgresql://127.0.0.1:5432/pubburi`와 별도
`PUBBURI_DB_USER/PASSWORD`, `PUBBURI_RELAY_DB_CA`를 지정하면 PostgreSQL Store를 사용한다.
현재 Java 기본 MySQL 설정을 바꾸지 않는다. [Flyway/복사 계약](POSTGRESQL-MIGRATION.md)으로
준비한 `pubburi` schema와 이관한 source UUID를 그대로 사용하며 init도 UUID/기존 ACK·binding을
생성/초기화하지 않는다. 새 source 수명은 별도 명시 준비가 필요하다.

Psycopg 연결은 공개 CA의 **verify-full**로 numeric 주소의 SAN까지 확인하며 SCRAM/TLS·UTF8·
UTC·READ COMMITTED를 사용한다. schema 경로·연결/SQL/잠금 제한을 명시하며 non-TLS나
다른 CA/이름으로 plaintext fallback하지 않는다. MySQL의 위 hostname-off 한계와 구분한다.
[libpq 인증서 검증](https://www.postgresql.org/docs/18/libpq-ssl.html)을 따른다.

PG의 `CURRENT_TIMESTAMP`는 TX 시작 시각이므로 lease·due·ACK/retry·binding에는
[`clock_timestamp()`](https://www.postgresql.org/docs/18/functions-datetime.html#FUNCTIONS-DATETIME-CURRENT)를
사용한다. ACK는 target FOR SHARE 다음에 해당 claim 행을 먼저 잠그고, retry도 해당 claim 행을
먼저 잠근다. 잠금 뒤의 새 UPDATE 명령에서 token/status와 실제시각 만료를 판정한다. 단일 UPDATE의
시각 조건이 row 잠금 대기 전에 평가될 가능성까지 분리하며 원격 IO 중에는 이 잠금을 잡지 않는다.
불변 json은 `payload::text`와 바이트 상한으로 읽고 UUID의 canonical text·boolean0/1 변환으로
기존 strict Engine 입력을 유지한다. JSON 자동 객체화/재직렬화나 새 wire는 없다.

별도 relay 역할의 표/열 권한은 다음이 전부다. 역할·DB/TLS와 source 준비는 관리자가 명시하며
runtime에 업무 쓰기·version 접근·INSERT/DELETE·sequence/DDL/TEMP를 주지 않는다.
검증 DB는 PUBLIC의 TEMP 권한도 제거해 상속으로 넓어지지 않게 준비한다.

~~~sql
GRANT USAGE ON SCHEMA pubburi TO pubburi_relay;
GRANT SELECT ON pubburi.t_product_search_outbox,
    pubburi.t_product_search_delivery, pubburi.t_product_search_relay_target TO pubburi_relay;
GRANT UPDATE(status,claim_token,lease_until,available_at,attempts,delivered_at,last_error)
    ON pubburi.t_product_search_delivery TO pubburi_relay;
GRANT UPDATE(fingerprint,identity_json,bound_at)
    ON pubburi.t_product_search_relay_target TO pubburi_relay;
~~~

watch는 Ctrl+C로 중단한다. 급작스러운 종료는 미ACK lease를 남길 수 있으며 만료 뒤 다른 worker가
재claim한다. 실패를 없애려고 delivered_at/token/UUID를 수정하거나 이벤트를 삭제하지 않는다.
추가 metadata 조회·DB 쓰기/잠금·이력 보관과 반복 전송 비용이 있다. 처리량/지연 개선 수치는 미측정이다.

## PostgreSQL 검색 세대 전환

검증 표식이 있는 로컬 PostgreSQL18.6과 source-bound OpenSearch에서만 별도 작업으로 실행한다.
기존 전달을 모두 ACK까지 처리한 뒤 relay와 상품·특성·주문 쓰기를 중단한다. 읽기 앱은 계속 서비스할
수 있다. 운영자가 기존 binding의 물리 이름·UUID·cluster UUID와 새 물리 이름·UUID를 확인해 고정한다.
새 index는 기존 `search_index.py init`의 `backend_product_events`와 동일 source UUID로 준비한다.
`pubburi-products-v1-read`는 고정 읽기 별칭으로 예약한다. 처음 별칭을 만드는 경우에만
`--bootstrap-read-alias`를 명시한다. 기존 별칭은 현재 binding에 대응하는 단일 필터 별칭이어야 한다.

~~~bash
# 기존 PUBBURI_PG_URL, PUBBURI_PG_DB_NAME, PUBBURI_PG_CONTAINER_ID, PUBBURI_PG_VALIDATION_LABEL,
# PUBBURI_PG_RUN_TOKEN, PUBBURI_PG_CA와 별도 제어 역할의 PUBBURI_DB_USER/PASSWORD를 준비한다.
export PUBBURI_DB_URL="$PUBBURI_PG_URL" PUBBURI_RELAY_DB_CA="$PUBBURI_PG_CA"
export PUBBURI_SEARCH_CUTOVER_MUTATING=1 PUBBURI_SEARCH_CUTOVER_WRITERS_STOPPED=1
.venv-relay/bin/python scripts/search_cutover.py --endpoint "$ENGINE_ORIGIN" \
  --source-id "$SOURCE_UUID" --cluster-uuid "$CLUSTER_UUID" \
  --old-index "$OLD_INDEX" --old-uuid "$OLD_INDEX_UUID" \
  --new-index "$NEW_INDEX" --new-uuid "$NEW_INDEX_UUID"
~~~

제어 역할은 superuser·확장 권한 없이 marker와 target, 상품/특성/태그/version/outbox/delivery
SELECT, 해당 여섯 업무·전달 표의 `MAINTAIN`, target의 `UPDATE(fingerprint,identity_json,bound_at)`을
사용한다. target row를 `FOR UPDATE NOWAIT`, 여섯 표를 `SHARE NOWAIT`로 잠가 producer와 relay
쓰기를 막고, 모든 이벤트에 완료 ACK가 있는지 확인한다. 읽기와 별칭 이동 후 binding이 일치할 때까지의
검색 대체는 [공개 검색](PRODUCT-SEARCH.md)을 따른다. 별도 DB 복사본과 별개 OpenSearch 운영자를
동일 source/색인에 붙이지 않는다. [PostgreSQL 잠금](https://www.postgresql.org/docs/18/sql-lock.html)

한 번에 전체 불변 이력 최대1,000건·4MiB·60초를 처리한다. 이벤트 ID 최대값은 경계로 쓰지 않는다.
각 상품의 연속 버전과 최신 행·삭제 표식을 현재 상품/특성에 대조하고, 새 색인의 문서 집합과 정확한
최신 내용·버전을 검증한다. 이후 필터·비쓰기 설정을 유지하며 별칭을 원자적으로 옮기고 기존 DB
binding을 조건부 변경한다. 원본 UUID, 업무값, version/outbox/ACK와 구 색인은 보존한다.
[OpenSearch 별칭 변경](https://docs.opensearch.org/latest/api-reference/alias/aliases-api/)

별칭 이동과 DB 갱신은 서로 다른 시스템의 작업이다. 중간 실패가 나면 쓰기/relay 중단을 유지하고
**동일 인자**로 재실행한다. 기존 별칭/기존 binding이면 검증부터, 새 별칭/기존 binding이면 새
색인을 재검증한 뒤 binding 변경부터 수습한다. 새 별칭/새 binding이면 재검증 후 완료 상태를 반환한다.
제3의 물리 세대나 변조된 identity는 거절한다. 성공 뒤 새 물리 이름으로 relay를 시작한다.

기존 MySQL 구현/실제 조건·실패는 [전달 기록](https://github.com/Pubburi/Backend/blob/53c15c06327f4518b9c269ac95ccdd68b3f05c81/docs/plans/2026-11-01-durable-search-relay.md),
PG 제공/검증 범위는 [어댑터 기록](https://github.com/Pubburi/Backend/blob/ac2b0cd15f77f85feffaefbd74b1a228e2839399/docs/plans/2026-11-04-postgresql-search-relay.md)에 구분한다.
업무 DB 전체 전환과 검색 색인의 세대 전환은 별도 인수 범위다.
