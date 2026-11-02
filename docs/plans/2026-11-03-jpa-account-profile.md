# DB03·DB04 PostgreSQL 계정/프로필과 자격 수명

기준 main `9fa7ab0`, 작업 `codex/jpa-account-profile`다. 원래 UserRestController의 가입·ID 확인·
프로필/등급·관리자 목록/수정/삭제 여섯 작업을 별도 JPA 제공자에 연결한다. 초기 얇은 인증
Controller는 제거하고 원래 Controller의 기존 인증 세 작업을 함께 사용한다.
기본 MySQL/MyBatis 실행·공통 API/서비스/POM·V1/V2·Java17·OSIV off/validate를 유지한다.

## 구현 전 계약과 범위

- 기존 JpaUserDao/UserRepository와 실제 Order/OrderDetail/Stamp 영속 계층을 그대로 사용한다.
  원래 JoinUserRequest/AdminUserRequest·GradeService·ProfileResponse를 whitelist로 연결한다.
  같은 인증 경로를 중복 등록하거나 DAO 성공 응답을 만들어 필요한 기능을 대체하지 않는다.
- 가입은 입력 검증·중복 확인과 insert-only ID·비밀번호 hash·새 epoch/USER/0스탬프를 유지한다.
  프로필은 현재 사용자·최신10주문과 snapshot 상세·기존 등급 계산을 공개 DTO로 반환한다.
  hash/epoch를 응답에 노출하지 않으며 타인/관리자 경계는 원래 AccessGuard를 따른다.
- 관리자 생략 필드는 UserService의 full FOR UPDATE 뒤 현재 값을 사용한다. 비밀번호를 명시한
  경우에만 자격을 회전하고 관리자 본인 수정은 해당 변경의 epoch만 현재 세션에 반영한다.
  강등·삭제·동일 ID 재가입·다른 세션의 비밀번호 변경은 다음 보호 요청에 반영한다.
- 삭제의 사용자 잠금·자유 메모 정리·계정 삭제와 FK cascade/주문 참조 null은 한 TX다.
  이미 저장한 상품/금액 상세를 새 계정에 연결하지 않는다. 기존 runtime 권한으로 실행하며
  업무 테이블/sequence/권한을 새로 추가하지 않는다. 취향/찜 cascade 검증은 해당 API 제공과 구분한다.

## 검증 경계

조정자가 소유한 PG18.6/TLS 정상/실패 두 DB와 별도 runtime 역할을 사용한다. 새 자체 계정/
상품/주문만 만들고 원본 행이나 epoch/UUID/ACK를 바꾸지 않는다. 실패 DDL은 별도 failure DB의
정확한 자체 사용자 한 명에만 적용하고 finally 제거한다. 불변 version/event/delivery는 삭제하지
않으며 소유 폐기 DB의 최종 정리에 맡긴다. 원문/자격/실행 로그는 ignored에만 보관한다.

새 계정 HTTP/동시성/실패와 기존 전체 회귀, 고정 JAR의 등록 경로·재시작/재로그인·프로필 보존·
비밀번호 회전·삭제/재가입을 확인한다. 실제 결과와 독립 MySQL 대조·웹 흐름은 아래에 구분한다.
DB 없는 단위 성공을 실제 PG 성공으로 합산하지 않는다. 최초 모듈 Java17 단위3/패키징은 통과했고
준비되지 않은 DB 통합34 skip은 완료로 세지 않는다.

기본 Java17/Python3.12 `./scripts/check.sh`는 도구86·루트 Java140·이관 도구 단위18·JPA 단위3과
패키징을 통과했다. 실제 PG 첫 실행에서는 기존25와 새 계정6조건이 통과했으나, 삭제 검증 두
fixture의 주문 메모가21자로 기존20자 상한에 걸려400이었다. 이 준비 입력을13자로 바로잡았고
runtime/공유 검증 계약은 변경하지 않았다. 이를 제품 결함이나 개선 수치로 세지 않는다.

