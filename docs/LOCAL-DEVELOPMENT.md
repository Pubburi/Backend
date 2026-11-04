# 로컬 통합 실행

확정 목표 DB/영속 계층은 PostgreSQL18.6·JPA(Hibernate)·Flyway다. 옵션을 생략한 통합 기동은 이관 전
MySQL 기준이며 기존 볼륨을 보존한다. 준비된 소유 PostgreSQL 복사본은 아래 명시 경로로 실행한다. PostgreSQL schema installer는 별도 폐기 검증 DB에서
[전환 계약](POSTGRESQL-MIGRATION.md)에 따라 명시 실행한다. schema 검증을 전체 API/cutover
완료로 간주하지 않는다. 기본 스택 전환과 복구 인수는 별도 게이트다.

현재 인증/회원·매장·상품/특성·주문·후기·찜/취향·기본 목록/본인 문구·B1 DEMO caller의37경로/44작업은
별도 [명시 JPA 제공자](JPA-REHEARSAL.md)에서 실행할 수 있다.
copied PG/최소 runtime 역할·동적 loopback 포트에서만 실행하며 아래 기본 스택은 바꾸지 않는다.
검증한 범위의 웹 연결은 가능하지만 기본 MySQL runtime/전체 cutover·복구 인수 완료와 구분한다.

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

DB06의 이 경로는 기존37경로/44작업 JPA 제공자를 소유한 로컬 폐기 복사본에서 실행한다.
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
`PUBBURI_SEARCH_ENABLED`/`ENDPOINT`/`INDEX`, `PUBBURI_CONTENT_CATALOG_PATH`/`SHA256`다.
원격 base URL/엔진은 HTTP numeric loopback만 허용한다. catalog 생략은 원래 UNAVAILABLE이며
임의 bundle/기존80 자동 fit을 만들지 않는다. 공식 fresh container-DEMO 계약은 그대로다.
PG 모드는 Backend `.env`를 읽지 않고 상속된 PUBBURI/DB/control·JVM 주입 설정을 제거한다.
FE에는 앱 포트/`VITE_API_PROXY_TARGET`, Recommendation에는 앱 포트와 공통 도구 경로만 전달한다.
FE/추천 자식에 DB 자격을 전달하지 않는다. trusted toolchain의 runtime.env 사용은 위와 같다.

실행은 release/소유 CID를 검증하고 같은 로컬 작업 잠금·미점유 포트를 확보한 뒤 복사한 JAR를
Java17로 먼저 시작한다. 원래 TLS verify-full/SCRAM·PG18.6/UTF8·receipt/source·최소 권한·
Flyway validate/Hibernate validate·OSIV off 검증을 재사용한다. 준비 실패 시 FE/추천은 시작하지 않는다.
자기 JVM 기동 로그와 상품 API·OpenAPI37/44를 확인한 뒤 FE/추천 health와 `/api` 프록시를 검사한다.
`--backend-only`는 doctor/up/smoke/status에서 API release만 확인하는 CI/개별 실행 옵션이며
세 서버·세션/사용자 흐름 인수가 아니다. `status`도 endpoint 관측이지 프로세스 소유 증명은 아니다.

`smoke` 정상 종료·`up` Ctrl+C/SIGTERM·실패는 이번에 만든 앱 process group만 정리한다.
private run 폴더에 완료 readiness와 종료 상태·PID 기록을 남기며 로그/설정/JAR는 비공개로 보관한다.
DB/컨테이너/볼륨은 항상 보존하며 PG 모드의 init/install/down/reset-db는 명시 거절한다.
불확실한 프로세스 정리에는 기존 잠금을 남긴다. 강제 종료 뒤 잠금/프로세스를 확인하지 않고
지우거나 다른 실행을 인계받지 않는다. 실제 release·소유/중단·세 서버 검증과 남은 cutover/rollback은
[DB06 증가분 기록](plans/2026-11-04-postgresql-local-release.md)으로 구분한다.
