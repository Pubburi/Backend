# DB01·DB02 PostgreSQL schema 증가분

기준 main53c15c0, 작업 codex/postgresql-schema-foundation. 사용자 확정 목표는 PostgreSQL18.6·
JPA(Hibernate)·Flyway Community와 relay Psycopg3다. [전환 ADR/계약](../POSTGRESQL-MIGRATION.md)을
정본으로 두고 기존 MySQL/API·과거 완료 근거는 baseline으로 보존한다.

이번 완성 단위는 실제16업무표 Flyway V1·guarded installer/validate·원본 next identity 예약이다.
비사용 entity scaffold나 부분 API profile은 추가하지 않는다. 전체 source 복사/DB03 JPA API·
DB04 업무 재인수·DB05 relay 전환·DB06 cutover 전체 완료는 아니다.

## 선택과 경계

- 독립 Maven tool로 Java17 Flyway13.12.0/PG plugin·JDBC42.7.14를 고정한다. root API POM/jar에는
  추가하지 않아 이관 전 HTTP를 깨뜨리는 반쪽 구성/자동 migration을 피한다. 공식 release·
  PostgreSQL18 지원과 JDBC 보안 수정을 확인했다. 기존 BOM Hibernate6.6.15.Final/JDBC42.7.5
  해석값과 도구의 의도된 보안 override를 구분한다.
- 16표의 FK/원금액/UUID/known flags/REAL·numeric/timestamptz(6)·json 원문/명시 collation을
  정의한다. 새로운 source UUID/epoch/ACK를 생성하지 않아 실제 이관 정본을 덮지 않는다.
- 소유 CID/label/포트·별도 marker·실제 버전/UTF8·CA/SAN TLS·비superuser guard를 통과해야
  migrate/validate/reserve 명령을 실행한다. clean/repair/baseline은 없고 미추적 schema를 채택하지 않는다.
- source-next를 별도 manifest로 받아 삭제된 상품 version ID와 현 PK보다 커야 하고 현재
  target sequence를 뒤로 옮기지 않는다. 미리 모든 입력을 검사한 후 transactional ALTER RESTART를
  사용한다. 원본 metadata 자동 export와 전체 이관은 아직 구현하지 않았다.
- 현재 MySQL/NUL·case/accent/한글 정렬 전체를 ICU와 같다고 추정하지 않는다. PG 사실/고정
  조건과 향후 source 전체 대조를 구분한다. DB02 일부만으로 기존 API·세션/잠금 동등성을 주장하지 않는다.

## 검증 진행

도구 컴파일/단위·기본 schema 초안을 먼저 확인했다. 최초 단위 파일의 메서드 선언 오타는 실제
DB 실행 전 컴파일 단계에서 수정했으며 업무 오류/성능 결과로 세지 않는다.
첫 실제 실행은 FK ON DELETE RESTRICT의 SQLSTATE를23503으로 가정한 검증에서 실패했다.
실제 PostgreSQL18.6은 공식 정의의23001(restrict_violation)을 반환했다. 검사 코드를 정확히23001로
수정했으며 FK를 없애거나 허용 범위를 넓히지 않았다. 해당 업무 fixture TX는 rollback됐고 schema/
이미 통과한 복구·미추적표 상태는 보존했다. 기존 DB를 DROP/reset하지 않고 새 전용3DB로 재검증했다.

## 실제 검증 결과

별도 non-superuser/TLS PostgreSQL18.6의 round2에서 일반 DB통합3조건이 통과했다. 추가로 실제 CI와
같은 fresh labelled container를 새 tmpfs/동적127.0.0.1 포트로 만들어 bootstrap부터 재현했다.
소유한 CI bootstrap의 최종 Java17 실행은 단위5개·실PG4개, 합계9개 모두 통과/skip0이다.

