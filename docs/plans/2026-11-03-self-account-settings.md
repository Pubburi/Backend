# F09 본인 이름·비밀번호 API 공급

기준 main `7cd38cf`, 작업 `codex/self-account-settings`다. F09의 React 연결을 막는 본인 쓰기
두 경로를 기존 UserRestController·UserServiceImpl과 실제 UserDao에 추가한다. 같은 공통 코드가
기존 MySQL/MyBatis와 opt-in PostgreSQL/JPA에서 실행된다. 현재 미제공 경로를 연결하는 새
필수 기능 증분이며 과거 운영 장애 수정/성능 개선이나 전체 F09 웹 인수를 먼저 주장하지 않는다.
DB05 Psycopg3 relay는 다음 필수 작업으로 보존하고 이 PR에 섞지 않는다.

## 구현 전 계약과 실제 검증 기준

- PUT `/api/users/me/profile`의 `{name}`, PUT `/api/users/me/password`의
  `{currentPassword,newPassword}`만 받는다. 세션 ID/관측 epoch가 본인을 정하며 성공은200과
  Boolean true의 기존 ApiResponse다. 이름 NotBlank/max100, 현재 암호 NotBlank/max100,
  새 암호 NotBlank/4–100·UTF-8 최대72바이트를 literal 문자열로 처리한다. 기존 짧은 암호의 검증을 새 암호 정책으로 막지 않는다.
- 추가 body 필드/비문자 타입·누락/null/길이는400, query도 고정400이다. 관리자 권한으로
  타인 선택/role·stamps 수정 우회를 만들지 않는다. 기존 join/admin 정책은 변경하지 않는다.
- 기존 selectByIdForUpdate 뒤 epoch를 대조하고 현재 필드에서 새 DTO를 만들어 update한다.
  이름은 자격을 보존한다. 암호 불일치는403·무변경, stale/삭제/재가입은401이다. 성공 암호는
  기존 hasher+새 epoch와 immutable 변경 결과를 사용하며 AccessGuard의 세션 CAS를 재사용한다.
- 원래 서비스 TX에서 SQL 실패는 전체 rollback한다. 새 schema/DAO/GRANT·V1/V2·기본 DB 전환은 없다.
  두 변경의 동시성·이름/스탬프 보존·로그인/암호 회전·기존 세션/재가입·후반 UPDATE 실패를 검증한다.
  이미 승인된 요청의 취소나 전역 로그인 구현을 새로 약속하지 않는다.

조정자는 원래 MySQL/현재 JPA/migration·Frontend 산출물을 먼저 고정했다. 승인 snapshot의
345행/16표/7고수위를 각 새 TLS PG에 복사/전행 검수하고 새 자체 계정만 사용한다. 정상/실패는
worker, browser는 조정자 소유이며 원본 계정·ID/epoch/source UUID·불변 queue를 reset하지 않는다.
실패 DB의 정확한 자체 계정 AFTER UPDATE trigger/function은 finally 제거한다. SQL inspector/
실제 해시의 테스트 gate는 순서만 제어하고 SQL/서비스 동작을 바꾸지 않는다. 기존 전체 PG·
MySQL/Java/Python·JAR 재시작/허용 경로와 실제 HTTP·OpenAPI를 같은 필수 CI에서 유지한다.
조정자의 실제 두 DB HTTP 대조/React 인수는 실행한 범위만 결과에 추가한다.

## 암호 해시 경계 확인

원래 Java17 PasswordHasher를 기본 crypto6.5.0/JPA6.5.11에서 직접 실행했다. ASCII72자,
한글24자/emoji18개의72UTF-8바이트는 hash/match가 성공했고 ASCII73·한글25자75바이트·
emoji19개76바이트는 hash에서 IllegalArgumentException이었다. 따라서 새 self newPassword만
명시 byte 검증400을 추가한다. 현재 암호는 기존 max100/짧은 암호 호환을 유지한다. 기존
BCrypt matches는72바이트 prefix 뒤 suffix를 구별하지 않는 기존 의미가 있어 공유 hasher·
login/join/admin을 바꾸거나 truncation을 새 literal 보안 성과로 주장하지 않는다. prehash/trim·
새 알고리즘은 도입하지 않았고 새 신규 암호의 초과 입력은 저장 전 거절한다.

## 상태와 후속

