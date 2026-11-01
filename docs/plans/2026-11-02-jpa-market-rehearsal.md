# DB03 실제 JPA 세션·매장 제공자

기준 main3748f25, 작업 codex/jpa-market-rehearsal. 사용자 확정 PostgreSQL18.6·Spring Data
JPA(Hibernate)·Flyway Community를 실제 HTTP/업무 저장에 연결하는 첫 제공자다. 이 작업은
DB03 일부이며 DB02 복사 도구를 전체 API/JPA 전환 완료로 승격하지 않는다.

## 완료 단위와 선택

[제공 계약](../JPA-REHEARSAL.md)의 세 인증 경로와 원래 매장 Controller를 별도 모듈에서
실행한다. 실제 일곱 path/여덟 operation만 등록한다. 상품·주문·개인 기능은 미등록404이며
성공 stub·미사용 entity scaffold를 만들지 않는다. 기본 API/MySQL/MyBatis·001–008/data.sql과
루트 POM/Java는 그대로다. 명시 별도 JAR/loopback 동적 포트의 rehearsal이며 기본 스택을
전환하지 않는다. DB02에서 profile을 넣지 않았던 범위와 이번 실제 제공자를 구분한다.

- 원래 MarketRestController·User/MarketService·AccessGuard·PasswordHasher·요청/응답 DTO를
  소스 whitelist로 공유했다. 미제공 주문 서비스에 결합한 원래 전체 UserRestController 대신
  세 인증 transport만 새 Controller로 연결했다. 업무 규칙/DTO 사본·가짜 주문 bean은 없다.
- 일반 CRUD는 Repository, 읽기는 JPQL DTO projection이다. assigned 사용자 ID의 신규 상태는
  Persistable lifecycle로 처리해 단순 merge의 동일/대소문자 동등 ID 덮어쓰기를 방지했다.
- 세션은 ID+epoch의 새 projection, 비밀번호 전환은 이전 hash+epoch CAS다. mutable DTO의
  withoutPass가 managed entity를 지우지 않는지 실제 DB에서 확인했다.
- Hibernate6.6 PostgreSQL translator의 FOR NO KEY UPDATE와 전체 사용자 수명 FOR UPDATE를
  구분했다. 후자는 native 잠금으로 보존하고 실제 다른 연결 KEY SHARE의 차단을 확인했다.
  이를 실제 고객 동시 부하·API 지연 개선으로 해석하지 않는다.
- OSIV off/ddl-auto validate·Flyway validate만 사용한다. 매핑한 두 entity의 검사와 기존16표
  migration checksum 검사는 범위가 다르다. 전체 업무를 JPA로 재검증한 것은 아니다.
- 실제 MyBatis annotation은 compile-only이며 실행 repackage에서 제외했다. JAR ZIP과 실제
  JVM에서 MyBatis/MySQL/H2·미제공 원래 Controller가 없는지 확인했다.

별도 모듈의 실제 resolved 버전은 Boot3.5.16·Spring Data JPA3.5.13·Hibernate6.6.53.Final·
Tomcat10.1.61·Jackson BOM2.21.8·pgJDBC42.7.14·Flyway13.12.0·springdoc2.8.9다. Java17/공식
BOM·행 잠금의 primary 근거는 [제공 문서](../JPA-REHEARSAL.md)에 연결했다. 루트 Boot3.5.0을
변경하거나 Hibernate 제한 유지보수/기존 BE07 전체를 해결했다고 쓰지 않는다.

## 실제 PG 준비와 최소 권한

조정자는 새 TLS PG18.6 두 DB에 동결 H19 도구로 copied source345행/16표·7source-next·
수령증을 복사/검증한 뒤 고정 허구 계정 세 개씩만 추가했다. 원본 사용자 DB/API에 접근하지
않았고 source artifact/V1/V2·UUID/epoch를 초기화하지 않았다. 앱은 이 복사본의 runtime 역할만
사용한다. migrator는 별도 테스트 제어 연결에 한정하고 앱 JAR child env에는 전달하지 않는다.

