# R02·R04 본인 취향과 검수 DEMO B1 caller

기준 main41eeffb에는 R06 문구와 공개 B0가 있지만 현재 본인의 저장 취향을 기존 B1 content에
전달하는 업무 caller는 없다. 이번은 이 한 경로를 기본 MySQL과 별도 JPA에 함께 제공한다.
기본 DB 전환·모델/권리 정본 복제·전체 R02/R04 완료는 범위에 넣지 않는다.

## 구현 전 계약과 선행

- private POST `/api/users/me/recommendations/content`는 strict limit만 받는다. 기본4·1–12이며
  query/계정/epoch/profile/preferences/catalog/host/설명 선택자를 받지 않는다. 현재 세션의
  ID/observed epoch로만 canonical 저장 취향을 읽고 private/no-store를 유지한다.
- Recommendation의 공식 `backend_catalog_bundle`이 checked_export를 재검증해 만든 fresh
  DEMO bundle만 운영자가 파일/전체 SHA256으로 고정한다. Java가 권위·동일성·fit을 다시 만들지
  않는다. 기존80·이름/순서로 추정한 ID·sourceName/URL을 승인 catalog로 취급하지 않는다.
- bundle은 명시 catalog/ID·sku_group/원 Product7·features8과 원 capture 종료+5분의 유효기한을
  가진다. historical/만료/미설정은 전송0·UNAVAILABLE이다. 일반 서비스 SKU/권리 승인이 아닌
  REVIEWED_DEMO로 응답하며 가격은 업무 시연 스냅샷, available은 명시 공개행 존재 범위다.
- 현재 취향·상품·특성/태그는 짧은 RR에서 배치 조회한다. 원격 밖 두 snapshot과 승인 binding을
  대조한다. 의미 변화/상품 삭제/조건 불일치는409, 자격/계정 수명은401, DB 오류는500이다.
  원격 실패/비정상 wire는 result=null/products=[]의 UNAVAILABLE로 구분한다. 조건을 낮춘
  로컬 순위/B0를 개인화 결과로 만들지 않는다. 기존 공개 B0는 그대로다.
- 기존 content에 전체 preferences·추가 constraints={}·include_explanations=true를 전달한다.
  원래 soft/hard/null/[]/0·원자 태그와 점수/기여/순위를 유지하며 새 모델을 구현하지 않는다.
  ID/유일성/상한·적격 수/필수조건·가용성·명시 없음인 제외·진단과 R04의 실제 상품/profile 사실·
  양수 기여 합/상위 표시·단위·fallback을 consumer에서 검증한다.
- 고정 content route·connect200ms/전체3초·request512KiB/response256KiB와 strict JSON/redirect
  거부를 적용한다. B1의 첫 수치 라이브러리/fit 비용은 B0/R06과 다르며 기존700ms를 바꾸지 않는다.
  클라이언트 취소가 이미 진행 중인 제공자 CPU fit을 중단시킨다고 보장하지 않는다.
- 기존 실제84 PG·packaged/기본 MySQL 회귀와 strict OpenAPI 범위를 유지하며 새 POST만 더한다.
  V1/V2/schema/GRANT·원본80/epoch/source UUID/ACK/sequence reset은 없다. 실제 DB/서버는
  조정자가 예약한 폐기본과 새 자체 작성 행에만 사용한다.

## 검증 계획

기본 단위는 공식 bundle shape/wholeSHA/만료·변조/ID/원문 바인딩·strict input/현재세션·
기여/설명 사실/필수조건·본문 상한/절대 기한을 확인한다. 실제 PG는 원격 대기 중 취향/가격/
특성/삭제·credential/삭제재가입/세션교체 경합과 RR·DB 오류/읽기 전용·후속 원본 보존을 검사한다.
실제 고정 Recommendation·두 DB의 동일 author catalog/명시 취향은 조정자와 별도 연결해
원래 B1 응답·현재 Product7·DEMO 범위·실패 결과를 대조한다. fixture/skip을 전체 인수로 세지 않는다.

README 네 기존 사례는 더 강한 독립 제품 문제·원인/영향 근거가 없는 한 본문/순서를 유지한다.
전체20개 MVP12월31일·DB/React11월22일, 추가 최적화는 MVP 뒤1월1일부터라는 기준은 유지한다.

## 최종 로컬 검증

| 범위 | 관측 결과 |
|---|---|
| 기본 구성요소 | Java179·도구100·migration 단위18 통과. migration 실DB9 skip은 실제 성공과 분리 |
| 실제 PG18.6/TLS·최소 runtime | 기존84+신규5=89통합·단위3 통과, 준비2 skip 별도 |
| 실행 JAR | JPA37경로/44작업·재시작/재로그인·기존 업무 통과. 기본 MySQL12그룹 통과 |
| 원본/정리 | 정상/실패 각14표264원본 typed cells 정확·실패 trigger/function0·원래4 actor 외 ci계정0. 정확 SELECT 복원·기록10PID 부재/소유 포트 해제 |
| MySQL 보존 | source80·전체 기존 계정/event/delivery/target 전후 동일. 자체20계정/3주문 정리, 불변 신규 이벤트는 유지 |

