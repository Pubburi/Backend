# Backend 테스트와 CI

`./scripts/check.sh`는 저장소 비밀/생성물 검사, Python 도구 회귀와 Java17의 기본 API·
PostgreSQL migration·JPA 모듈 검증/패키징을 실행한다. DB 없는 통합 skip은 실제 통과가 아니다.
문서만 바꿀 때 무관한 새 DB/성능 검사를 추가하지 않는다. 기능별 계약은 각 문서, 회귀의 상세
조건은 [Java 테스트](../src/test/java/)와 [JPA 테스트](../tools/jpa-rehearsal/src/test/java/)를 따른다.

## 기본 검사와 필수 CI

```bash
./scripts/check.sh
# 실행 중인 기본 API의 읽기 전용 HTTP 확인; 계정/주문을 만들지 않는다.
./scripts/smoke.sh
```

PR·main·수동 실행의 필수 `backend-ci`는 다음을 유지한다.

| 범위 | 실행·검증 |
|---|---|
| 기본 MySQL8.4 | 새 폐기 schema의 data.sql·실제 JAR HTTP/API·세션/권한·업무 쓰기/롤백·OpenAPI/Swagger |
| PG18.6 schema | `PUBBURI_PG_INTEGRATION=1`: 별도3DB의 새 설치/no-op·checksum·실패DDL 복구·타입/ID/인덱스 |
| source/import | 별도 TLS source의 읽기 전용 snapshot·승인 파일 검증과 import4DB의 v1/16표·태그 포함 v2/17표 전체 typed-cell/PK·7개 고수위·수령증·실패 rollback |
| JPA | `PUBBURI_JPA_INTEGRATION=1`: normal/failure·빈 카탈로그 scratch의 실제 Repository/HTTP·RR/잠금·수명/실패와 packaged 재시작 |
| PG local release | `jpa_rehearsal_ci.local_release`: runtime 키만 private 설정에 복사해 실제 package-pg·backend-only37경로/44작업 기동/종료·격리 STATE에서 명시 PG 활성화와 선택된 기본 재기동 |
| 검색/relay | 실제 CLI·현재 업무 producer·두 worker·lease/token·DB 시각·중복/삭제·엔진 재시작/물리 교체와 같은 필수조건의 공개 DB fallback |

`PUBBURI_PG_RELAY_CI=1`은 실제 source/JPA 준비와 search/relay 통합 step에만 둔다.
단위 검사에 live-integration flag를 주입하지 않는다. 기존 검사나 실패 조건을 skip/continue-on-error로
대체하지 않는다. 정확한 서비스/의존성·실행 순서는 [필수 workflow](../.github/workflows/ci.yml)가 정본이다.

실제 Recommendation/FastAPI·세 서버 웹 연결은 위 fixture 검사와 별도 소유 자원에서 확인한다.
Frontend가 없는 Backend CI의 backend-only 성공을 전체 웹/기능 인수로 세지 않는다.
MVP·병합/리뷰 기준은 [개발 기준](DELIVERY-PLAN.md)과 [작업 규칙](../AGENTS.md)을 따른다.

## MySQL 변이 검사 안전 조건

`scripts/smoke.sh`의 변이 경로는 다음을 모두 확인한 뒤 새 테스트 계정·상품·주문만 사용한다.

- `PUBBURI_SMOKE_MUTATING=1`과 새 run token을 명시한다.
- loopback JDBC URL·`*_ci` schema 이름이 일치해야 한다.
- 지정 CID의 공개 MySQL 포트가 URL과 일치해야 한다.
- 같은 DB의 `pubburi_ci_smoke_guard(token)`이 run token과 일치해야 한다.

초기 data.sql 상품과 missing ID 전제도 검사한다. SQL 변이 전에도 CID/DB/marker를 재확인하고,
자체 계정 namespace와 정확한 실패 trigger 본문/대상을 검증한다. binlog 환경의 고정 실패 DDL에만
`PUBBURI_DB_ROOT_PASSWORD`를 별도 사용할 수 있으며 Java 앱에는 전달하지 않는다. 누락은
DDL 전에 거절하고 SUPER/글로벌 설정 확대로 우회하지 않는다. 자체 행·임시 DDL은 finally 정리하고
수정한 원본 값은 복원한다. 불변 event·전달 기록을 테스트 정리 목적으로 지우지 않는다.

업무 HTTP는 ApiResponse/미디어·strict 입력·현재 권한/epoch·삭제/재가입·비밀번호 회수,
주문 중간 실패/가격·삭제 snapshot, 특성/찜/취향의 전체 교체·null/[]/0·부모 cascade를 확인한다.
상품 필터/offset·R06/B1 wire와 DB 오류/UNAVAILABLE는 각 공급 계약에 맞춰 검사한다.

## PostgreSQL과 source 보존

기존 DB/컨테이너를 채택하거나 원본을 reset하지 않는다. [source 추출](SOURCE-SNAPSHOT.md)과
[승인 import](SOURCE-IMPORT.md)는 새 labelled tmpfs·동적 loopback·TLS CA/SAN·최소 역할을 사용한다.
전행/고수위·UUID/epoch·금액/원문 JSON을 대조하고 충돌/드리프트·중복 실행·부분 실패를 거절한다.
commit ACK 미확인/close 실패의 단위 주입은 실제 네트워크 ACK 유실이나 서버 crash 실험과 구분한다.