runtime은 non-super 외 CREATEDB/CREATEROLE/REPLICATION/BYPASSRLS·membership·DB/schemaCREATE·
객체 소유권·User/Market TRIGGER/TRUNCATE도 거절한다. User/Market DML·매장 sequence USAGE,
marker/history/receipt/relay SELECT와 공유 삭제 정리의 주문 두 컬럼 권한만 부여한다. 실제
CREATE TABLE/ALTER TABLE/DROP TABLE은 모두 SQLSTATE42501로 거절됐다. runtime SELECT만으로
sequence 현재값을 관찰하지 않으며 source-next 비교는 새 생성 ID로 확인한다.

TLS verify-full·SCRAM-SHA-256·UTC·명시 source UUID와 import receipt/relay source binding·marker를
확인한 후 JPA를 시작한다. automatic DDL/OSIV 설정은 거절하고 migrate/clean/repair/자동
source 채택을 하지 않는다. wrapper는 별도로 CID/label/loopback·서로 다른 정상/실패 DB를 확인한다.

## 실제 검증 결과

| 실행 경계 | 확인한 결과 |
|---|---|
| 공개/관리 매장 | 공개 목록/페이지·기존5필드와 double 좌표 유지, 비인증401/일반403/관리자 생성201·수정200·삭제200/없는 행404 |
| 실제 SQL 실패 | 별도 migrator가 고정 이름의 매장 AFTER INSERT trigger만 설치, HTTP500 뒤 행수 불변·trigger/function finally 제거 |
| 새 ID/재시작 | 생성 ID가 source-next 이상, 실제 JAR 중단/재시작 뒤 변경 매장 보존·메모리 세션 재로그인·정리 삭제 |
| 사용자 insert-only | 같은 대소문자 동등 ID 직접 INSERT 충돌, 기존 role/hash/epoch 불변 |
| 해시/epoch | legacy SHA-256 로그인 후 BCrypt 전환, 비밀번호/epoch 변경 뒤 이전 CAS0·구세션401·구비밀번호401·새 로그인200 |
| 삭제/재가입 | 작성한 계정만 삭제/같은 ID 재생성, dormant 구쿠키401·새 로그인200·이전 epoch CAS0 |
| 현재 정보 | 관리자 role/name/stamps 변경을 기존 cookie의 me/403에 반영, finally 원래 필드 복원 |
| DTO/잠금 | withoutPass 후 DB hash 유지, 사용자 FOR UPDATE 안에서 별도 KEY SHARE가55P03으로 거절 |
| 기동/등록 범위 | 별도 실패 DB에서 명시 flag 누락·auto DDL·wrong marker/source 거절, 실행 OpenAPI 정확7path·미제공 product/order/profile404 |
| 최소 권한 | 실제 runtime의 업무 schema CREATE/ALTER/DROP42501, 부적합 설정/권한을 조용히 자동 수정하지 않음 |

위는 새 제공자의 통제된 정확성 실험이다. 과거 운영 장애/오류율 감소·응답 성능·실사용자
규모나 주문/검색/추천 품질 성과가 아니다. source345행 수는 복사 도구의 준비 입력이며 JPA가
345행 전부의 업무 처리를 제공한다는 뜻도 아니다.

기본 check: Python3.12 도구77개, 루트 Java140개, 이관 도구 단위18개, JPA 단위3개/패키징
성공. DB 없는 경로의 이관 통합9개/JPA 통합·준비3개 skip은 DB 성공으로 세지 않는다.
실제 별도 실행에서는 JPA HTTP2개가 성공했고 CI 전용 준비는 정상/실패 DB에 각각 실행했다.

새 owned PG1/MySQL1·tmpfs·동적127·자체 TLS bootstrap으로 기존 PG3/import4에 새 JPA2DB를
추가한 총9DB를 준비했다. 기존 PG/source/export/import 통합과 새 runtime 역할·HTTP/실행
JAR/기동 거절·재시작 검증을 모두 통과했다. 자기 CID/label/tmpfs 확인 뒤 두 container와
개인 CA·credential·artifact를 finally 정리했다. 조정자 원본 두 DB/자원은 보존했다.

