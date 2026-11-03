# DB03 PostgreSQL 본인 찜과 계정 수명

기준 main `f390896`, 작업 `codex/jpa-favorites`다. 원래 FavoriteRestController/FavoriteServiceImpl을
사용해 본인 단건 상태·페이지·PUT/DELETE 네 작업을 별도 JPA 제공자에 연결한다. 독립적인 명시취향
저장은 다음 단위다. 기본 MySQL API/공유 서비스/POM·V1/V2·Java17·OSIV off/validate는 유지한다.
이미 MySQL에서 제공한 F01 계약을 재인수하며 필수 기능을 새 완료로 중복 계산하지 않는다.

## 구현 전 계약과 선택

- 복합 PK(user_id,product_id)의 실제 Favorite entity/Repository와 현재 Product DTO projection을
  사용한다. 단건은 epoch가 맞는 사용자 행과 관계 EXISTS를 한 SQL에서 읽으며 null은 만료,
  false는 현재 계정의 관계 없음이다. 관리자도 본인만 읽고 body/query의 소유자는 신뢰하지 않는다.
- 페이지는 원래 짧은 REPEATABLE READ에서 identity/count/items를 읽고 created_at DESC·product_id DESC와
  현재 Product7 필드를 유지한다. 저장한 시점의 상품 snapshot이나 관계 시각/계정 ID를 공개하지 않는다.
- 기존 서비스의 User→Product full FOR UPDATE와 잠금 뒤 epoch 재검증을 유지한다. PUT은 상품 존재를
  확인하고 DELETE는 없는 관계/삭제 상품에도200 false다. 구 자격의 대기 요청을 새 계정에 옮기지 않는다.
- Repository INSERT의 ON CONFLICT DO NOTHING은 중복의 첫 created_at을 보존한다. 신규는 명시
  statement_timestamp()로 기존 MySQL TIMESTAMP(6)의 문장 시각을 유지한다. V1의 TX 시각 default는
  변경하지 않으며 JVM 시간을 bind하거나 sequence/관계를 reset하지 않는다.
- runtime에 favorite SELECT/INSERT/DELETE만 추가한다. 모든 UPDATE·DDL/TRIGGER/TRUNCATE는 거절하고
  새 sequence는 없다. 회원/상품 삭제 cascade와 계정 재가입 격리를 유지하며 상품/회원 합계·주문
  이력·version/event/PENDING·source UUID/ACK를 찜 동작으로 바꾸지 않는다.

## 검증의 사전 경계

조정자가 소유한 PG18.6/TLS 정상/실패 두 DB와 별도 nonowner runtime만 사용한다. browser DB와
기준 MySQL/웹 인수는 조정자의 별도 범위다. 새 자체 계정/상품/관계만 검증하고 원본 복사 행은
수정하지 않는다. failure DB의 정확한 자체 user_id/product_id 관계에만 AFTER INSERT/DELETE
실패를 설치하고 finally 제거한다. 불변 producer 기록은 삭제하지 않으며 최종 소유 DB 폐기에 맡긴다.
원문/자격/SQL 로그는 ignored에만 보관한다.

실제 본인/관리자 격리·상태/빈 결과·입력/offset·첫 시각/반복/재추가/동률·현재 상품·부모 삭제/재가입,
RR의 별도 commit·구 자격 대기 요청·문장 시각·동시 멱등성·실패 rollback을 확인한다. 단위/패키징과
DB 없는 skip을 실제 PG 성공으로 합산하지 않는다. 실제 결과와 독립 대조/웹 흐름은 아래에 구분한다.

Java17 모듈 단위3/패키징이 통과했고 DB 없는 통합/준비50 skip은 성공 개수에 넣지 않았다.
별도 control 예약의 소유자/허용 DB를 확인한 뒤 두 control 키만 테스트 자식 환경에 합쳤다.
실제 PG18.6/TLS·별도 runtime의 전체49 통합(기존41·찜 정상6/실패2)과 단위3이 통과했다.
이미 준비한 fixture1 skip은 합산하지 않는다. 본인/관리자/타인 격리·수명/입력·현재 상품·첫 시각과
재추가/동률, 두 PUT/DELETE의 멱등성·계정/상품 삭제와 주문 이력 보존을 실제 HTTP/DB로 확인했다.

