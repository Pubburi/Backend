# 로컬 통합 실행

기본 Maven 산출물은 PostgreSQL18.6·JPA(Hibernate)·Flyway다.
`./mvnw verify/package`는 PG JAR, `./mvnw -f tools/mysql-baseline/pom.xml verify`는 기존 MySQL 비교 JAR을 만든다.
`package-pg`도 같은 기본 PG JAR을 고정한다. 기존 selector와 실제 DB를 자동 전환하지 않는다. 명시 PG 활성화 전 옵션을 생략한 통합 기동은 이관 전
MySQL 기준이며 기존 볼륨을 보존한다. 아래 `activate-pg` 후에는 같은 명령이 선택한 PG를 사용한다. 준비된 소유 PostgreSQL 복사본은 아래 명시 경로로 실행한다. PostgreSQL schema installer는 별도 폐기 검증 DB에서
[전환 계약](POSTGRESQL-MIGRATION.md)에 따라 명시 실행한다. schema 검증을 전체 API/cutover
완료로 간주하지 않는다. 기본 스택 전환과 복구 인수는 별도 게이트다.

현재 인증/회원·매장·상품/특성·주문·후기·찜/취향·기본 목록/본인 문구·B1 DEMO caller의49경로/63작업은
기본 [JPA 제공자](JPA-REHEARSAL.md)에서 실행할 수 있다.
copied PG/최소 runtime 역할·동적 loopback 포트에서만 실행하며 원본 MySQL을 바꾸지 않는다.
검증한 범위의 웹 연결은 가능하지만 실제 DB cutover·복구 인수 완료와 구분한다.

본인 B1은 [공급 계약](CONTENT-RECOMMENDATIONS.md)의 공식 fresh container-DEMO bundle을
운영자가 path/SHA로 고정할 때만 전송한다. 기본 빈 설정은 원본80 자동 fit 없이 UNAVAILABLE이며
기존 공개 B0/R06/상품 검색·기본 MySQL 기동은 그대로다. 실제 원천/권리 검증·fresh 발행은
Recommendation 정본 도구를 사용하고 Backend가 임의 catalog를 만들어 대신 승인하지 않는다.

공개 상품 검색의 명시 엔진 읽기는 [검색 계약](PRODUCT-SEARCH.md)의 세 환경변수로만 켠다.
기본 false는 DB 조회를 유지하며 별도 준비한 source-bound 물리 index와 현재 DB 결과를 대조한다.
원래 필수 조건/페이지는 장애에도 유지한다. 자동완성/재색인·기본 PostgreSQL 전환을 대신하지 않는다.

세 레포를 같은 상위 폴더에 `Backend`, `Frontend`, `Recommendation` 이름으로 둔다.
Java 17, Node.js 24, Python 3.12, uv 0.13.6, 실행 중인 Docker Compose가 필요하다.
macOS에서는 `JAVA_HOME`이 비어 있을 때 실행 스크립트가 설치된 JDK 17을 선택한다.
이미 다른 JDK를 지정했다면 `JAVA_HOME`과 `PATH`를 직접 Java 17로 맞춘다.
Node 24는 사용 중인 버전 관리 도구로 선택한다. 전역 셸 설정은 수정하지 않는다.
설치된 도구를 이 프로젝트에서만 선택하려면 ignored `.pubburi/runtime.env`에
`JAVA_HOME`, `PATH`, `UV_PYTHON` 등을 지정할 수 있다. 이 파일은 본인이 관리하는
셸 설정으로만 사용하며 공유·커밋하지 않는다. `init`은 이 파일을 덮어쓰지 않는다.

Backend 루트에서:

```bash
./scripts/local-stack.sh init
./scripts/local-stack.sh doctor
./scripts/local-stack.sh install
./scripts/local-stack.sh up
```

