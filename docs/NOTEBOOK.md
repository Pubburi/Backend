# 본인 비공개 상품 기록

F07은 PostgreSQL 제공자에서 현재 본인의 기록만 저장한다. 공개 후기·추천 피드백과 별개이며
메모/태그/평점을 공개 API·추천 서버·검색 이벤트로 자동 전송하지 않는다. TRIED는 사용자가
직접 경험했다고 구분한 입력이지 구매/실사용 증명이나 추천 품질 성과가 아니다.

## API

모든 요청은 세션과 `X-Pubburi-Account-Owner: encodeURIComponent(현재 계정 ID)`를 요구한다.
헤더는 계정을 선택하지 않는다. 미로그인/만료 epoch/관측 계정 불일치는401, 헤더 누락·중복·
비canonical 인코딩은400이다. 관리자도 다른 사람의 UUID 기록을 조회/수정/삭제할 수 없다.
모든 성공·오류는 `Cache-Control: private, no-store`, `Vary: Cookie`다.

| 메서드 | 경로 | 동작 |
|---|---|---|
| GET | `/api/users/me/notebook` | `page`(기본1), `size`(기본20·최대100), 선택 `experience`로 본인 목록 |
| POST | `/api/users/me/notebook` | 상품당 본인 기록 하나 생성·201. 중복409·없는 상품404 |
| GET | `/api/users/me/notebook/by-product/{productId}` | 현재 상품의 본인 기록 또는 `data:null` |
| GET | `/api/users/me/notebook/{id}` | UUID 기록. 없거나 타인 기록이면404 |
| PUT | `/api/users/me/notebook/{id}` | 내용 전체 교체. `expectedVersion` 필수·불일치409 |
| DELETE | `/api/users/me/notebook/{id}?expectedVersion=n` | 본인 UUID 기록 삭제·`data:null`. 기대 revision 불일치409 |

목록은 기존 `PageResponse`의 `items/page/size/total/totalPages/hasNext`이며 수정 시각 내림차순·
UUID 오름차순이다. page/size는 범위 내 정수만 허용하고 중복/추가 query를 거절한다.
POST body는 `productId,experience,rating,tasteTags,aromaTags,memo`를 모두 가진다.
PUT는 productId 대신 `expectedVersion`을 가지며 생략/추가 필드·타입 강제 변환은 허용하지 않는다.

- `experience`: `TRIED` 또는 `INTERESTED`.
- `rating`: TRIED의 선택 숫자0–5, 소수 둘째 자리까지. 미평가는 null이다.
- `tasteTags`, `aromaTags`: 각각 null(unknown) 또는 최대20개 문자열 배열. []는 그대로 보존한다.
  각 원소는40 Unicode code point 이하이며 순서·대소문자·앞뒤공백을 보존한다. 쉼표/개행을
  구분자로 나누거나 trim/fold/NFC 정규화를 하지 않는다. 빈/blank-only 원소·정확 중복은400이다.
- `memo`: null 또는2000 code point 이하 문자열. 빈 문자열·공백·개행은 그대로 보존한다.
- INTERESTED는 rating/tasteTags/aromaTags가 모두 null이어야 한다. 관심을 경험 평점으로 쓰지 않는다.
- 모든 문자열의 NUL·단독 UTF-16 surrogate는400이다. 정수/숫자 대신 문자열·boolean은 거절한다.
- `expectedVersion`: 0–9007199254740991 범위 정수. DELETE query도 필수 단일 canonical 정수다.

entry는 `id,productId,productName,currentProduct,experience,rating,tasteTags,aromaTags,memo,version,
createdAt,updatedAt`다. id는 서버 UUID이며 createdAt/updatedAt는 UTC epoch milliseconds다.
productName은 생성 당시 이름, currentProduct는 같은 읽기 snapshot의 현재 Product7 또는 null이다.
version은 **기록 행의 revision만** 나타내며 별도로 변경되는 상품 DTO의 ETag가 아니다.
If-Match/ETag는 사용하지 않는다. 오래된 입력은409 후 최신 기록을 읽어 명시적으로 다시 저장한다.

## 수명·영속 경계

회원/epoch를 다시 확인하고 쓰기는 회원→해당 행 순서로 잠근다. 생성 시 현재 상품도 잠그며
상품당 중복과 같은 revision의 동시 변경을 직렬화한다. 페이지와 현재 상품은 짧은 RR과 배치
조회로 읽고 반환 전 세션을 다시 확인한다. 데이터베이스 실패를 빈 목록/성공으로 바꾸지 않는다.

상품 삭제는 productId를 null로 바꾸고 UUID·이름 snapshot·개인 원문·revision을 보존한다.
삭제된 상품 기록도 UUID로 편집/삭제할 수 있다. 상품 삭제/이름·가격 변경은 note revision을
올리지 않는다. 회원 삭제는 모든 본인 기록을 CASCADE하며 같은 ID 재가입에는 상속되지 않는다.

Flyway V5의 `t_user_notebook`은 UUID PK 한 표이며 새 sequence를 만들지 않는다. runtime은
SELECT/INSERT/DELETE와 UPDATE(experience,rating,taste_tags,aroma_tags,memo,version,updated_at)만
받으며 key/owner/product/name snapshot/created_at 변경·DDL·TRIGGER·TRUNCATE는 허용하지 않는다. MySQL 원본과
v1/v2 source artifact를 바꾸지 않는다. MySQL baseline은 명시404이며 가짜 저장을 제공하지 않는다.
source import/초기 cutover는 이 PG-only 표가 비어 있어야 한다. PG 쓰기 뒤에는 V5의 전체
기록을 포함한 [PG 백업·복구](LOCAL-DEVELOPMENT.md)를 사용한다. 기존 V3/V4 백업은 그 원형대로
복구하고 새 runtime을 쓰기 전에 명시 V5 migration·최소 grant를 적용한다. 자동 DDL/원본 초기화·
MySQL 역이관을 제공하지 않는다. 실제 개인 원문/DB snapshot/통합 로그는 공개 artifact에 넣지 않는다.