최종 production source와 두 실행 JAR을 동결했다. 전체 실제 PG 뒤에도 모든 ZIP entry가
같음을 확인하고 같은 동결 JAR로 packaged 검증했다. 신규 실제5개는 직접 작성 HTTP fixture와
실제 Repository/DB 경계의 검증이며 공식 B1 모델/권리·품질 인수로 세지 않는다.
공식 producer의 합성3상품 bundle도 Java17의 실제 byte pin/지원 shape parser로 소비했다.
이때 고정 clock은 만료된 합성 파일의 단위 진단에만 쓰며 실제 caller는 유효기한을 유지한다.

조정자는 같은 동결 production JAR와 고정 Recommendation wheel·공식 fresh bundle로
실제 MySQL과 TLS JPA 각각12조건/제공자8응답을 확인했다. 정상 PROVIDER의 원래 전체 B1 result와
현재 Product7·같은 ID순이 같고, 명시 매핑3개 외 기존80/계정 ID·cookie/hash/epoch 전송은0이다.
NOT_STARTED의 명시 기본순·hard 가격1/ABV0·가격0 빈 결과·허용 주종 사케의 OOV·soft 순위/
limit1·다른 본인·selector400·현재 가격 변경409/복원·logout401을 구분했다. first cold의
3초 UNAVAILABLE와 늦은 제공자200도 별도 정상 기한 정책으로 관측했고 정상 빈 추천과 구분했다.
이는 직접 작성한 기능/계약 인수이며 실제 주류/사용자 선호·서비스 모델 품질/성능 결과가 아니다.

두 DB의 자체3상품을 원래 관리자 API로 제거한 뒤 원래80 목록 응답을 정확 복원했다. 별도로
원본16표의 전체 원래 행과 기존 사용자/event/version/delivery/source-target의 전후 해시가
같음을 확인했다. private 초기 하네스/원문/새 관측과 최종 인수·보존 근거는 분리 보관한다.

## 초기 실패와 근거 한계

첫 draft compile의 byte JSON 읽기 IOException 처리 누락은 같은 candidate에서 바로잡았다.
첫 합성 bundle probe는 성공 message가 항상'ok'라는 잘못된 소비 전제로 거절했다. 원래
성공 envelope의 임의 문자열 message 계약으로 대조했으며 producer 값을 바꾸지 않았다.
조정자 WIP 검토 뒤 container 전용 origin/evidence/scope 짝·records 순서·known volume·
source taste/aroma=null을 좁혀 검증했다. 권위나 DB 태그를 임의로 추가하지 않는다.

migration 기본 검사를 소켓 권한 없는 실행으로 호출한 첫 시도는 실제 non-TLS peer 단위에서
EPERM1이었다. 같은 소스를 소유 loopback 권한으로 실행해18단위를 통과했고 처음 로그를
보존했다. 첫 MySQL은11그룹 뒤 별도 실패 DDL 자격 누락을 preflight에서 거절했다. 원본80/
기존 계정·queue/target은 같고 자체19계정/2주문을 정리했다. 정확 소유 schema의 기존 고정
DDL만을 위한 자격을 fixture에 보완하고 새 출력의 동일 JAR12그룹을 통과했다. 역할 확대나
글로벌 설정 변경·검사 삭제/skip으로 복구하지 않았다.

조정자의 첫 실제 B1 하네스는 완료 뒤에만 적재한 trace 개수0을 전송0으로 해석했다. 고정
JAR/공식 실제 bundle의 static/fixed-clock loader와 Spring config/mapper probe는 모두
통과했다. 수신/제공자 완료/호출자 응답을 분리한 새 관측에서 실제 수신→3초 UNAVAILABLE→
늦은 제공자200이 확인됐다. 첫 cold fit도 무조건 PROVIDER여야 한다는 전제를 바로잡고 기존
기한/생산 소스/입력은 유지했다. 이런 관측 시각은 timeout 진단이며 서비스 성능/오류율 측정이 아니다.
이어진 중간 하네스의 미지원 주종 저장200 전제도 canonical8주종400과 구분한다. 실제 원문
/첫 실패·새 검증을 별도로 보존하며 재시도나 입력 정규화로 실패를 지우지 않는다.

## 범위·사례·일정

기본 MySQL/MyBatis·/api/ApiResponse/세션과 V1/V2/최소 권한을 유지한다. 새로운 schema/GRANT
/원본80 전송·fit/UUID·ACK·sequence reset은0이다. 전체 R02/R04·서비스 catalog/품질·다른 B1
owned caller·전체 DB03–06/기본 PostgreSQL cutover는 후속이다. 현재8/20을 자동으로 늘리지 않는다.

README 네 사례의 본문/순서는 유지했다. 이번은 신규 caller의 필수 연결이며 독립적인 과거 제품
장애·영향/원인/대체안 근거가 기존 사례보다 강하지 않다. 새 소스/테스트 개수·fixture/하네스
전제 실패를 포트폴리오 사례로 승격하지 않는다. 전체20개 MVP12월31일·DB/React11월22일과
추가 최적화는 MVP 뒤1월1일, 최종2월5일/2월6일 구현 중단 기준을 유지한다.
