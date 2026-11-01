# 로컬 검색 outbox relay

이 relay는 [업무 생산](SEARCH-OUTBOX.md)의 이벤트를 개별 claim해 명시한 로컬 색인으로 보낸다.
`init`·`once`·`watch`를 직접 실행할 때만 동작하며 Java 서버/기본 통합 기동은 Python 의존성이나
검색 엔진을 요구하지 않는다. 공개 상품 검색은 MySQL 그대로다. 자동 색인 재생성·retention/
재색인 세대 cutover·전체 S02 완료와 구분한다.

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
PyMySQL1.2.3의 공식 release/보안 변경과 PyPI hash를 확인했다. Java 기본 기동에 추가하지 않는다.

~~~bash
uv venv --python 3.12 .venv-relay
uv pip sync --require-hashes --python .venv-relay/bin/python tools/search-relay/requirements.lock
~~~

기존 DB는 쓰기와 relay를 중단하고 백업한 뒤 [008](migrations/008-search-outbox-delivery.sql)을
한 번 적용한다. 기존 이벤트마다 PENDING을 만들고 새로운 source UUID를 발급한다. 기존 표는
UPDATE/DELETE하지 않는다. DDL은 implicit commit이며 중간 실패/기존 표 재실행을 자동 수습하지
않는다. 기존 outbox/버전과 새 표/행 개수를 대조하고 DML rollback 여부를 확인한 뒤 기동한다.
빈 새 DB는 현재 data.sql을 사용하며 기존 볼륨에 초기화 SQL을 다시 실행하지 않는다.

DB는 numeric loopback JDBC URL과 별도 환경의 PUBBURI_DB_USER/PASSWORD를 사용한다.
root 검증 자격이나 비밀번호를 argv에 넣지 않는다. 운영 계정은 outbox SELECT와 delivery/target의
SELECT·UPDATE면 된다. 앱의 producer INSERT 권한과 분리할 수 있다.

relay는 **명시한 로컬 MySQL CA chain을 검증하는 TLS**를 필수로 한다. 소유한 Compose DB의
공개 `/var/lib/mysql/ca.pem`만 ignored 파일로 복사해 PUBBURI_RELAY_DB_CA에 지정한다. private key는
복사하지 않는다. 자동 생성 서버 인증서 이름이127.0.0.1과 일치하지 않아 hostname 검증은 끄며,
동일 CA가 발급한 다른 peer를 이름으로 구분하지 못하는 한계가 있다. numeric loopback/소유 DB에만
사용하고 외부 DB는 거절한다. CA 없거나 읽기 오류·다른 CA·non-TLS는 실패하며 plaintext로 fallback하지 않는다.
[드라이버 연결 옵션](https://pymysql.readthedocs.io/en/latest/modules/connections.html)의
ssl_verify_cert/ssl_verify_identity를 각각 명시했으며, 자동 생성 인증서에 hostname 검증까지
성공했다고 표현하지 않는다.

~~~bash
export PUBBURI_RELAY_DB_CA="$PWD/.pubburi/mysql-ca.pem"
# PUBBURI_DB_URL / PUBBURI_DB_USER / PUBBURI_DB_PASSWORD는 본인의 로컬 환경에서 설정
.venv-relay/bin/python scripts/search_relay.py init --endpoint http://127.0.0.1:9200 --index pubburi-products-v1-live
.venv-relay/bin/python scripts/search_relay.py once --endpoint http://127.0.0.1:9200 --index pubburi-products-v1-live
.venv-relay/bin/python scripts/search_relay.py watch --endpoint http://127.0.0.1:9200 --index pubburi-products-v1-live
~~~

watch는 Ctrl+C로 중단한다. 급작스러운 종료는 미ACK lease를 남길 수 있으며 만료 뒤 다른 worker가
재claim한다. 실패를 없애려고 delivered_at/token/UUID를 수정하거나 이벤트를 삭제하지 않는다.
추가 metadata 조회·DB 쓰기/잠금·이력 보관과 반복 전송 비용이 있다. 처리량/지연 개선 수치는 미측정이다.

구현/실제 조건·실패 기록은 [작업 계획](plans/2026-11-01-durable-search-relay.md)을 따른다.