| 제어 조건 | 확인한 결과 |
|---|---|
| fresh16업무표+Flyway history | V1설치1, 같은 migrate0·validate성공, version/checksum 보존 |
| 실제 source index metadata | 읽기 전용 SHOW INDEX의30개와 실제 pg_index의 유일성/ordered key·방향/nullable NULL순서/valid·ready·nonpartial BTREE가 일치 |
| TLS/정본/권한 | 명시 CA/SAN verify-full·SCRAM·실제SSL·비superuser·UTF8/18.6 guard, 다른CA 거절 |
| 실제 non-TLS peer | SSLRequest 뒤N을 받으면 인증 바이트 없이 연결 종료, owned peer 종료 |
| 문자열·도수·취향 | 선택 case/accent/한글정렬·C태그구분, REAL5.555 비트/필터경계, numeric15.25/10·known/null/빈관계 보존 |
| PK/FK·가격/삭제 | 명시ID·유일성/불법 범위 거절, 활성 상품/회원 삭제 후 nullable 참조와15000원 주문 snapshot 보존 |
| epoch/source/전달상태 | UUID·IN_FLIGHT token/시각·DELIVERED/ACK·source/fingerprint/identity원문을 fixture 값 그대로 저장/조회 |
| 불변 payload | json의 공백/키순서/NULL/[] 원문이 저장/조회·상품 삭제 뒤 동일; outbox 참조 RESTRICT |
| 7개 sequence/source-next | 명시ID와 삭제된 상품version9000 뒤9001 생성, 현재next 뒤로 이동 없음·잘못된watermark 거절/불변 |
| 두 번째 ALTER 실패 | 고정fixture 소유권으로42501을 주입, 먼저 실행된 product RESTART를 포함한7sequence 원값 전체 rollback·주문금액 보존·소유권 복원 |
| 실패DDL/복구 | 부분표+INSERT 뒤 의도적 SQL실패가 업무표를 남기지 않음, 같은 DB 정상V1/validate 성공 |
| 미추적schema/marker/checksum | 기존 authored 표값 보존한 채 baseline채택 거절, 잘못marker 거절, 변경checksum 거절 후 원본validate 성공 |

이 값/숫자는 허구 시연 입력의 고정 정합성 조건이며 실사용자/실제 이관 성과·운영 오류율·속도
개선이 아니다. 실제 source 전체 복사는 아직 구현하지 않아 이 표를 기존 MySQL 데이터 전량 보존으로
표현하지 않는다. ICU의 제한된 비교 조건도 전체 Unicode 동등성 증명이 아니다.

`./scripts/check.sh`는 Python3.12 도구65개·기존 API Java140개/패키징·도구 단위5개를 통과했다.
DB없는 기본 경로에서는 도구 PG통합4개를 실행하지 않으며, 위 실제 전용 실행/CI 조건과 구분한다.
현재 API jar는 여전히 dcb73c35b4dbf148b05775992408b8d930977e84022fbabc878e68491ed22b81이다.
root POM/기존Java·mapper·data.sql/001–008·PyMySQL relay는 이번에 변경하지 않았다.
기존 MySQL HTTP14/실engine 필수 CI는 유지하며 새 PG 도구를 같은 required backend-ci에 추가했다.

CI 준비 도구 자체도 새 서비스에서 실제 CA/SAN·TLS reload·비super role·3DB/marker생성을
통과했다. 일반 installer/wrapper에는 관리자 자격을 전달하지 않는다. sequence 실패 주입의
별도 관리자 DDL만 소유 CI표/fixture에 사용하며 앱/installer 권한을 높이지 않았다.
소유 bootstrap container·JVM/peer·생성 private material은 종료/정리했고 root 소유 DB와
읽기 전용 source는 보존했다. 최신 head CI/Codex/병합/main 검증은 조정자 최종 게이트다.

