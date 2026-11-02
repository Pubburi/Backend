# DB03 PostgreSQL 후기와 부모 수명

기준 main `9395294`, 작업 `codex/jpa-comments`다. 원래 CommentRestController/CommentServiceImpl과
요청/응답 DTO를 사용해 공개 상품 후기·관리자 전체 목록·작성·수정·삭제 다섯 작업을 별도 JPA
제공자에 연결한다. 기본 MySQL API/공유 서비스/POM·V1/V2·Java17·OSIV off/validate는 유지한다.

## 구현 전 계약과 선택

- 실제 JpaComment/CommentRepository와 DTO constructor projection으로 CommentDao 전체를 구현한다.
  엔티티/lazy 관계를 HTTP에 노출하거나 성공 stub/복제 서비스를 등록하지 않는다.
- 평점은 기존0.5–5.0의 float32/REAL이다. 0.5 단위나 정수로 강제하지 않고3.7 같은 값도 보존한다.
  본문은 기존 NotBlank/최대200자이며 공백/줄바꿈/따옴표를 임의 정규화하지 않는다. 과거 DB NULL
  본문은 조회에서 보존하며 새 HTTP의 null/빈 본문은400이다.
- 공개 페이지는 productId 조건·ID 내림차순·page/count를 그대로 사용한다. 없는 상품은200 빈
  페이지, 없는 후기 변경/삭제는404, 없는 양수 productId의 신규 작성은 FK409로 기존 계약을 유지한다.
- INSERT는 Repository의 실제 identity를 돌려주고 승인 snapshot의 sourceNext 고수위를 지킨다.
  수정은 rating/comment만 바꾸는 affected-row JPQL이고 삭제도 단일 affected-row DELETE다.
  삭제된 DTO를 merge해 다시 만들거나 작성자/productId를 재바인딩하지 않는다.
- 작성은 현재 세션 사용자, 변경/삭제는 작성자/현재 관리자만 허용한다. 역할/epoch 변경을 기존
  AccessGuard로 다시 확인한다. FK의 사용자/상품 cascade와 full FOR UPDATE/KEY SHARE 충돌을
  검증하며 일반 후기 변경에 새 부모/후기 선행 잠금 정책을 추가하지 않는다.
- runtime은 후기 SELECT/INSERT/DELETE·UPDATE(rating,comment)와 후기 sequence USAGE만 추가한다.
  PK/작성자/상품 UPDATE·표 전체 UPDATE·DDL/TRIGGER/TRUNCATE·sequence SELECT/UPDATE를 거절한다.
  후기 쓰기는 상품 count/가격·주문·스탬프/계정·version/event/PENDING·UUID/ACK를 바꾸지 않는다.

## 실제 실행의 경계

조정자가 새로 소유한 PG18.6/TLS 정상/실패 두 DB와 별도 nonowner runtime을 사용한다. browser DB는
조정자가 따로 담당한다. 원본 복사 행은 수정하지 않고 자체 계정/상품/후기만 만든다. 실패 DDL은
별도 failure DB의 정확한 자체 행(INSERT는 자체 사용자+상품+고유 본문)에만 적용하고 finally 제거한다.
불변 producer 기록은 삭제하지 않으며 최종 소유 폐기 DB 정리에 맡긴다. 원문/자격/SQL 로그는
ignored에만 둔다. 검증 도구의 COMMENT_NEXT는 승인 snapshot의 sourceNext.t_comment를 받는다.

Java17 모듈의 최초 단위3/패키징은 통과했다. DB 통합/준비42 skip은 성공에 넣지 않는다.
첫 실제 실행의 다수 검사는 control 자격이 별도 예약 파일에 분리된 것을 실행자가 환경에 합치지
않아 fixture 작업 전에 중단됐다. 예약 소유자와 정상/실패 DB 범위를 확인하고 control 두 키만
테스트 자식 환경에 추가했다. runtime/권한을 바꾼 제품 결함 수정으로 계산하지 않는다.

