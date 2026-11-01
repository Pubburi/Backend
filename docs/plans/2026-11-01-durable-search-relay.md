# S02 durable claim/ack/retry relay 증가분

기준 main e7a957b, branch codex/durable-search-relay. 기존 원자 생산 위에 전송 상태와 명시
watch/once relay를 구현한다. 정본은 [relay 계약](../SEARCH-RELAY.md)이다. 공개 검색·재색인
cutover·retention/reconciliation과 S02 전체 완료는 이번 범위가 아니다.

## 결정과 구현

- 기존 strict Python Engine의 schema/버전·409 정본·tombstone/기한/바이트 검사를 재사용하고,
  Java에 별도 엔진 HTTP 구현을 중복하지 않았다. DB 연결은 Backend 전용 PyMySQL1.2.3을
  해시 고정했다. Java 기본 기동과 Recommendation 환경에는 의존성을 더하지 않는다.
- delivery 별도 표·DB시간 lease/token fencing·원격IO 전 claim commit·조건부 ack/retry를 둔다.
  낮은 event ID가 늦게 commit해도 다시 발견하며 MAX(event_id) watermark는 쓰지 않는다.
- source UUID를 index metadata에 명시하고 cluster/index UUID binding을 DB에 둔다. 전후 관찰과
  ack TX의 target 잠금은 교체를 탐지/거절하며 분산원자성/자동복구를 주장하지 않는다.
- 429/5xx는 relay 전용 header guard에서 transient, 다른4xx는 permanent로 구분한다. HTML 본문이
  영구오류를 JSON파싱 transient로 숨기지 않는다. 기존 관리 CLI의 기본 오류/refresh 계약은 보존한다.
- watch 최초 기동의 엔진/정본 검증은 fail-fast이며 자동 재시도하지 않는다. 초기 검증을 통과한
  루프 안에서만 지정 transient를 재전달한다. 기동 transport 실패의 nonzero/루프 미진입도 단위로 검증한다.
- 후보 대안인 원격IO 중 DB row 잠금·전역 eventID cursor·실패 건너뛰기는 각각 경합/commit누락/
  미전달 상태 유실 때문에 선택하지 않았다. per-row 상태/추가조회·중복전송 비용을 수용한다.

## 의존성·인증에서 확인한 실제 경계