검수 중 schema 초안이 MySQL의 자동 FK index와 CREATE TABLE 밖의 명시 index를 빠뜨린 것을
확인했다. 실제 readonly source의16표 SHOW INDEX에서 개인/업무값 없이 metadata만 추출하고,
기존4개 외9개를 명시해 PK16/unique1/nonunique13을 보존했다. pref value 등의 FK는 기존 복합PK가
지원하므로 불필요한 별도 index를 만들지 않았다. source의 nullable ASC NULL-first를 읽기 전용
제어 질의로 확인했고 target catalog 검사에도 포함했다. 이 보강을 새로운 조회 성능 개선으로
표현하지 않는다. root가 보존한 초기 draft DB를 repair/변경하지 않고 새 소유 fixture에서 최종V1을
설치해 전체9개를 다시 통과했다.

로컬 openssl의 기본 serial 산출물 위치가 작업 루트의 .srl인 것도 확인했다. 자기 실행 시각으로
소유를 확인해 제거하고 -CAserial 경로를 ignored 소유 디렉터리 안으로 명시했다. 최종 재실행에서
루트 산출물 없음·private material/소유 container 정리를 확인했다. 원래 실패나 준비 산출물을
서비스 결함/성능 성과로 세지 않았다.

최종 V1 SHA256은98d9f46a24872139650694eff0cb3b6584ce9652633a8f4bd9f48fab5b3e6078,
source-index contract는5dfcac2cc69c4edc2089acce9e9fa96762dfbe1ad56eb738bb12615d5bcffbfe다.

## 최초 Actions 실패와 CI 환경 보정

PR24의 head8ec7dcc [첫 required CI](https://github.com/Pubburi/Backend/actions/runs/41325844371)는
PostgreSQL 준비 단계에서 exit2로 실패했다. 이후 PG통합·기존 HTTP·engine 단계는 실행되지
않았으므로 이 run을 통과 근거로 사용하지 않는다.

실제 runner의 Docker 명령은 -p5432:5432로 0.0.0.0/[::]에 공개했다. 도구의 정확한 loopback
publication 검사와 달라 기존 guard가 거절하는 조건이었다. workflow를127.0.0.1:5432:5432로
고쳤고 guard는 완화하지 않았다. [GitHub service ports 문서](https://docs.github.com/en/actions/tutorials/use-containerized-services/use-docker-service-containers)와
[runner 전달 코드](https://github.com/actions/runner/blob/main/src/Runner.Worker/Container/DockerCommandManager.cs)의
Docker --publish 전달을 확인했다. 실제 실패 CI의 두 publication 구조를 재현한 검사도 계속 거절한다.

별도로 로컬 자동화에는 존재하던 ignored .pubburi가 fresh checkout에는 없었다. 기존 하위 TLS
directory.mkdir는 clean ROOT에서 FileNotFound로 실패함을 먼저 재현했다. 부모만 mode0700/
exist_ok로 만들고 기존 TLS directory를 채택/덮어쓰지 않는 동작은 유지했다.

최종 Python3.12 전체66개가 통과했다. .pubburi가 처음 없는 별도 ROOT와 새 소유 loopback PG/
tmpfs에서 실제 준비·TLS·marker 생성부터 다시 실행해 PG9개 전부 통과/skip0·CLI·wrongCA
거절·30index/sequence rollback을 확인했다. CA 절대 경로와 Maven의 다른 cwd, 명시 serial 경로도
유지했으며 소유 container/private material/PID를 정리했다.
이 수정은 workflow/Python 준비/회귀/기록뿐이며 V1·Java·API jar는 바꾸지 않았다. 따라서 기존
Java140을 다시 빌드하지 않았고 최신 head의 required CI가 PG/HTTP/engine 전체를 다시 통과해야
한다. Codex의 두 구체 지적은 조정자가 원 스레드 답글·native 해결과 최신 head 게이트로 처리한다.

## README 선정

현재 네 사례는 서비스의 거래·세션·입력·검색 상태 경계에 실제 구현 근거가 있다. 이번 schema
도입만으로 더 강한 고객 업무 문제 해결을 확보했다고 보지 않아 네 사례와 순서를 보존한다.
검증 숫자/회귀 목록은 이 계획·PR·CI에만 남기고 README에는 넣지 않는다.