필수 backend-ci는 같은 성공 조건에 새 모듈·JPA 실제 경로를 포함한다. 기존 MySQL HTTP14·
엔진/relay·PG/source/import 검사와 문서 변경 시 필수 실행은 유지한다. 새 JPA raw 통합 XML·
서버로그·자격/원본 artifact는 업로드하지 않고 `target/jpa-rehearsal-report.json`의 안전한 요약만
7일 보존한다. Actions 최신 head CI/완료 Codex/미해결0·병합 mainCI는 별도 최종 게이트다.

## 중간 실패와 보강

1. 첫 실제 로그인401은 조정자가 legacy hash를 plaintext로 준비한 fixture 오류였다. 기존
   SHA-256 정책을 확인해 authored 계정3개×2DB의 hash만 정정한 뒤 같은 DB에서 재검증했다.
   ID/epoch/role/import 상태를 reset하지 않았으며 인증 정책 약화·제품 오류율 개선으로 세지 않는다.
2. 개발 중 native typed query의 raw List 원소를 String으로 명시 cast하는 컴파일 오류를 고쳤다.
   DB/사용자 실패나 성능 결과가 아니다.
3. 부모 승인 검수에서 assigned-ID merge·비소유 runtime 역할과 사용자 잠금의 강도를 명확히
   보강했다. 새 설계의 실패 경계를 실제 회귀로 확인한 것이며 과거 서비스 결함이라고 만들지 않는다.
4. 추가 도구 확인을 기본 shell Python3.9/제한 sandbox에서 실행한 시도는 socket/ps 권한 거절로
   무효였다. 확인된 이번 테스트 자식만 정리하고 Python3.12/승인된 loopback·프로세스 조건에서
   77개 전체 성공을 다시 확인했다. 제한 환경 실패를 제품 실패/성공 분모에 합치지 않는다.

실행 로그/원문·계정/개인 경로는 ignored에만 보존하며 공개 근거에 싣지 않는다. 도구 child
deadline은 소유 새 session을 leader reap 전에 종료해 자식을 남기지 않는다. 정상 종료 뒤 PID가
재사용될 수 있는 process group에는 signal하지 않는다. 진단 실패와 실제 소유 JVM 정리를 분리했다.

## 불변 artifact와 한계

- 최종 새 JPA JAR SHA256: `8cef8a1bcddd9bc6d24ddf22a70bb2a1075a87d5699c36659f13970403e2d0ae`.
- 기존 API JAR SHA256: `dcb73c35b4dbf148b05775992408b8d930977e84022fbabc878e68491ed22b81`.
- V1 SHA256: `98d9f46a24872139650694eff0cb3b6584ce9652633a8f4bd9f48fab5b3e6078`.
- V2 SHA256: `23da1a7f3b24d5584599e3a4ca4ac715e66ba048465aee9029893c19ef9132e6`.

사용자 관리/등록 HTTP·주문·상품/특성·취향/찜·필터/검색·outbox/relay PG 이전은 미제공이다.
공유 UserDao의 내부 수명 회귀가 해당 전체 API 제공을 뜻하지 않는다. 미제공 root API의 기존
MyBatis runtime은 그대로다. UI 전체/DB04–DB06·Psycopg3 전환·cutover와 되돌리기는 후속이며
사용자 원본 DB 이관이나 실제 운영에 사용하지 않는다.

README 비교: 실제 JPA 경계·새 포팅 판단은 확보했지만 기존 네 사례의 업무 원자성/재전달·
구세션 수명·두 엔진 전체 복사/원자 수령증·주문 이력 보존을 교체할 더 강한 독립 영향 근거는
없다. 사례 네 개와 순서를 그대로 보존하고 기술/실행 안내에 이 제공자의 정확한 범위만 추가한다.