후기 정상5·실패3과 기존 전체 회귀, 같은 고정 JAR의 실제 등록 경로·쓰기/재시작/조회/삭제·
부모 cascade를 확인한다. 실제 결과와 독립 MySQL 대조/웹 흐름은 아래에 구분한다.
이 부분 제공자를 전체 DB03–06·취향/찜/추천·Psycopg3 relay·기본 cutover 완료로 합산하지 않는다.

실제 PG18.6/TLS·별도 runtime의 전체41 통합(기존33·후기 정상5/실패3)과 JPA 단위3이 통과했다.
이미 준비한 fixture1 skip은 성공 개수에 넣지 않았다. 위 단정도·본문/NULL·페이지/없는 상품·
소유권/현재 자격·부모 cascade·경합의 완전한 rating/body 쌍·동시 삭제200/404·삭제 후 UPDATE0과
별도 부모 잠금55P03·최소 권한 거절을 실제 HTTP/DB에서 확인했다. INSERT/UPDATE/DELETE 뒤
실패는 후기/부모/사용자와 기존 producer/UUID 상태를 보존했고 trigger/function은 finally 제거했다.

동결한 검증 JAR SHA256은 `909058a8afa7e9b0c3c98a5614178a92b03afb1f7b8a195ae6e52a277d947271`다.
조정자는 동결한 기존 MySQL 실행 JAR과 같은 자체 계정/상품으로29 HTTP를 독립 대조했다.
status와 전체 JSON이29/29 같았으며 값/평점 정규화나 시각 제외를 하지 않았다. 같은 새 상품81과
후기4를 사용해 원본 고수위를 보존했다. 공개 빈 페이지·쓰기/검증·소유권/관리자·분수 평점/본문
수정·관리자 페이지·삭제를 포함하며 원본 복사 행은 수정하지 않았다. 입력 명세 SHA256은
`d1a71ea51b9dadea163b189fbbb5b85d0188b37f3b8fe0ed78b28c3bef80a7ff`다.
이 고정 조건의 동등성은 모든 입력/Unicode·전체 웹/DB 인수나 운영 오류율/성능의 증명이 아니다.

Java17/Python3.12 `./scripts/check.sh`는 도구86·루트 Java140·이관 도구 단위18·JPA 단위3과
패키징을 통과했다. DB가 없는 경로의 이관9/JPA42 skip은 실제 DB 성공으로 합산하지 않는다.
재패키징 뒤에도 위 고정 JAR의 바이트 SHA가 같았다. 같은 JAR의 packaged smoke는 실제
29경로/33작업·분수 평점/본문 수정의 공개 조회·재시작 뒤 보존·작성자 삭제·상품 삭제 cascade와
기존 계정/주문/매장/상품·기동 거절을 통과했다. 소유 JVM6개/임시 JAR 정리와 정상/실패 두 DB의
자체 계정/상품/후기·실패 trigger/function0을 별도 읽기 전용 검수했다. 불변 producer 기록은 보존했다.

조정자는 같은 JAR과 동결한 React production을 별도 복사 PG에서 연결했다. 비회원 공개 빈 후기
GET200, 회원 화면의 작성201/본인 삭제200, 실제 API로 준비한1.1 평점의 화면 표시와 본문만
수정한 PUT200 뒤1.1 유지, 비회원 공개 재조회1.1, 관리자 후기 목록GET200/화면 삭제200이 성공했다.
API mock·예상외 API 오류·pageerror는 없었으며 정확한 favorites GET404만 미제공 경계로 분리했다.
초기 두 시도는 private harness의 선택 조건에서 중단됐다. label/ARIA 진단 뒤 기존 검증의
combobox role/name 선택으로 바로잡았고 제품 소스/JAR/입력은 바꾸지 않았다. 실패 시도의 소유
자원도 정리했으며 이를 제품 결함/개선 수치로 세지 않는다. 이 선택 흐름은 전체 FE03/C04·
취향/찜·전체 웹/DB 인수나 운영 성능을 뜻하지 않는다.

README의 네 기존 사례는 더 강한 독립 영향/원인 근거가 없어 본문/순서를 유지한다.
필수 CI·최신 완료 Codex·미해결0·병합/main CI는 별도 최종 게이트이며 지연/처리량 개선을 만들지 않는다.
