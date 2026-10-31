# 계정 찜 API

현재 로그인한 계정만 자신의 찜을 저장·해제·조회한다. 관리자의 타인 조회 예외는 없다.
화면 연결 전의 백엔드 증가분이며 F01 전체 완료나 추천 모델·실제 인기의 근거가 아니다.
[부분 OpenAPI](openapi/account-favorites.json)와 실행 `/v3/api-docs`가 같은 응답 구조를 사용한다.

## HTTP 계약

| 요청 | 성공 data | 조건 |
|---|---|---|
| GET `/api/users/me/favorites?page=1&size=20` | 기존 PageResponse의 items/page/size/total/totalPages/hasNext | 본인의 현재 상품 페이지 |
| PUT `/api/users/me/favorites/{productId}` | `{"productId":1,"favorited":true}` | 관계를 존재하게 함. 없는 상품은 404 |
| DELETE `/api/users/me/favorites/{productId}` | `{"productId":1,"favorited":false}` | 관계를 없게 함. 없는 관계·삭제 상품도 200 |

세 경로는 JSON ApiResponse와 세션 쿠키를 사용한다. PUT/DELETE에는 요청 본문이 없다.
body/query의 userId/authEpoch로 소유자를 선택하지 않으며 타인 ID 경로도 제공하지 않는다.
비인증·삭제/회수된 계정 인스턴스는 401이다. 살아 있는 계정의 찜이 없을 때만 빈 페이지를 반환한다.
지원하지 않는 POST는 405·METHOD_NOT_ALLOWED와 Allow를 반환한다. 지원하지 않는 Accept는
406·NOT_ACCEPTABLE JSON 오류다. 잘못된 숫자/범위는 400, DB 실패는 500이며 성공으로 숨기지 않는다.

items는 기존 ProductResponse의 **id/name/type/price/img/abv/orderCount** 일곱 필드다.
현재 가격·이름·주문수이므로 주문 시점의 금액 스냅샷과 의미가 다르다. 찜 저장이 주문수나
스탬프를 올리지 않는다. account ID/epoch·찜 timestamp를 응답에 추가하지 않는다.

정렬은 created_at DESC, product_id DESC다. 같은 관계를 반복 PUT해도 최초 timestamp/순서를
유지한다. DELETE 뒤 다시 PUT하면 새 추가로 취급한다. page 기본 1·size 기본 20이며 page<1은 1,
size<1은 기본값, size>100은 100으로 정규화한다. page/size는 32비트 정수이며 offset이
32비트를 넘는 요청은 SQL 전 400이다. 범위를 벗어난 유효 페이지는 빈 items와 실제 total을 반환한다.
productId는 1..2147483647의 숫자 경로다.

## 저장·계정 수명

t_user_favorite의 기본키(user_id, product_id)가 중복 관계를 막는다. 회원·상품 외래키의 CASCADE는
삭제된 참조의 찜을 제거하며 상품/회원을 0이나 가짜 ID로 대체하지 않는다. 회원 삭제 후 같은 ID로
가입해도 예전 관계를 복구하지 않는다. 기존 주문 이력은 별도의 스냅샷/SET NULL 정책을 유지한다.

GET은 REPEATABLE_READ 트랜잭션에서 현재 id+authEpoch의 계정 존재·count·items를 확인한다.
empty와 만료 계정을 구분하며 두 목록 쿼리는 같은 읽기 스냅샷을 사용한다. PUT/DELETE는 먼저
사용자 행을 FOR UPDATE하고 epoch를 다시 비교한다. PUT만 상품 행을 잠그며 User→Product 순서로
기존 주문 잠금과 맞춘다. Guard 뒤 삭제/같은 ID 재생성을 거친 대기 요청은 새 계정에 쓰지 않는다.

PUT/DELETE는 원하는 상태를 명시하는 멱등 연산이다. 동시 요청은 DB 잠금으로 직렬화하지만
서로 반대인 PUT/DELETE의 최종 상태가 브라우저의 마지막 클릭 의도와 같다는 보장은 없다.
ETag/revision·클라이언트 요청 순서 보장은 별도 UI/연동 과제다. 전체 업무의 in-flight 취소나
자연 발생 부하·분산 세션·지연 개선을 이 설계만으로 완료했다고 주장하지 않는다.

## 기존 DB 수동 전환

[006 SQL](migrations/006-account-favorites.sql)은 001..005가 적용된 MySQL 8.4에서 새 빈 테이블만
생성한다. 기존 상품/계정·개인 취향을 추정하거나 채우지 않는다. 새 data.sql은 폐기/신규 DB 전용이며
기존 DB의 초기화에 쓰지 않는다. 애플리케이션 기동에서 마이그레이션을 자동 실행하지 않는다.

1. 백업·유지보수 창을 확보하고 쓰기를 중단한다. 현재 DB명과 t_user/t_product·FK·auth_epoch,
   t_user.id의 utf8mb4 문자셋/collation 및 현재 DB 기본 collation 일치를 확인한다.
2. 기존 모든 테이블의 행 수/전체 값·주문 총액을 안전한 비공개 위치에 기록한다.
3. 새 테이블이 없는지 확인하고 지정 DB에 `mysql --database=<검증한_DB> < docs/migrations/006-account-favorites.sql`
   을 한 번 실행한다. 비밀번호를 명령 인자/로그에 넣지 않는다. --force로 실패를 무시하지 않는다.
4. 기존 값 보존·새 테이블 0행·기본키/두 CASCADE/페이지 및 상품 인덱스를 확인한 뒤 앱을 배포한다.

DDL은 자동 멱등 전환이나 트랜잭션 rollback을 제공하지 않는다. 재실행의 table-exists 오류 뒤
자동 삭제/계속 실행을 하지 말고 실제 스키마를 확인한다. 배포 되돌리기는 구버전 앱부터 적용한다.
새 테이블 삭제는 사용자가 새로 저장한 찜을 잃으므로 실제 행/백업·승인을 확인한 별도 절차다.
기존 개발·사용자 볼륨에 폐기 테스트를 실행하지 않는다.

검증 방법과 실제 실행 결과는 [TESTING](TESTING.md)·[작업 기록](plans/2026-11-01-account-favorites.md)에 둔다.
