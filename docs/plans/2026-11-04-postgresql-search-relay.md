# DB05 PostgreSQL relay 어댑터

기준 main `8f59316`, 작업 `codex/postgresql-search-relay`다. 기존 명시 실행 Relay/Engine/CLI와
MySQL 경로를 보존하면서 PostgreSQL용 Psycopg3 연결/Store를 추가한다. 기본 API와 V1/V2,
공개 검색·대체 검색·전체 DB05–06/cutover는 이번 완료 범위가 아니다.

## 구현 전 계약과 검증 기준

- JDBC PostgreSQL URL을 명시한 경우에만 새 어댑터를 선택한다. numeric loopback·별도 자격과
  공개 CA의 verify-full·서버 SAN·SCRAM, UTC·READ COMMITTED를 요구한다. plaintext로 되돌리지 않는다.
- claim/ACK/retry/bind는 DB의 실제시각 `clock_timestamp()`를 사용한다. TX 시작 시각이나
  클라이언트 시계로 잠금 대기 뒤의 lease를 판정하지 않는다. SKIP LOCKED·target FOR SHARE와
  token fencing·원격 IO 전 commit, 포화 attempts·DB backoff와 기존 오류 분류를 유지한다.
- UUID는 canonical text, boolean은 기존 strict0/1, 불변 json은 `payload::text`로 읽는다.
  원문·source UUID·기존 ACK/binding을 reset하거나 재직렬화하지 않는다.
- 별도 relay 역할은 outbox SELECT·delivery/target SELECT와 필요한 열 UPDATE만 받는다.
  업무 쓰기·INSERT/DELETE·sequence/DDL/TEMP 권한을 더하지 않는다. fixture control과 분리한다.
- 승인 source의 별도 PG 복사본과 처음부터 새 수명인 authored queue를 구분한다. 원본 업무행은
  읽기 전용으로 보존하고 새 실제 관리자 API 상품 이력과 이 복사본의 명시 PENDING 전달을 검증한다.
  queue의 시간/target 실패 주입은 정확히 예약한 폐기 범위에서만 실행하며 원문 이벤트는 삭제하지 않는다.
- 실제 엔진에서 낮은 ID의 지연 commit·두 worker와 IO 중 잠금 해제·실제시각/만료 token·
  ACK 직전 종료와 중복 재전달·높은 삭제/오래된 갱신·HTTP400/429/503/TCP 실패·source/target
  불일치·동일 volume 재시작/물리 index 교체를 검사한다. 기존 MySQL 전체 lifecycle도 유지한다.

