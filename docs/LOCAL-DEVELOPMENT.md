# 로컬 통합 실행

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

추천 서버는 현재 헬스 체크만 제공한다. 통합 실행 성공은 추천 모델·회원 흐름 전체의
완성을 의미하지 않는다. [개발 계획](DELIVERY-PLAN.md)의 기능별 검증을 별도로 진행한다.