`init`은 무작위 DB 비밀번호를 가진 로컬 `.env`를 만들고 기존 파일은 보존한다.
기존 `.env`를 사용한다면 `.env.example`의 필수 DB 값을 모두 채워야 한다.
단순한 `KEY=value` 형식을 사용하며 셸 확장이나 여러 줄 값은 사용하지 않는다.
DB 이름·포트를 바꾸면 `PUBBURI_DB_URL`도 같은 값으로 맞춘다.
통합 도구는 이 로컬 DB와 일치하는 loopback JDBC URL만 허용하며 원격 DB 연결은 거부한다.

`install`은 Maven 검증·패키징, `npm ci`, `uv sync --locked`를 수행한다.
첫 설치에는 공개 의존성을 받는 인터넷 연결이 필요하다. AWS 계정·유료 AI 키는 필요하지 않다.
`up`은 전용 Compose 프로젝트 `pubburi-local`의 MySQL을 기동하고 세 서버를 실행한다.
기존 일반 Compose 프로젝트의 DB 볼륨을 이동하거나 삭제하지 않는다.
기존 볼륨의 스키마를 자동 전환하지 않는다. 새 상품 쓰기는 검색 outbox도 필요하므로 기존 DB는
백업/쓰기 중단 후 001–006의 적용 상태와 [007 outbox 전환](SEARCH-OUTBOX.md),
[008 delivery 전환](SEARCH-RELAY.md)을 순서대로 확인한다. relay는 별도 환경에서 명시 실행하며
기본 통합 실행이 Python relay나 OpenSearch 기동을 요구하지 않는다.
현재 data.sql은 빈 새 DB용이며 기존 데이터에 다시 실행하거나 reset으로 전환하지 않는다.
소스를 수정한 경우 개발 서버의 반영 방식을 확인하고 Java 변경은 재시작한다.

| 서비스 | 기본 주소 | 현재 검증 |
|---|---|---|
| 웹 | http://127.0.0.1:5173 | 앱 HTML, `/api` 프록시 |
| 백엔드 | http://127.0.0.1:9987 | 상품 페이지 API 계약, 정상 빈 목록 허용 |
| 추천 | http://127.0.0.1:8000 | `GET /health` |
| DB | 127.0.0.1:3306 | MySQL 8.4, 빈 전용 볼륨에서 `data.sql` 초기화 |

웹은 같은 출처의 `/api`와 세션 쿠키를 사용한다. 통합 실행은
`VITE_API_PROXY_TARGET`을 현재 백엔드 포트로 지정한다. 개별 웹 실행 시에는
Frontend의 `.env`에서 이 값을 지정할 수 있다. `VITE_` 값에 비밀키를 넣지 않는다.
포트는 Backend `.env`의 `PUBBURI_FRONTEND_PORT`, `PUBBURI_SERVER_PORT`,
`PUBBURI_RECOMMENDATION_PORT`, `PUBBURI_DB_PORT`로 바꿀 수 있다.
사용 중인 앱 포트가 있으면 시작을 거부하며 기존 프로세스를 종료하지 않는다.

## 확인·종료·초기화

```bash
./scripts/local-stack.sh status
# 자동 기동 → HTTP 검증 → 이번에 띄운 앱 프로세스 종료
./scripts/local-stack.sh smoke
```

`up` 터미널에서 Ctrl+C를 누르면 이번 실행이 만든 앱 프로세스만 종료한다.
`smoke`도 앱만 종료하며 DB와 데이터는 보존한다. DB까지 멈출 때는:

```bash
./scripts/local-stack.sh down
```

`up`·`down`·`reset-db`는 같은 잠금을 원자적으로 획득해 서로의 작업을 중단하지 않는다.
기동 확인은 수정된 로컬 DB의 정상 빈 목록도 허용한다. 초기 80개 상품 검증은
CI의 별도 폐기용 DB와 `scripts/smoke.sh`에서 수행한다.

로그와 실행 잠금은 `.pubburi/`에 보관하며 커밋하지 않는다. 갑작스러운 강제 종료로
`local-stack-lock`이 남으면 기존 터미널과 포트·프로세스를 먼저 확인한다.
살아 있는 작업을 죽이거나 잠금을 자동으로 빼앗지 않는다.