공식 [Psycopg release notes](https://www.psycopg.org/psycopg3/docs/news.html)와
[3.3.7 배포](https://pypi.org/project/psycopg/3.3.7/),
[동일 binary 배포](https://pypi.org/project/psycopg-binary/3.3.7/)를 다시 확인했다.
11월3일 정식3.3.7과 Python3.12·Linux/macOS wheel을 hash-pin한다. 직전 조사 당시의3.3.6을
최신이라고 재사용하지 않으며 기존 PyMySQL1.2.3 핀과 Java 기본 기동은 보존한다.

## 상태와 한계

Python3.12의 실제 PostgreSQL18.6/TLS·분리 runtime/control과 OpenSearch3.9.0/nori에서
별도 queue18건을 전달했다. 낮은ID 지연 commit·두 worker/IO 중 잠금 해제·SKIP LOCKED,
target 대기 뒤 due/새 lease와 target/delivery 잠금 중 만료한 ACK/retry 거절을 확인했다.
이전 token·포화 attempts/최대60초 DB backoff·실제 HTTP400/429/503/TCP 거절과 정상 복구,
실제 worker의 엔진 쓰기 뒤 exit23·별도 CLI의 duplicate ACK도 통과했다. json 원문/개행과
canonical UUID/strict0·1을 보존했고 다른 CA/SAN·non-TLS는 인증 전에 거절했다.

동결 원래 JPA JAR `3bfabd97f8a13bb9e8c69b93658c61d52323b041413b16f6e8ed1b73a9981d6e`의
실제35경로/42작업·세션을 유지했다. 새 자체 상품의 생성/가격 수정/특성 교체/삭제4버전을 별도
watch로 전달해 현재 가격/태그·최종 tombstone·옛 갱신 거절을 확인했다. 복사본의 기존80PENDING과
합쳐84건이었다. 원본12업무표 전체와 기존 버전·event 원문/시각·source UUID는 exact 비교로
보존했다. 승인 snapshot의 최초 DELIVERED는0이며 기존 이관 ACK가 있던 실험으로 꾸미지 않는다.

조정자는 같은 소유 volume을 cold restart하고 실제 획득한18/84건의 전체 ACK행이 그대로인지
대조했다. binding·tombstone/idle도 유지됐다. queue의 한 physical index만 전달 중 교체하자
새 이벤트1건은 PENDING/delivered_at NULL로 남았고 다음 실행도 기존 binding 불일치로 거절했다.
18/84의 기존 완료 ACK·source UUID는 교체 뒤에도 exact였다. 이 안전 거절을 가짜 완료/자동
색인 유실 복구로 바꾸지 않았다. 엔진 restart/교체는 조정자만 수행했다.

기존 Java17 기본 check와 최종 Python 도구95개, 실제 JPA75통합+단위3이 통과했고 준비2 skip은 별도다. 첫 실제 JPA
실행의 기본 목록4조건은 전용 scratch가 아직80개인 준비 전제에서 실패했다. 조정자가 동결
원래 관리자 DELETE API로 이 복사본만0개로 준비하고 같은 전체 회귀를 재실행했다. Java 제품/
테스트/SQL은 바꾸지 않았다. 초기 전체 XML/system-out·실패4개는 재실행 전 보존했지만 private
driver가 고정 Maven aggregate 로그를 덮은 보존 한계는 별도로 기록했다. 최종 로그를 분리하고
이후 덮어쓰기를 거절하도록 교정했다. 준비·검증 조건 문제를 제품 결함/자연 오류율로 합산하지 않는다.

별도 MySQL8.4/TLS 복사본에서도 원래 packaged HTTP 전체 계약 함수를 유지해 실행했다.
이전 검증과 같은 source80 보존용 자체 상품 인자와 입력 artifact의 출력 위치만 private driver에서
지정했다. 기존80상품/계정·기존 event/delivery/target은 exact였고 새20계정/3주문·자체 상품을
정리했다. 원래 MySQL relay 함수는 새 queue12건과 업무/기존 backfill116건을 전달했다.
조정자의 두 번째 동일 volume 재시작·legacy queue index 교체 뒤 기존12/116 ACK 전행과
PG18/84 ACK도 모두 exact였다. legacy 새 교체 이벤트도 미ACK로 남았으며 PG의 안전 거절
상태/UUID를 복구라는 이름으로 reset하지 않았다. 두 DB의 분모와 source fixture는 합산하지 않는다.

정상/실패/scratch의 자체 계정/상품·실패 trigger/function은0, 상품 수80/80/0·runtime SELECT는
복원됐다. 소유 JPA/legacy API·watch/crash 자식과 API listener는 종료됐고 private JPA 복사본은
제거했다. 새 불변 version/event/delivery는 삭제하지 않았으며 container/volume 정리는 조정자 소유다.

기본 검사에서 새 PG fixture의 소유/기존 출력·공개 경로 거절도 포함한다. required CI는 기존
MySQL 전체 HTTP/relay·PG/import/JPA를 유지하고 별도 queue/최소 relay 역할을 준비해 같은
엔진 lifecycle에 PG를 추가한다. restart/교체 전후의 비어 있지 않은 ACK 전행 해시 대조도 CI에
내장했다. 초기 sandbox의 DNS/기존 HTTP fixture bind 거절은 허용된 환경에서 같은 설치/검사를
통과했으며 보호·테스트를 약화하지 않았다. 원문 로그/자격/실제 자료는 업로드하지 않는다.

첫 [필수 CI41706511385](https://github.com/Pubburi/Backend/actions/runs/41706511385)는 source
`04a071a`에서 fake cleanup의 네 assertion으로 실패했다. 새 PG relay 실행 플래그를 job 전체에
두어 실제 fixture 준비 전 unit에도 전파했고, fake engine의 정리 단계 전에 미생성 PG 입력을
열어 FileNotFoundError가 생긴 원인이었다. 같은 flag/미생성 조건으로 로컬의 동일4실패를
재현한 뒤 실행 플래그를 source/JPA 준비와 실제 search/relay 통합 두 step으로 한정했다.
fake cleanup 환경도 외부 값에서 격리하고 같은 유입 회귀를 추가해5개 테스트를 모두 통과했다.
정리/비소유 자원 보호 assertion·필수 실PG 단계는 유지하며 임의 재시도/skip으로 처리하지 않는다.
같은 flag를 상속한 전체 check도 Python93/Java149·이관단위18/JPA단위3으로 통과했다.
운영 adapter/JAR와 앞의 실제 PG/MySQL 전달 결과는 변경하지 않았다. 최신 head의 fresh CI와
Codex는 새로 확인하며, 첫 head의 리뷰 완료를 수정 head의 승인으로 대신하지 않는다.

두 번째 [CI41707627953](https://github.com/Pubburi/Backend/actions/runs/41707627953)는 위 도구/
PG 준비·source/import/JPA·기본 HTTP를 통과한 뒤 마지막 relay에서 실패했다. CI가 전체 JPA
회귀 DB를 business 입력으로 재사용한 차이를 확인했다. 기존 원문 저장 테스트는 whitespace와
key 순서 검증을 위해 name/tasteTags만 있는 부분 document를 의도적으로 append/schedule해
보존한다. 같은 raw fixture가 변경 없는 strict claim_document에서 relay_outbox_payload_invalid로
거절되는 것을 재현했다. 이를 실제 업무 producer의 결함으로 바꾸거나 기존 테스트/원문을 삭제하지 않는다.

CI도 조정자의 실제 검증과 같이 별도 승인 snapshot business DB를 준비하고 기존 import·최소
앱 권한·계정 준비만 재사용한다. 기존 normal/failure/scratch의75회귀는 그대로 수행하며 그 이벤트를 전달용으로
필터하거나 ACK하지 않는다. business 선택이 normal/failure 등 기존 회귀 DB와 같으면 SQL 전에
거절하고, 초기 이벤트의 strict wire/metadata 검사도 claim 전에 고정 오류로 거절한다. 안전 보고서는
queue/business 단계를 분리한다. 별도 신규 회귀는 잘못된 DB 선택과 원래 부분 JSON의 거절/
정상 wire 보존을 확인한다. adapter·V1/V2·원문 저장 테스트·원본 UUID/ACK와 앞의 실제 PG/MySQL
전달 결과는 유지한다. 새 로컬 DB를 reset하지 않았다. 같은 상속 flag의 전체 check는 Python95·Java149/이관단위18/JPA단위3으로 통과했고, 수정 head의 fresh 필수 CI에서 실제 분리를 검증한다.

새 부하/성능 측정·운영 복구 SLA·검색 refresh 완료나 사용자 검색 품질 성과는 미측정이다.
최신 필수 CI/완료 Codex/미해결0·main 인수는 후속 게이트다. 공개 엔진 검색·PG 필수조건
fallback·전체 DB05–06/cutover는 미완료다. 전체20개 MVP12월31일·DB/React11월22일 목표는 유지한다.

README 네 사례와 비교한다. 이번은 기존 원자 outbox/전달 사례의 PostgreSQL 보존 증분이며
독립적으로 더 강한 원인·영향 근거가 없다면 네 본문·순서를 보존한다.