테스트 전용 statement gate는 count 뒤 items 앞에 실제 별도 commit을 넣었다. 같은 요청은 기존
total/items를 보존하고 다음 요청은 새 관계를 읽었다. 다른 gate는 User 잠금 직전 비밀번호를 회전해
대기한 구 epoch의 PUT401과 관계 불변을 확인했다. 오래된 TX에서 생성한 시각도 DB 문장 시각의
하한 뒤였다. 통제한0.2초 간격은 시각 의미의 검증이며 서비스 응답 성능이 아니다.
실패 DB의 정확한 관계에 AFTER INSERT/DELETE 예외를 넣어 관계/첫 시각·계정/상품/producer/UUID
상태가 요청 전후 같은지 확인했고 trigger/function은 finally 제거했다. runtime의 모든 UPDATE와
TRUNCATE 거절도 유지했다. 검증 JAR SHA256은
`e702e27d848a676e64df1886ca106286cfcd3fe8853f08e4df1852f86fe2f4f3`다.

Java17/Python3.12의 전체 `./scripts/check.sh`가 통과했다. 기본 API Java140·도구 Python86·
이관 단위18·JPA 단위3을 확인했고 각 실제 DB 검사의 skip을 성공으로 합산하지 않았다.
같은 SHA의 패키지 기동/재시작은 실제 API31경로/37작업과 찜의 단건·현재 상품 페이지·반복 요청·
재시작 보존/재로그인·부모 삭제를 확인했다. 이전 계정/주문/후기/상품 흐름과 잘못된 기동의 거절도
유지됐다. 소유 JVM6개는 모두 종료됐고 정상/실패 DB의 자체 계정·상품·후기·찜과 실패 trigger/function은
각각0이었다. 불변 검색 기록은 정리 대상으로 삼지 않았다.

독립적인 기존 MySQL 패키지와 위 JPA 패키지는 같은 새 자체 계정/상품/찜 시나리오50개에서
HTTP 상태와 전체 JSON이 모두 같았다. 응답에 관계 시각이 없어 시각 제외나 정규화를 하지 않았다.
본인/관리자/타인 격리·무시되는 소유자 selector·멱등/정렬/재추가·현재 Product7·숫자/없음·
상품 cascade와 같은 ID 재가입/구 epoch를 포함한다. 고정 시나리오 SHA256은
`ef9dbd06fefaedc6e9c7652b7a91acaba1aedf26ffdb9054d1c2dfb7ae589ca3`다.

고정한 실제 React 산출물과 같은 JPA JAR의 선택된 찜 흐름도 통과했다. 게스트 상세의 Product7과
장바구니 수량2를 유지한 채 로그인하고, 상태 GET200 false→UI PUT200 true→본인 목록의 현재
가격/상품 링크를 확인했다. 관리자의 자기 목록은 비었고 키보드 DELETE200 뒤 빈 목록의 탐색
링크로 초점이 이동했으며 상세 상태는 다시 false였다. 장바구니는 유지됐고 예상 밖 API 오류·
페이지 오류·mock은0이었다. 최초 브라우저 하네스는 request baseURL 누락으로 해당 HTTP 전에
실패했다. 자체 자료를 정리하고 하네스 설정만 고쳐 같은 JAR/입력으로 재검증했다. 이를 제품
결함 수정이나 전체 F01/고객 화면/DB03–06 인수로 확대하지 않는다.

README 네 사례는 더 강한 독립 영향/원인 근거가 없어 본문/순서를 보존한다. 지연/처리량 성과를
만들지 않으며 전체 DB03–06·명시취향/추천·Psycopg3 relay·기본 cutover와 구분한다.
필수 CI·최신 완료 Codex·미해결0·병합/main CI는 별도 최종 게이트다.