JPA는 기존 [기동 guard/최소 권한](JPA-REHEARSAL.md)을 사용한다. normal/failure 원본 상품은
보존하고 기본 목록 수명용 scratch만 승인 복사/검수 뒤 원래 관리자 API로 비운다. 실패 DDL과
일시 읽기 권한 회수는 별도 control 역할·자체 fixture에만 적용하고 finally 복원한다. runtime에
DDL/소유권을 주지 않는다. `PUBBURI_JPA_EXPECTED_COMMENT_NEXT`는 snapshot의 sourceNext에서 받는다.

`scripts/jpa_smoke.py`와 [local-stack](LOCAL-DEVELOPMENT.md)은 같은 JAR/OpenAPI 검증을 사용한다.
자기 JVM 기동/PID를 확인하고 실제37/44·업무·재시작/재로그인과 소유 process group 정리를 검사한다.
기존 listener를 테스트하거나 종료하지 않으며 PG의 init/down/reset은 제공하지 않는다.
새 release의 입력 변경/미완료 manifest·비밀 설정 권한·상속 자격·TIME_WAIT/실제 점유를 별도로 검사한다.

같은 source/JPA lifecycle의 `postgresql_recovery_ci`는 실제 HTTP의 import 후 주문·가격 변경·
태그 후기·찜·비밀번호 회전과 별도 authored claim fixture/rollback으로 소비한 ID를 만든다.
다른 역할의 기존 세션(NOLOGIN 후 통계 열 가림)·비상속 SET/CONNECT 전용 LOGIN·추가 함수·
틀린 artifact pin을 거절한 뒤 새 marker-only PG에 복구하고
17업무표+2control표 binary-cell/스키마·7sequence·UUID/원문/receipt 동일성을 대조한다. JPA 재기동의
기존 주문 금액/후기·새 암호/찜과 소비 ID 비재사용을 확인하며 그 뒤 새 쓰기와 frozen 동일 상태를
구분한다. COMMIT 불확실/후행 검수 실패의 단위 주입은 실제 서버 crash나 네트워크 ACK 유실을
재현한 결과가 아니다. 기존 MySQL·PG·JPA 필수 job 안에서 실행하며 별도 파이프라인을 만들지 않는다.

## 검색·relay와 종료

[Relay](SEARCH-RELAY.md)의 PG business는 normal/failure와 분리한 승인 복사본이고 queue는 새 빈 DB다.
원문 저장 경계 fixture를 실제 전송 입력으로 재사용하지 않는다. 최소 runtime/control 권한을
분리하고 strict wire·원문 JSON/UUID·토큰 fencing·remote IO 전 commit·SKIP LOCKED를 유지한다.
기존 source/DELIVERED ACK와 target binding을 보존하며 부적합 event를 필터/수정/삭제하거나
가짜 ACK/reset으로 복구하지 않는다. MySQL lifecycle도 같은 필수 검사에 남긴다.

[공개 검색](PRODUCT-SEARCH.md)은 relay/restart/물리 교체 검증 뒤 실제 현재/지연/변경/삭제를 만들어
DB-only/엔진/미기동 응답 전체를 비교한다. 원래 업무행·version/event·기존 ACK를 따로 보존한다.
새 fixture 관리자 로그인의 hash CAS만 정확한 ID/hash 열에 허용하며 epoch/역할·타계정은 보호한다.

HTTP 검증은 connect/write/헤더/본문의 절대 기한과 본문 상한을 유지한다. 느린 trickle·짧은 EOF·
chunked/redirect·partial receipt, 정상/오류/SIGTERM/timeout의 소유 자식·socket 정리를 검사한다.
자기 engine/CID/label·volume만 정리하며 cleanup 실패를 성공으로 숨기지 않는다. 실험의 lease 대기·
파서 소비량·CI 시간·테스트 수를 서비스 성능/운영 SLA로 해석하지 않는다.

본인 B1 family opt-in은 기본 거절/명시 허용·정확한 scope/evidence/origin 조합·혼합 scope와
알려진 용량/설명/태그 거절·핀/만료·기존 container 호환을 확인한다. Spring 설정과 실제 loader를
연결하고, 실제 JPA는 현재 unknown 필드·기존4필드/새 scope·원격 뒤 변경409를 기존 수명 회귀에 더한다.
공식 fresh bundle/실제 Recommendation·웹 인수는 이 consumer fixture와 구분한다.
본인 활동 opt-in은 현재 epoch·승인 available·최신 후기만 사용하고, null/[]과 hard 조건·
고정 배치 문장 수·원격 뒤 활동 변경409 및 실제 반환 주종만 노출하는 경계를 검사한다.

## 결과물과 개인정보

원본 snapshot/원문 SQL·통합 XML/서버 로그·CA private key·.env·계정 hash는 업로드하지 않는다.
CI artifact는 workflow가 명시한 안전 요약과 허용된 XML만7일 보존한다. 개인값이 포함될 수 있는
source/import/JPA 상세 보고서는 제외한다. 공개 근거에 로컬 private 경로나 비공개 기획 주소를 넣지 않는다.
