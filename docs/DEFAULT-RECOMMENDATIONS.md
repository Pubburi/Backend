# 공개 기본 주류 목록

`GET /api/recommendations/default?size=4`는 비로그인도 사용할 수 있는 **비개인 기본 목록**이다.
명시취향·찜·사용자 ID·세션·행동 로그를 추천 서버로 보내지 않는다. 학습/취향 점수·인기 성과나
추천 품질을 증명하는 API가 아니다. [부분 OpenAPI](openapi/default-recommendations.json)와
실행 `/v3/api-docs`가 제공자 계약이다.

성공은 ApiResponse의 data에 다음 두 필드만 둔다.

~~~json
{"items":[{"id":1,"name":"상품","type":"탁주","price":1000,"img":"image.png","abv":5,"orderCount":0}],"source":"BASELINE"}
~~~

- items는 기존 ProductResponse 일곱 필드이며 현재 DB에서 읽은 상품이다. size는 정수1..12,
  기본4이며 잘못된 값은400 BAD_REQUEST다. 후보 부족 시 실제 행 수만 반환한다.
- source는 BASELINE 또는 FALLBACK이다. 후자는 원격 실패/미기동/비정상 계약이나 선택 상품의
  삭제 뒤 현재 DB 기본순을 사용했다는 뜻이며 추천 품질 점수·개인화 수준이 아니다.
- 처음부터 DB가 비면 원격을 호출하지 않고 FALLBACK과 빈 items를 반환한다. DB 읽기 실패는
  500이며 빈 성공으로 숨기지 않는다. 이 GET은 DB/주문수·스탬프·취향을 수정하지 않는다.
- 사용자 선택자·임의 upstream URL은 요청으로 받지 않는다. URL/연결 정책은 운영자 설정만 사용한다.

## 후보·현재 데이터와 서비스 경계

현재 상품의 **첫 1,000 ID 오름차순**을 후보로 제한한다. 전체 상품의 인기/학습 순위나 취향
상위 후보라고 표현하지 않는다. Recommendation의 [B0 정본](https://github.com/Pubburi/Recommendation/blob/main/docs/BASELINE-API.md)
POST `/v1/recommendations/baseline`에 catalog·빈 constraints·limit을 보낸다. 각 catalog 항목은
product_id와 available=true만 투영한다. 이름/이미지·가격/도수·개인정보를 보내지 않으며 선택적
category/price_krw/abv_percent는 공급자 기본 null을 따른다.

현재 업무 스키마에는 판매 상태/재고 필드가 없다. 이 투영의 available=true는 **후보를 읽을 때
현재 상품 행이 존재함**만 뜻하며 매진/판매 가능성 검증을 완료한 것이 아니다.

원격 b0-id-ascending-v1·eligible_count·정확한 ID 순서/범위/중복·두 이유·응답 필드를 검증한다.
이유는 해당 B0 계약의 available/deterministic_baseline이며 화면 응답에 추가하지 않는다. JSON
중복 키·추가 루트 값·잘못된 타입/본문도 거절한다. 점수/모델을 Backend에서 새로 구현하지 않는다.

원격 호출 뒤 선택 ID의 현재 상품을 **배치 재조회**해 최신 가격/정보를 조립한다. 그 사이 선택한
행이 사라졌으면 일부 오래된 결과를 섞지 않고 현재 DB ID순의 FALLBACK으로 다시 읽는다. DB
트랜잭션을 원격 대기 동안 유지하지 않는다. 최신이라는 뜻은 마지막 DB 조회 시점이며 응답 직후
변경까지 막는 잠금/스냅샷 보증은 아니다. 주문 금액은 별도 주문 시점 스냅샷 계약을 유지한다.

## 기동·제한·오류

기본 upstream은 `http://127.0.0.1:8000`이고 운영자 환경변수
`PUBBURI_RECOMMENDATION_BASE_URL`로 HTTP(S) origin만 설정한다. userinfo/query/fragment/별도
path는 허용하지 않으며 실제 API path는 고정한다. 추천 서버와 AWS·외부 AI 키가 없어도
Backend는 기동하고 현재 DB 대체 목록을 제공한다. 포트를 바꾸면 이 변수도 같은 origin으로 맞춘다.

연결 상한은200ms, 원격 응답의 **전체 본문 완료 대기**는700ms다. 부분 JSON/느린 chunk에도
헤더만 받은 뒤 무기한 read하지 않는다. 애플리케이션에 누적하는 본문은32KiB(32,768B)로 제한하고 초과/비JSON/비200·
redirect는 거절한다. retry나 redirect 추적을 하지 않는다. timeout/중단/초과에는 자신이 만든
HTTP future와 body subscription에 취소를 요청한다. [JDK17 문서](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html)는
취소/리소스 해제가 비동기적으로 반영될 수 있음을 명시한다. 취소 즉시 OS 자원이 모두 회수된다는
보장이나 전체 API/DB 응답 시간이700ms라는 성능 주장은 하지 않는다.
이 누적 상한은 전송 계층이 취소 전에 읽은 전체 네트워크 바이트나 JDK 내부 버퍼의 크기를
측정한 값은 아니다.

원격 실패는 정상 FALLBACK 경로이며 원문 오류·URL·응답 본문을 공개 API에 노출하지 않는다.
기존 ApiResponse·400/406·DB 오류500과 ProductResponse를 유지한다. 설치/검증은
[TESTING](TESTING.md)과 [작업 근거](plans/2026-11-01-default-recommendation-integration.md)를 따른다.
