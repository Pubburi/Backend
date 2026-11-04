# DB06 소유 PostgreSQL 로컬 release 실행

기준 main6fda9d3의37경로/44작업 JPA 제공자는 실제 API를 제공하지만 기존 local-stack 도구는
MySQL Compose만 실행한다. 이번은 준비된 소유 PG 복사본에서 같은 제공자를 세 서버에 연결하는
명시 실행 경로를 추가한다. 기존 MySQL 기본값·업무 Java·schema/V1/V2·권한은 바꾸지 않는다.
DB06 전체 전환이나 전체20개 MVP 완료를 뜻하지 않는다.

## 실행 전 계약

- `package-pg`는 깨끗하게 커밋된 JPA 실제 입력과 source commit을 고정하고 DB독립 Maven verify
  뒤 JAR SHA/입력별 SHA·완료 manifest를 새 private release에 발행한다. 실패/중단·입력 변경/
  기존 출력 재사용을 거절한다. Git object와 현재 입력·실제 JAR를 다시 확인해 임의 JAR를 채택하지 않는다.
- `--provider postgresql`은 private strict JSON·기존 소유 CID/label/loopback 공개 포트와 완료
  release를 요구한다. Backend `.env`/상속 DB/control·JVM 주입 설정을 사용하지 않는다.
  FE/Recommendation 자식에는 DB 비밀 없이 해당 앱 포트·도구 경로/프록시만 전달한다.
- 기존 JPA 기동의 verify-full/SCRAM·PG18.6·marker/receipt/source UUID·최소 runtime 역할·
  Flyway validate/Hibernate validate·OSIV off를 재사용한다. 새 DB/역할/GRANT·DDL/import·
  UUID/ACK/sequence reset은 없다. optional model catalog 생략은 원래 UNAVAILABLE이다.
- 같은 로컬 잠금과 포트 점유 거절을 사용한다. 자기 JAR/Tomcat 로그·상품 API/OpenAPI37/44가
  준비된 뒤에만 FE/Rec를 시작하고 health/HTML·`/api` 프록시를 확인한다. `--backend-only`는
  API release/CI용이며 세 서버나 웹 인수로 세지 않는다. status는 endpoint 관측만 수행한다.
- 성공/실패·Ctrl+C/SIGTERM은 이번 앱 process group만 정리하고 DB/컨테이너/볼륨을 보존한다.
  정리가 불확실하면 잠금을 남긴다. PG init/install/down/reset-db는 환경/Compose 접근 전에 거절한다.

실행 명령/필수 설정과 남은 수동 준비는 [로컬 개발](../LOCAL-DEVELOPMENT.md)에 둔다.
기본 MySQL local-stack 함수와 Compose 경로는 유지하며 새 PG 분기는 opt-in이다.

## 필수 검증과 원인 구분

기존 DB없는 check와 실제89 JPA 통합·packaged 업무/기동 거절/재시작을 유지한다.
새 도구 단위는 설정/출처/상속 비밀/포트·잠금/기존 dispatch/파괴 명령·CI runtime projection을 검사한다.
필수 CI에는 기존 copied normal/failure/빈 카탈로그 및 MySQL/relay 흐름을 삭제하지 않고,
검수된 normal DB의 실제 local release 생성·backend-only 기동/종료를 추가한다.

초기 실제 전체 JPA는 빈 recommendation 전용 복사본이 아직80상품인 준비 누락으로 lifecycle4건이
실패했다. 원문94/4fail/준비2skip을 보존했고 다른 source 복사본은 바꾸지 않았다. 조정자가 해당
전용 복사본만 기존 관리자 DELETE API로80→0 준비·프로세스 종료를 확인한 뒤 같은 source를
새 로그에서 전체 재실행해89통합+3단위 통과/준비2skip을 확인했다. 검사/분모를 낮추지 않았다.

첫 private 보존 auditor는 기존 typed-cell helper의 struct/datetime import 누락으로 실패했다.
당시 RAM-before를 확보했다고 소급하지 않는다. 조정자의 설치전345행/16표 검수와 별도로,
고정 accepted source264행의 typed-cell 직접투영을 종료 후 대조한다. launcher 직전 원본
queue/target 전체값은 새로운 baseline으로 분리 기록하고 종료 후 정확히 대조한다.