DB 초기화는 **전용 로컬 DB의 모든 변경을 삭제**한다. 앱을 먼저 종료하고 필요한
데이터를 백업한 뒤 명시적으로 실행한다. 일반 `down`은 볼륨을 삭제하지 않는다.

```bash
./scripts/local-stack.sh reset-db --confirm-delete-local-data
```

기본 추천 API는 로컬 추천 서버의 B0를 호출하며 미기동/장애에는 현재 DB의 기본 목록을 제공한다.
추천 서버는 B0/B1 계약을 제공하지만 통합 실행 성공은 추천 모델·회원 흐름 전체의
완성을 의미하지 않는다. [개발 계획](DELIVERY-PLAN.md)의 기능별 검증을 별도로 진행한다.

## 준비된 PostgreSQL의 명시 실행·패키징

DB06의 이 경로는 기존49경로/63작업 JPA 제공자를 소유한 로컬 폐기 복사본에서 실행한다.
DB 설치·source import·ID 예약·기본 DB 전환을 수행하지 않는다. 먼저 [복사/검수](SOURCE-IMPORT.md)와
[JPA 제공자](JPA-REHEARSAL.md)의 TLS/marker/receipt·최소 runtime 역할 준비를 마친다.
원본 DB나 운영 DB를 이 도구에 지정하지 않는다. 새 역할/권한 추가는 필요하지 않다.

```bash
./scripts/local-stack.sh package-pg --output-dir <Backend의 .pubburi 바로 아래 새 release 폴더>
./scripts/local-stack.sh doctor --provider postgresql --pg-config <비공개 JSON> --release-dir <release 폴더>
./scripts/local-stack.sh smoke --provider postgresql --pg-config <비공개 JSON> --release-dir <release 폴더>
./scripts/local-stack.sh up --provider postgresql --pg-config <비공개 JSON> --release-dir <release 폴더>
```

`package-pg`는 DB 없는 JPA Maven verify 뒤 source commit/현재 입력의 개별 SHA와
실제 실행 JAR SHA를 완료 manifest에 결속한다. 패키징하는 Java·resource·POM·wrapper 입력은 Git에 커밋된 깨끗한 상태여야 하고 신규 미추적
입력도 거절한다. 다른 launcher/문서 변경은 허용한다. 출력 폴더는 새 private 폴더여야 하며
기존 release를 덮어쓰지 않는다. 빌드 실패/중단·입력 변경에는 완료 manifest를 발행하지 않는다.
이 manifest는 로컬 소스/JAR 동일성 검증이며 서명된 배포나 실제 DB 인수 증명서는 아니다.

설정 파일은 소유자만 읽는 regular JSON(0600 또는0400, symlink 불가)이며 정확히
`{"schema_version":1,"environment":{...}}` 형식이다. 중복/미지 키·비문자열 값을 거절한다.
`environment`에는 다음을 모두 문자열로 지정한다. 비밀번호/실제 값은 커밋하거나 공유하지 않는다.

| 키 | 의미 |
|---|---|
| `PUBBURI_PG_URL`, `PUBBURI_PG_DB_NAME` | query/userinfo 없는 numeric loopback JDBC URL과 동일한 `*_ci` DB명 |
| `PUBBURI_PG_USER`, `PUBBURI_PG_PASSWORD` | 기존 최소 runtime 계정, admin/control 자격 금지 |
| `PUBBURI_PG_RUN_TOKEN`, `PUBBURI_PG_CA` | 해당 DB marker와 공개 TLS CA 파일 |
| `PUBBURI_PG_CONTAINER_ID`, `PUBBURI_PG_VALIDATION_LABEL` | Running Docker 컨테이너의 정확한 ID/소유 label·loopback 공개 포트 |
| `PUBBURI_JPA_SOURCE_ID`, `PUBBURI_JPA_REHEARSAL` | 검수 receipt의 canonical UUID, 명시 값 `1` |
| `PUBBURI_SERVER_PORT`, `PUBBURI_FRONTEND_PORT`, `PUBBURI_RECOMMENDATION_PORT` | 서로 다른 미점유 loopback 앱 포트 |