공식 [PyMySQL changelog](https://github.com/PyMySQL/PyMySQL/blob/main/CHANGELOG.md)의1.2.1 보안수정과
[PyPI1.2.3](https://pypi.org/project/PyMySQL/1.2.3/) hash를 확인했다. 최초 `[rsa]` 제안은 새 계정의
첫 비TLS caching_sha2 인증에서 실제 AttributeError로 실패했다. warm cache 접속 성공만으로
인증 완료를 추정하지 않았다. 공식 [v1.2.3 소스](https://github.com/PyMySQL/PyMySQL/blob/v1.2.3/pymysql/_auth.py)의 full RSA 경로 terminal packet 반환 누락을
확인했고 vendor monkeypatch/구보안버전 퇴행 대신 명시 CA 검증 TLS를 필수로 선택했다.

RSA 전이의존성을 제거한1개 lock으로 새 계정 cold secure-auth1회/TLS1.3 cipher와 CA chain
검증이 성공했다. 다른 CA 거절과 소유 non-TLS greeting에서 인증 바이트 전 오류코드2026 거절도 확인했다.
첫 non-TLS 검사에서 실제 오류 문구를 잘못 가정한 검증 실패는 실제2026/SSL-required로 바로잡았다.
hostname 검증 off의 이유/잔여 peer 구분 한계·기본 Java와 분리한 opt-in 범위를 계약에 남겼다.

## 검증과 한계

Java17 Maven140개/패키징과 초기 Python61개가 통과했고, HTTP상태 분류·startup fail-fast를 포함한
최종 Python3.12.14의63개도 통과했다. 새 jar는
`dcb73c35b4dbf148b05775992408b8d930977e84022fbabc878e68491ed22b81`로 고정했다.
실제 최소 DB계정의 새 jar HTTP14범주가 통과했다. 008은 기존15표(guard 포함)의 UTF-8 행/개수와
기존 이벤트80개를 보존하고 PENDING80개를 만들었으며 반복 전환은 거절/전체불변이었다.

첫 실제 relay 검증은 검증 SELECT의 MySQL 예약어 alias delayed 때문에1064로 실패했다.
그전 낮은ID 지연commit·두 worker의4건은 DELIVERED였고 제어 TCP거절은 PENDING/backoff였다.
이는 준비 SQL 오류이며 제품 동기화 위반/성능 개선율에 합산하지 않는다. 원상태를 보존한 뒤
marker·5행/상품범위를 확인하고 소유 queue-only4표만 재준비했다. 원 application DB는 초기화하지 않았다.

두 번째 실행에서 queue의12개 authored 이벤트는 모두 DELIVERED였고 400/429/503 분류도 통과했다.
이어 실제 업무 소비 fixture의 index 이름에 허용하지 않는 추가 하이픈이 있어 생성 전 거절됐다.
관리 대상 허용 규칙은 바꾸지 않고 fixture 이름만 수정했다. application DB의 target binding과
미전달 행은 그 시점까지 손대지 않았다. queue 재준비의 DROP은 자동 승인 검토에서 거절되어
실행하지 않았으며, 기존12행을 보존하고 조정자가 별도 빈 폐기 schema를 예약하는 대안으로 진행했다.

최종 MySQL8.4·OpenSearch/nori3.9.0·Python3.12.14의 전체 engine lifecycle9범주가 통과했다.
동일 불변 jar의 실제 업무API를 사용하며 HTTP mock/외부 서비스/실사용자 입력은 없다.

| 고정한 조건 | 실제 확인 결과 |
|---|---|
| 기존80 backfill + 실제 업무API 이벤트의 같은TX 예약 | 총114 outbox/114 delivery, 예약 없는 이벤트0 |
| 별도 watch 프로세스의 업무 소비 | 114 DELIVERED, 실제 API의 동일 가상 상품5버전도 모두 전달, 최종 삭제 표식 일치 |
| 서로 다른 상품의 낮은ID 미commit·높은ID 선commit | 높은ID 전달 후 낮은ID commit/전달, 누락 없음 |
| 두 worker를 원격IO gate에서 대기 | 서로 다른 두 claim, 제3연결의 같은 행 NOWAIT 잠금 성공 후 두 전달 완료 |
| 실제 HTTP400/429/503의 HTML 본문 | 모두 미ACK/PENDING·고정 오류, 400은 영구거절·429/503만 retry대상; 제어 원인 제거 후 전달 |
| 실제 TCP거절·DB backoff, 높은버전 삭제 선전달 | 과거갱신은 stale_ignored, 삭제 표식 유지 |
| DB lease 만료/reclaim | 이전token ACK/retry 거절, 새token 전달 완료 |
| 엔진쓰기 뒤 실제 worker exit23, DB ACK 전 종료 | IN_FLIGHT 유지, 만료를 제어한 새 worker는 canonical duplicate로 ACK |
| DB binding 변경·다른 source | 이전 ACK/다른 source 채택 거절 |
| 같은volume 엔진 재시작 | 기존 UUID binding·idle·업무/가상 삭제 표식 유지 |
| 전송 중 소유 physical index 삭제/재생성 | UUID 변경으로 미ACK/PENDING, 다음 실행도 기존 target 불일치로 거절 |

별도 authored queue는 총13행 중 앞12행 DELIVERED, 마지막 색인교체 실험1행 PENDING/
delivered_at NULL을 확인했다. 이 미전달은 안전 거절 결과이며 성공으로 바꾸지 않았다.
expiry/available_at은 폐기 DB에서 제어했고 실제 운영 부하·자동 복구 SLA·성능 측정이 아니다.
ACK는 refresh=false의 엔진 조건부 쓰기 수락이지 검색 refresh 완료가 아니다.
소유 JAR/watch/crash worker는 종료했고 소유 검색 container/volume은0개였다. MySQL은 조정자
소유로 유지했다. 초기 실패와 최종 결과는 별도 ignored 근거로 보존하며 공개 artifact에는
입력·receipt·개인 경로·자격·원문 로그를 포함하지 않는다.

재현은 `./scripts/check.sh` 후 전용 폐기 MySQL의 schema/CID/독립 marker·CA를 설정하고
`./scripts/smoke.sh`를 실행한다. 새 빈 queue schema와 그 marker를 별도로 준비한 환경에서
`PUBBURI_RELAY_SMOKE_DB_NAME=pubburi_relay_ci .venv-relay/bin/python scripts/smoke_search.py`를
실행한다. 기존 queue가 비어 있지 않으면 자동 채택/초기화하지 않고 거절한다. 설치/기동은
[relay 실행 계약](../SEARCH-RELAY.md), CI의 정확한 준비 순서는 workflow를 따른다.

## README 비교·후속

기존 첫 사례 BE-CS05의 업무/검색 경계를 깊게 하는 변경이다. 별도 다섯 번째로 늘리지 않고 실제
근거를 확보한 뒤 이 사례의 결과/한계·현재 코드를 갱신한다. 나머지 세 사례와 순서는 유지한다.
전체미완료: 자동 기동/운영배포, retention/reconciliation·source복제 복구·색인 유실/새 세대 cutover,
공개 검색과 품질/성능 인수. 최신 head CI/Codex·병합/main CI와 브랜치 삭제는 조정자 게이트다.