Java17/Python3.12 전체 `./scripts/check.sh`가 통과했다. 기존 도구86/기본Java149/이관 단위18/
JPA 단위3을 확인했으며 DB 없는77 skip은 실제 성공에 합산하지 않았다. 실제 PG18.6/TLS·
최소 runtime에서 전체75 통합(기존67·self 정상6/실패2)+단위3이 통과했고 준비2 skip은 별도다.
엄격 입력/소유·현재 암호 불일치/무변경·이름/자격/역할/스탬프·개인 자식 보존을 확인했다.

SQL을 바꾸지 않는 gate에서 잠금 전 암호 회전과 동일ID 재가입 뒤 구 요청은 두 경로 모두401,
두 암호 변경은 첫200/두번째401이었다. 이름 수정과 관리자 스탬프 변경은 직렬 처리 뒤 둘 다
남았다. 기존 로그인은 이전 해시 검증 뒤 회전과 겹치면 옛 epoch의200 응답까지 완료할 수
있으나 다음 보호 요청은401이고 현재 변경 세션/새 암호 로그인은 유지됐다. 이 기존 인증
snapshot 경계를 진행 중 로그인 전체 취소/원자성으로 확대하지 않는다. 정확한 자체 계정의
AFTER UPDATE 실패는 이름/해시/epoch·두 세션·개인 상태/상품 producer를 rollback했고,
DDL 제거 뒤 같은 요청은 성공했다. 제어한 대기는 서비스 시간 성능이 아니다.

같은 JAR의 packaged smoke가 실제 OpenAPI35경로/42작업·기동 거절·새 두 API·비밀번호 회수와
재시작/재로그인 및 기존 업무 흐름을 통과했다. JPA JAR SHA256은
`3bfabd97f8a13bb9e8c69b93658c61d52323b041413b16f6e8ed1b73a9981d6e`, 기본 MySQL JAR는
`23c478055b4146fd29e94123e8dad56275f824fbd2a97ed139679536de697b81`다. 전체 검사/실제 PG 뒤
재패키지 SHA도 동결본과 같았고 생산 코드 변경은 없었다.

전용 MySQL 복사본에서 원래 smoke.sh의 읽기/OpenAPI/Swagger/default fallback을 확인했다.
기존 mutating_contract는 조회 첫 상품의 주문 수를 남기는 기존 하네스여서, 로컬 private driver가
동일 계약 함수 전체를 호출하되 이 인자만 실제 관리자 API로 만든 자체 상품으로 정했다.
새 self 입력/72바이트·현재 세션/다른 세션·재가입도 통과했다. 원래 CI의 전체 실행 경로/검사는
유지하며 생산 SQL·서비스를 바꾸지 않았다. 기존80상품·기존 계정의 전체 필드와 기존 event/
delivery/target은 exact 비교로 보존됐고 새20계정/3주문·자체 상품을 정리해 최종80개였다.
새 불변 이벤트는 남겼다. JDBC/MySQL 원본이나 다른 조정자 DB를 대상으로 하지 않았다.

PG 정상/실패/scratch 감사에서 자체 행·실패 trigger/function은 각각0, SELECT는 복원됐고
상품 수80/80/0이었다. packaged JVM6개·자체 거절 포트 소켓과 MySQL 소유 프로세스도 종료했다.
불변 version/event/delivery는 직접 삭제하지 않았다. 초기 새 단위 fixture의 Mockito agent attach
실패는 기존 reflection proxy 방식으로 교정했고, 제한 sandbox의 소켓 거절/소유 검사 중단은
로그를 보존한 뒤 허용된 로컬 환경의 같은 전체 검사로 통과했다. 검증/실행 조건 문제이며
제품 결함이나 자연 오류율로 합산하지 않는다.

조정자의 독립 두 DB 전체 응답 대조와 실제 React F09 인수는 별도 실행 결과/게이트로 관리한다.
이 기록의 API/DB 통과만으로 전체 화면 완료를 먼저 표시하지 않는다. 11월22일 DB/React·12월31일 전체20개 MVP,
최신 head 필수 CI/완료 Codex/미해결0·main 인수 게이트는 유지한다. 기본 MySQL과 PG의 부분
제공자를 보존하며 전체 DB03–06/cutover·DB05 relay 완료와 구분한다.

README 네 사례와 비교한다. 이번은 기존 계정 수명/권한 경계를 새 본인 수정에 적용한 필수
기능 증분으로, 더 강한 독립 원인/영향이 없으면 네 본문·순서를 보존한다. 테스트 수·기술 채택·
제어한 경합 대기를 운영 오류율/응답 시간 개선으로 바꾸지 않는다.