수정한 같은 runtime의 실제 정상/실패 DB 전체33 통합(기존25·계정 정상6/실패2)과 단위3이
통과했다. 이미 준비한 fixture1 skip은 성공 개수에 넣지 않았다. 소유권·현재 역할·hash/epoch
보존/회전과 본인/다른 세션, 최근10주문·등급·삭제 이력·동일 ID 재가입을 실제 HTTP/DB로 확인했다.
동시 두 부분 수정과 주문/이름 수정에서도 생략한 자격/합계를 복원하지 않았다. AFTER UPDATE와
AFTER DELETE 실패는 이전 hash/epoch/세션·메모·주문 참조/상세·스탬프·취향/찜까지 rollback됐다.

같은 고정 JAR의 packaged smoke는25경로/28작업, 가입/중복/ID 확인·프로필/등급·관리자 수정/목록·
비밀번호 회전·재시작 후 재로그인/프로필 보존·삭제/재가입 후 구쿠키401/새 프로필 초기 상태를
통과했다. 기존 상품/특성·주문/매장과 기동 거절·소유 JVM 정리도 유지했다. 검증 JAR SHA256은
`d8c5699c02e83a13a0d5cef7b0b102c9c6395f7d869d5bd8165cec2aa27c7f3b`다. 실행 archive에는
기본 애플리케이션/얇은 인증 Controller나 MyBatis/MySQL/H2 runtime이 없으며 원래 UserRestController와
실제 JPA provider를 포함한다. 기본 API/POM과 schema V1/V2는 변경하지 않았다.

조정자의 독립 대조는 동결한 기존 MySQL 실행 JAR과 이 JPA JAR에 같은 복사/자체 계정을 사용했다.
가입/ID 확인·순차 중복400·프로필/등급과 현재 스탬프63·생략 필드 수정·두 쿠키의 비밀번호 회전·
구 비밀번호 거절/새 로그인·역할 승격/강등 즉시 반영·삭제/재가입의 구 epoch401/새 프로필0·
관리자 본인 삭제/강등409·목록/비공개 필드를 포함한38 HTTP의 status와 전체 JSON이38/38 같았다.
새 주문을 만들지 않아 신규 시각 차이를 제외할 필요가 없었으며 값 정규화도 하지 않았다.
입력 명세 SHA256은 `7abb27553967ba4cd9f2a64d4812b00fc6152e72d24db9173b2e426831bc3d82`다.
이 조건의 동등성은 모든 Unicode/MySQL collation·전체 업무/웹 인수나 성능의 증명이 아니다.

조정자의 선택한 실제 웹 흐름은 동결한 React production build와 이 JPA JAR을 별도 복사 PG에
연결했다. 자체 상품의 단가7,777원·수량2에서 주문201/ORDER_SNAPSHOT 15,554원·장바구니 비움,
회원 profile GET200의 현재 스탬프2/씨앗 등급과 실제 프로필 화면의 스탬프/주문 카드가 일치했다.
관리자 화면의 완료 PATCH200/Y·삭제200·목록 제거/GET404와 자체 상품 정리도 성공했다.
API mock·예상외 API 오류·pageerror는 없었다. 정확한 자체 상품 comments GET404만 미제공 경계로
별도 기록했다. 최초 private harness 경로 오타는 브라우저/변이 전 준비 실패였으며 경로만 고쳐
같은 소스/JAR/build로 실행했다. 이 준비 오류도 제품 결함으로 계산하지 않는다.

이 결과는 선택한 주문/프로필·관리자 흐름의 실제 인수이며 전체 FE03/C04·후기/찜/취향 제공,
전체 고객 시나리오나 서비스 지연/처리량 개선을 의미하지 않는다.

README의 네 기존 사례를 비교해 보존한다. 이번 단위는 기존 세션/삭제 이력의 실제 PG 재인수이며
별도의 과거 제품 결함·운영 장애율·성능 개선으로 만들지 않는다. 아직 더 강한 독립 영향 근거가 없다.
후기/comments·취향/찜/추천 API·Psycopg3 relay·전체 웹/DB03–06·기본 실행 cutover는 후속이다.
필수 CI·최신 완료 Codex·미해결0·병합/main CI를 별도 최종 게이트로 유지한다.