선택 키는 `PUBBURI_LOG_LEVEL`, `PUBBURI_RECOMMENDATION_BASE_URL`,
`PUBBURI_SEARCH_ENABLED`/`ENDPOINT`/`INDEX`, `PUBBURI_CONTENT_CATALOG_PATH`/`SHA256`, `PUBBURI_CONTENT_FAMILY_BROWSE_DEMO_ENABLED`다.
원격 base URL/엔진은 HTTP numeric loopback만 허용한다. catalog 생략은 원래 UNAVAILABLE이며
임의 bundle/기존80 자동 fit을 만들지 않는다. 공식 fresh container-DEMO 계약은 그대로다.
PG 모드는 Backend `.env`를 읽지 않고 상속된 PUBBURI/DB/control·JVM 주입 설정을 제거한다.
FE에는 앱 포트/`VITE_API_PROXY_TARGET`, Recommendation에는 앱 포트와 공통 도구 경로만 전달한다.
FE/추천 자식에 DB 자격을 전달하지 않는다. trusted toolchain의 runtime.env 사용은 위와 같다.

실행은 release/소유 CID를 검증하고 같은 로컬 작업 잠금·미점유 포트를 확보한 뒤 복사한 JAR를
Java17로 먼저 시작한다. 원래 TLS verify-full/SCRAM·PG18.6/UTF8·receipt/source·최소 권한·
Flyway validate/Hibernate validate·OSIV off 검증을 재사용한다. 준비 실패 시 FE/추천은 시작하지 않는다.
자기 JVM 기동 로그와 상품 API·OpenAPI49/63를 확인한 뒤 FE/추천 health와 `/api` 프록시를 검사한다.
`--backend-only`는 doctor/up/smoke/status에서 API release만 확인하는 CI/개별 실행 옵션이며
세 서버·세션/사용자 흐름 인수가 아니다. `status`도 endpoint 관측이지 프로세스 소유 증명은 아니다.