새 launcher 후보의 실제 첫 negative 실행은 정상 smoke 직후 marker 확인 전에 포트 preflight가
EADDRINUSE로 멈췄다. 뒤늦은 첫 단순 probe에서는 이미 재사용 가능해 원인을 확정하지 못했다.
같은 Tomcat에 HTTP/1.0 close 요청을 보내 서버 FIN을 받은 뒤 정상 종료시킨 별도 재현에서
LISTEN0·plain bind errno48·SO_REUSEADDR bind 성공을 관측했다. 닫힌 연결의 TIME_WAIT를
실제 listener로 오판한 후보 결함이었다. PG preflight만 Tomcat과 같은 SO_REUSEADDR로 바꾸고
수정 전 실패/수정 후 통과하는 실제 TCP 회귀·reuse가 켜진 살아 있는 listener 거절을 함께 유지한다.
고정 sleep·무조건 retry로 회피하지 않는다. 뒤의 private 하네스도 점유용 listener를 plain bind로
만들어 같은 경계에 걸렸고, 제품 수정 없이 그 준비/최종 재사용 probe만 같은 서버 설정으로 교정했다.

## 완료 경계·사례

이 작업은 DB06의 로컬 release/기동 증가분이다. 기본 MySQL/MyBatis 제거·운영 환경 설정/배포·
쓰기 중단·전행/문자열/업무 재인수·cutover 후 쓰기 역이관·rollback·재색인/relay 자동 운영과 전체
DB03–06은 별도 게이트로 남긴다. R02/R04 UI·서비스 승인 catalog/family 소비를 추가하지 않는다.
현재8/20을 실행 도구 하나로 늘리지 않는다. 서비스 성능 전후·실사용자 장애/성과를 주장하지 않는다.

README의 네 사례는 보존한다. 신규 로컬 실행과 이 후보의 TIME_WAIT 결함은 실제 근거가 있지만
기존 업무 원자생산/relay 상태·현재 권한/epoch·전행 이관 검수·주문 금액/삭제 이력의 독립 영향과
원인/대안 근거보다 강한 교체 사유는 아니다. 신규성/코드량/검사 수로 사례를 승격하지 않는다.
전체20개 MVP12월31일·DB/React11월22일, 추가 최적화는 MVP 뒤1월1일과 최종2월5일/2월6일
중단 기준을 유지한다. 실제 최종 source/head의 CI·완료 Codex·미해결0·main 병합은 조정자 게이트다.

## 최종 실행 결과

| 범위 | 관측 결과 |
|---|---|
| 기본 check | 도구117·Java179·migration 단위18 통과. migration 실DB9 skip 및 JPA DB없는3단위/91skip은 실제 인수와 분리 |
| 실제 PG | 같은 source의89통합+3단위 통과/준비2skip. 동일 release JAR로 기존 packaged 업무·기동거절·37/44·재시작/재로그인 통과 |
| 새 launcher | 최종 source 전후 SHA 동일한9조건 통과. 상속 DB/JVM 주입 제거·marker/label/JAR/점유 listener/reset 거절·status 관측·SIGTERM·동일 포트 즉시 재기동/자체 매장 보존·구세션401·삭제 정리 |
| 필수 CI 증가분 | 실제 runtime-only projection으로 새 release 생성·backend-only 기동/37/44/종료 함수를 소유 normal PG에서 그대로 통과 |
| 소유 종료/원본 | 기록23PID/process group 부재·포트/잠금 해제. 정상/실패 각각264원본 typed행 및 원래 delivery/target 정확·실패DDL0·읽기권한 복원·GRANT 추가0 |

조정자도 같은 동결 release/helper와 별도 browser PG·13개 runtime 키 설정으로 실제 세 서버를
두 번 UP했다. 같은 DB와 세 앱 포트에서 원래80 상품 페이지의 API/프록시 응답, ADMIN HTTP세션과
실제 Chromium의 다섯 관리자 탭(GET-only), B0의 실제 BASELINE2개와 미설정 B1 UNAVAILABLE를
확인했다. API/page 오류0이며 status 관측도 성공했다. 재시작 후 구cookie401·재로그인200,
두 번의 launcher 정상 종료와 세 자식 process group/포트 해제·DB 보존을 확인했다.
추가 업무 쓰기/원본 변경·새 모델 fit/서비스 catalog 승인은 없고 전체 사용자 기능 인수는 아니다.

동결 JPA 입력은 기준 source commit과199개 개별 파일 SHA에 결속했고, 실제89 후 생성물의 모든
ZIP entry payload가 release와 같음을 확인한 뒤 정확한 release JAR를 packaged 검증에 사용했다.
뒤의 source 변경은 launcher/CI도구·회귀·실행 문서에 한정하며 업무 Java와 V1/V2는 변경0이다.
기존 MySQL 경로는 단위 dispatch/기존 도구 검사와 필수 CI에서 유지한다. 이번 로컬 실행 결과를
새 MySQL 전체 회귀·전체 PG cutover/rollback이나 서비스 성능 개선으로 바꾸지 않는다.
