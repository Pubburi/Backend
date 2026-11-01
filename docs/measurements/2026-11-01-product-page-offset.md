# 상품 페이지 offset 정확성 검증

## 고정한 조건

- 기준 커밋: `cb9023c4104692a586c33b156c18622b2fee9eca`.
- 기준 불변 jar SHA-256: `3fbb61317289c8ea6036efa2243898a21faa5b1c21ed45deed66cd30e28be767`.
- 수정 불변 jar SHA-256: `ddd3ab6f70f2cb2b7d0b296ac85a1acd4f2f68934733babefe9df930211c9a65`.
- Java17 패키지·전용 폐기 MySQL8.4·기존 seed의 현재 상품 API를 사용한다. 공유 DB/서버와 외부
  사용자의 요청을 사용하지 않는다. 정규화된 size와 `(page - 1) * size`가 Java/mapper의 signed
  32-bit offset 계약에 들어가는지 검사한다. MySQL 자체의 OFFSET 최대값을 측정한 것이 아니다.
- 아래 공개 경로를 같은 조건으로 한 번씩 호출한다. 대기/처리량·CPU/메모리·캐시/워밍업·실제
  사용자 오류율 실험이 아니며 시간 개선 수치를 만들지 않는다.

## 전후 원값

| GET /api/products 쿼리 | 수학적 offset | 기준 HTTP | 기준 관찰 | 수정 HTTP |
|---|---:|---:|---|---|
| page=1&size=100 | 0 | 200 | 정상 첫 목록 | 200 |
| page=1&size=4 | 0 | 200 | 정상 첫 네 상품 | 200 |
| page=2147483647&size=100 | 214,748,364,600 | 500 | int -200, INTERNAL_SERVER_ERROR | 400 |
| page=1073741825&size=4 | 4,294,967,296 | 200 | int0, 잘못된 첫 네 상품 | 400 |
| page=42949674&size=100 | 4,294,967,300 | 200 | int4, 잘못된 중간 상품 | 400 |
| page=21474837&size=100 | 2,147,483,600 | 200 | 지원 범위 안의 빈 목록 | 200 |
| page=21474838&size=100 | 2,147,483,700 | 500 | 지원 범위 초과·음수 int | 400 |
| page=2147483647&size=1 | 2,147,483,646 | 200 | 지원 범위 안의 빈 목록 | 200 |

네 미지원 조건은 각각 한 번씩 요청했고, 기준의 계약 위반은4/4다. 이 중 서버500은2/4이고
잘못된 페이지200은2/4다. 200인 잘못된 결과를 성공으로 세거나 모든 HTTP 오류를 같은 실패율로
합산하지 않는다. 같은 여덟 조건의 재측정에서 계약 위반은4/4→0/4였다. 서버500은2/4→0/4,
잘못된 페이지200은2/4→0/4이며 지원되는 네 조건의200은 유지됐다. 의도된400은 잘못된 요청의
거절이지 모든 HTTP 오류가 사라졌다는 의미가 아니다.

## 원인·수정·재현

기존 `(page - 1) * size`가 int 안에서 계산돼 SQL 전에 유실됐다. 음수 검사만 추가하면 0이나
양수로 래핑된 요청은 계속 다른 페이지를 읽는다. 상품 서비스에서 다음처럼 계산/지원 범위를
확인하고 검증한 값만 전달한다.

~~~java
long offset = ((long) criteria.page() - 1) * criteria.size();
if (offset < 0 || offset > Integer.MAX_VALUE)
    throw new IllegalArgumentException("Product page offset exceeds the supported range");
~~~

실제 [상품 서비스](../../src/main/java/com/pubburi/pub/model/service/ProductServiceImpl.java)의
DAO 호출은 `(int) offset`을 사용한다. PageCriteria와 다른 API의 정책을 전역 변경하지 않는다.
소스 probe의 산술 결과와 MockMvc/fake DAO의 호출 수는 실제 MySQL의 오류·성능 측정과 구분한다.

새 회귀의13조건을 수정 전에 실행해 미지원 네 조건의400 기대가 실패하고 정상 아홉 조건은
통과함을 확인했다. fake DAO는 빈 정상 응답을 반환하므로 이때의200을 위 실제 SQL500/잘못된
페이지 관찰로 대신하지 않는다. 수정 후 회귀는 미지원 조건의 상품 DAO 호출0과 기존 정상화/
필터/정렬/메타 보존을 검사한다.

재현 명령은 Java17에서 `./scripts/check.sh`, 같은 폐기 DB marker를 준비한 뒤
`PUBBURI_SMOKE_MUTATING=1 ./scripts/smoke.sh`다. 원값의 HTTP 조건은 위 쿼리 표를 사용한다.
필수 smoke는 네 미지원 조건과0/음수·size clamp를 검사하고 기존 필터/거래/권한 검증도 유지한다.
DAO 호출0은 위 fixture에서 확인한 상품 조회 경계이며 실제 트랜잭션의 연결/관리 SQL 수를 측정한 값은 아니다.
같은 수정 jar·전용 MySQL의 전체 HTTP smoke13범주도 통과했다. 기존 필터·권한/세션·거래/
rollback·삭제 이력·특성·취향·찜·기본 목록과 실행 OpenAPI/Swagger를 함께 확인했다. 이 범주 수는
서비스 성능 개선 수치가 아니다. 원문 로그·개인 경로·자격정보는 공개 근거에 포함하지 않는다.

## 판단과 한계

DAO offset을 long으로 확장하거나 임의의 페이지 상한으로 clamp하는 대안 대신 현재 int 계약을
유지하고 범위를 벗어난 요청을 명시적으로 거절한다. 이는 SQL/스키마나 전체 페이지 정책 확장이
아닌 상품 목록의 범위 검증이다. 지원되는 높은 페이지를 임의로 첫/마지막 페이지로 바꾸지 않는다.
API의 모든 정수 바인딩·모든 다른 목록 API를 고쳤다고 주장하지 않는다. 검증된400과 실제 DB
장애500을 구분하며 페이지 크기/연속 요청의 전체 부하·자원 제한은 별도다.