`smoke` 정상 종료·`up` Ctrl+C/SIGTERM·실패는 이번에 만든 앱 process group만 정리한다.
기동 상태는 터미널과 실제 API로 확인하고 private run 폴더의 로그/설정/JAR는 비공개로 보관한다.
DB/컨테이너/볼륨은 항상 보존하며 PG 모드의 init/install/down/reset-db는 명시 거절한다.
불확실한 프로세스 정리에는 기존 잠금을 남긴다. 강제 종료 뒤 잠금/프로세스를 확인하지 않고
지우거나 다른 실행을 인계받지 않는다. 이전 검증/제약은
[DB06 증가분 기록](https://github.com/Pubburi/Backend/blob/89adaa8e9a93cf2628703744f7febcb13dae38d8/docs/plans/2026-11-04-postgresql-local-release.md)으로 구분한다.

준비한 복사본을 이 checkout의 기본 로컬 실행 대상으로 명시 선택하려면 다음을 실행한다.

```bash
./scripts/local-stack.sh activate-pg --pg-config <비공개 runtime JSON> --release-dir <release 폴더> --cutover-config <비공개 초기 검수 JSON>
./scripts/local-stack.sh smoke
./scripts/local-stack.sh up
```

Backend만 개발할 때는 `./scripts/dev.sh`를 실행한다. 위 선택이 있으면 같은 검증과 작업 잠금으로
고정한 JPA release를 기동하고, 선택이 없으면 기존 `.env`·Maven MySQL 개발 실행을 사용한다.
PostgreSQL 선택이나 기동이 잘못되면 명령이 실패하며 `.env`를 읽지 않는다. 기존 MySQL을
명시 비교하려면 `./scripts/dev.sh --provider mysql`을 실행한다. 이 명령들은 DB 준비를 대신하지 않는다.

`activate-pg`는 같은 작업 잠금에서 기존 실제 JPA/API·세 서버 smoke를 실행하고 자신이 만든
프로세스를 모두 종료한 뒤에만 `.pubburi/local-provider.json` 하나를 원자적으로 발행한다.
설정·release manifest·JAR SHA와 경로를 고정하며 자격 원문을 선택 파일에 복제하지 않는다.
검증/정리/발행 실패는 기존 선택을 보존한다. 이후 provider를 생략한 명령은 같은 PG 경로를
사용한다. 선택/입력 변경·CID/marker 부적합·포트 점유·기동 실패는 거절하며 MySQL로 자동
복귀하지 않는다. `doctor`는 파일/소유 container 검사이고 실제 DB/marker/권한 검사는 앱 기동 때
실행한다. `--backend-only` 검증은 기존과 같이 전체 웹 인수를 대신하지 않는다.

첫 선택은 source/target writer·relay·DDL을 운영자가 정지하고 `--cutover-config`를 제공해야 한다.
이 파일은 mode0600의 소유 regular JSON이며 `{schema_version:1,environment:{...}}` 형식이다.
environment는 [기존 설정 예시](../tools/postgresql-migration/.env.example)의 source reader/소유 CID·label·
snapshot 설정과 PG 검수자/소유 CID·label·승인 import 설정을 담는다. 정확한 키는
`scripts/postgresql_import.py`의 `CUTOVER_REQUIRED`이며 MUTATING·FORMAT_VERSION·앱/관리자 설정은
넣지 않는다. PG URL/DB/CID/label/marker/CA와 source UUID는 runtime 설정에 일치해야 한다.
원본 자격은 별도 검수 자식에만 전달한다. 먼저 이관 모듈을 빌드하고, 기동 전과 소유 앱 종료 뒤
게시 직전에 [verify-cutover](SOURCE-IMPORT.md)를 실행해 현재 source/target 전행·7고수위와 승인
파일의 일치를 확인한다. 설정 변경·drift·검수 실패는 selector를 쓰지 않는다. READ ONLY는 writer
차단이 아니므로 실제 쓰기 정지 창을 유지해야 하며 concurrent writer 무손실 전환을 보장하지 않는다.
PG 업무 쓰기 이후에는 초기 일치를 다시 주장하지 않고 아래의 PG 백업/복구·promote를 사용한다.

다시 `activate-pg`를 실행할 때는 선택한 DB의 CID·label·DB명·marker·source UUID가 같아야 한다.
같은 PG의 release 갱신에는 초기 검수 파일을 주지 않는다.
같은 source UUID의 다른 복구본도 활성화 전에 거절한다. 같은 대상의 새 release, 자격, 포트는
새 비공개 설정으로 검증할 수 있다. 기존 선택에 결속된 설정은 갱신을 마칠 때까지 그대로 둔다.
복구본을 기본 대상으로 승계할 때는 아래의 명시 `promote` 검증을 사용한다.

활성 상태의 `init/install/down/reset-db`도 기존 PG 거절을 유지한다. `--provider mysql`은
이관 전 기준을 의도적으로 별도 실행하는 명시 옵션이며 전환 후 쓰기를 MySQL로 되돌리는
복구가 아니다. 원본 MySQL·스키마·역할·receipt·UUID/ACK를 선택 명령으로 바꾸지 않는다.
이 선택은 로컬 launcher의 기본 대상만 바꾸며 전체 DB06·최종 cutover 완료가 아니다.
CI는 별도 private STATE/선택 파일을 사용하여 실제 사용자 선택을 덮어쓰지 않는다.

## 쓰기 정지 PostgreSQL 복사본의 백업·새 대상 복구

import 뒤 생긴 주문·후기·비밀번호 변경까지 현재 PG 상태에서 백업한다. MySQL 원본으로 되돌리거나
기본 스택을 전환하지 않는다. 기존 사용자 DB/볼륨은 지정하지 않는다. 같은 pinned PG18.6의 소유
CID·label·동적 loopback·TLS/marker를 사용하며 source/target은 서로 다른 `*_ci` DB다.
대상은 **새 marker만 있고 pubburi schema가 없는 DB**여야 한다.

운영자가 앱·relay·관리 쓰기를 중단하고 client 연결을 종료한 뒤 해당 DB에 CONNECT가 있는
다른 non-super 로그인 역할을 별도 control 권한으로 `NOLOGIN`으로 준비한다. 읽기 전용 로그인도
중단해야 하는 보수적인 프로파일이며, 비상속 SET ROLE 경로를 따로 분류해 허용하지 않는다. 도구는 이를 확인할 뿐 자동 권한 회수·세션 종료·
DB/역할 생성을 하지 않는다. backup/restore는 별도 non-superuser schema 소유자이며 최소 runtime
자격을 사용하지 않는다. trusted 관리자나 같은 control 자격의 외부 개입을 막는 fencing은 아니다.
다른 역할의 통계 열이 가려져도 공개 `datid`·`pid`로 모든 다른 backend를 보수적으로 거절한다.
`NOLOGIN` 뒤에도 남아 있는 기존 세션을 포함하며 통계 조회 권한이나 역할 멤버십을 추가하지 않는다.

기존 relay의 [잠금 의존성](../tools/search-relay/requirements.lock)을 설치한 Python 환경을 쓴다.
source/target별 설정은 owner-only regular JSON이며 정확히 `schema_version:1`, `writers_stopped:true`,
`environment`를 둔다. environment에는 `PUBBURI_PG_` 접두사의 `URL`, `DB_NAME`, `USER`, `PASSWORD`,
`RUN_TOKEN`, `CA`, `CONTAINER_ID`, `VALIDATION_LABEL` **8개 문자열 키만** 지정한다. 중복/추가 키·
symlink를 거절한다. 실제 설정·archive·manifest에는 인증/업무 정보가 있으므로 공개하거나 Git/CI
artifact에 올리지 않는다. 결과 폴더는 Backend ignored `.pubburi` 바로 아래 새 private 폴더다.

```bash
.venv-relay/bin/python scripts/postgresql_recovery.py backup --config <source private JSON> --output-dir <새 백업 폴더>
.venv-relay/bin/python scripts/postgresql_recovery.py restore --config <target private JSON> --archive-dir <백업 폴더> --expected-sha256 <검수한 manifest SHA256> --output-dir <새 복구 시도 폴더>
```

백업은 V3의 17업무표·V4의 18업무표·V5의 19업무표·V6의 22업무표와 Flyway/원본 receipt를 SHARE 잠금·exported snapshot에 결속한다. 원문 JSON을
포함한 binary-cell digest·행 수/타입/PK/FK/index/콜레이션과7개 sequence의 정의·현재값·is_called를
보존한다. 미지 relation·사용자 함수/trigger/rule·extension과 미허용 archive TOC는 거절한다.
서버/클라이언트는18.6이며 archive는128MiB 이하다. SHA는 파일 동일성 근거이며 임의 SQL의 안전
승인이 아니며 TOC 검사는 SQL sandbox나 서명이 아니다. 신뢰하는 소유 source/control만 사용하고
외부 dump를 실행하지 않는다. 원본 archive를 수정하거나 결과 폴더를 재사용해 덮어쓰지 않는다.
스키마는 V3/V4/V5/V6의 각각 고정된 catalog와 대조한다. PG18 dump/reparse가 바꾸는 enum CHECK의 캐스트는
정확한 이름·원본/roundtrip 정의 쌍만 의미 동등하게 비교하며 그 외 차이는 거절한다. 스키마 SQL
원문 동일성과 이 표현의 동등성을 구분하고 데이터/원문 JSON은 별도 binary-cell로 대조한다.

`pg_restore --single-transaction --exit-on-error --no-owner --no-acl`은 자체 COMMIT으로 끝난다.
**복구 TX 원자성**과 **그 뒤 별도 연결의 exact 검수**를 구분한다. 후행 불일치나 timeout/COMMIT ACK
불확실에는 `attempt.json`만 남고 `completed.json`을 발행하지 않는다. 대상은 이미 commit되었을 수
있으므로 격리해 조사한다. 자동 DROP·clean/create·재시도·receipt/UUID 재생성은 없다. 원본과
archive는 보존한다. 완료 후 기존 최소 runtime GRANT를 별도 적용하고 `NOLOGIN`을 해제한 뒤
위 JPA release의 doctor/smoke·업무 인수를 수행한다. 새 대상 marker는 유지하며 source UUID·epoch·
claim/ACK·Flyway/receipt와 소비된 ID를 초기화하지 않는다. V3/V4/V5 복구 완료와 현재 V6 runtime의
기동 준비는 별개다. 새 runtime 전에 쓰기 중단 상태에서 필요한 Flyway V4/V5/V6를 명시 적용하고
[notebook 최소 grant](NOTEBOOK.md)와 기존 피드백 grant를 추가한다. source/target의 exact 상태가
같아야 하므로 구버전 백업과 그 대상을 V6로 바꾼 조합은 승격을 거절한다. 새 V6 승계는 중단한
소유 source를 먼저 명시 이행한 뒤 새 V6 백업·복구·검수 결과를 사용한다.

### 검증한 복구본을 기본 로컬 대상으로 승계

`restore`가 남긴 `completed.json`과 `promotion.json`을 사용한다. 후자는 승인 manifest와 복구 대상의
CID·label·DB명·marker를 결속한다. 운영자가 두 SHA256을 확인하고 별도 승계를 실행한다.

```bash
.venv-relay/bin/python scripts/postgresql_recovery.py promote \
  --config <선택된 원본 control JSON> --target-config <복구 대상 control JSON> \
  --archive-dir <백업 폴더> --expected-sha256 <manifest SHA256> \
  --restore-dir <복구 시도 폴더> --expected-restore-sha256 <promotion.json SHA256> \
  --pg-config <복구 대상 runtime JSON> --release-dir <release 폴더>
```

원본은 control 이외 모든 non-superuser 역할의 유효 `CONNECT`를 제거한다. `NOLOGIN`인 역할도 포함한다.
대상은 runtime에 최소 권한과 `CONNECT`를 준비한 상태에서 `NOLOGIN`으로 유지한다. 양쪽 기존 세션과
prepared transaction을 모두 정리한다. launcher 잠금과 두 DB의 업무표 SHARE 잠금 아래, 현재 선택 원본과
복구 대상의 스키마·원문 binary-cell·receipt/UUID·7개 sequence가 승인 백업과 모두 일치해야 선택을
발행한다. 백업 뒤 원본에 커밋이 추가되면 새 백업과 새 복구가 필요하다.

발행 뒤 원본의 `CONNECT` 차단을 유지하고 대상 runtime만 열어 `up`과 업무 인수를 진행한다. 이 명령은
권한이나 DB 데이터를 바꾸지 않는다. control 자격과 superuser를 독점한 운영자가 검증부터 대상 개방까지
외부 쓰기·권한 변경을 중단한다. 승계 범위는 소유 복사본의 로컬 launcher 선택이다.

이는 정지 시점의 논리 백업/새 PG 복구이며 PITR·지속/증분 백업·장애 직전 RPO0·PG→MySQL
역이관을 제공하지 않는다. 기본 업무 runtime 전환·전체 DB06 인수는 후속 게이트다. 근거는 PostgreSQL 공식
[pg_dump](https://www.postgresql.org/docs/18/app-pgdump.html)·[pg_restore](https://www.postgresql.org/docs/18/app-pgrestore.html)를 따른다.
