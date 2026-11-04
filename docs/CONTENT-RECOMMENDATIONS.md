# 본인 명시취향의 검수 DEMO B1 추천

`POST /api/users/me/recommendations/content`는 로그인한 **현재 본인**의 canonical 저장 취향을
기존 [B1 content](https://github.com/Pubburi/Recommendation/blob/main/docs/CONTENT-API.md)에 연결한다.
요청 JSON은 `{"limit":4}`이며 생략 기본4·정수1–12다. 다른 필드와 모든 query를400으로 거절한다.
계정/역할/epoch·profile/preferences·catalog/제외/host는 요청에서 선택하지 않는다.

성공 ApiResponse data에는 source(PROVIDER/UNAVAILABLE), catalogScope(REVIEWED_DEMO),
result, products 네 필드가 있다. PROVIDER의 result는 검증한 기존 B1/R04 typed 응답이며
products는 result.items와 같은 ID순의 **현재 Product7**이다. UNAVAILABLE은 result=null과
products=[]다. 제공자의 정상 빈 items/fallback은 PROVIDER로 보존하며 장애와 구분한다.
모든 응답은 private, no-store·Vary Cookie다. 점수는 만족 확률/품질 성과가 아니며 결과를 저장하지 않는다.

## 검수 catalog와 현재 업무 값

운영자는 Recommendation의 [공식 checked bundle](https://github.com/Pubburi/Recommendation/blob/main/docs/BACKEND-CATALOG-BUNDLE.md)을
검증/발행한 뒤 `PUBBURI_CONTENT_CATALOG_PATH`, `PUBBURI_CONTENT_CATALOG_SHA256`으로 고정한다.
기본 빈 설정은 일반 API/B0/R06의 기동을 유지하고 이 경로만 UNAVAILABLE로 처리한다.
전체 바이트 pin·정확 shape/명시 ID·그룹·원 공개 응답 binding과 유효기한을 확인한다. 원 권위/
매핑 검증과 모델 fit은 Recommendation 소유이며 Java가 Python의 authority loader를 복제하지 않는다.

이번 소비 범위는 container DEMO(`reviewed_mapped_business_demo_only`/`public_browse_row_exists`)뿐이다.
별도 family-browse 권위는 거절한다. authored 원천은 demo_fixture/owned_demo_fixture, 공개 검수
원천은 existing_local_catalog origin의 정확한 짝과 catalog/records의 ID순서를 요구한다.
공식 export의 source taste/aroma=null을 유지하고 DB 태그를 모델 사실로 승격하지 않는다.
bundle은 일반 서비스 catalog 승인이 아니다. source11/업무 DEMO/상품군 browse의 권위 구분을
유지하고 기존80을 이름/순서로 자동 매핑/fit하지 않는다. sourceName/URL·사용자 취향은 권리 승인이
아니다. available도 판매/재고 검증이 아니라 원래 명시 browse 조건이다. 기록된 원 응답은 Java에서
다른 숫자 직렬화로 다시 해시하지 않고 parsed public 필드를 semantic exact로 대조한다.
null/[]·배열 순서·문자 atom은 유지하고 유한 숫자는 수치가 같아야 한다.

한 짧은 RR에서 현재 본인 취향·mapped 상품/특성·태그를 배치로 읽는다. 현재 공개 필드가 원 승인
binding과 다르면409이고 제공자에 보내지 않는다. 원격 대기 뒤 같은 RR 읽기를 다시 수행해
취향/상품/특성 변화·삭제도409로 거절한다. 미설정/핀 불일치/만료/historical은 전송하지 않는다.
유효기한은 원 business capture 종료+5분이며 새 current bundle 발행은 원 소유자의 검증을 요구한다.

## 모델·설명과 실패 경계

원래 catalog와 현재 전체 preferences, constraints={}, limit, include_explanations=true만
고정 upstream `/v1/recommendations/content`에 보낸다. 계정 ID·해시/epoch·cookie·인증 헤더·
주문/찜/행동 기록은 보내지 않는다. soft 취향은 hard 조건으로 승격하지 않고 모든 저장 hard를
유지한다. Backend는 모델/랭킹/대체 문구를 만들지 않는다.

응답의 후보 ID/중복/수·현재 필수조건/available와 고정 제외 없음·진단/기여 합·R04 profile 출처·
실제 명시 사실/단위/상위3/생략 수/fallback을 확인한다. 가격/임의 이름을 점수 근거로 받거나
잘못된 reference/없는 atom을 사용자 취향으로 표시하지 않는다. 점수 함수를 재계산하는 검증은 아니다.

연결200ms·원격 전체3초, 누적 요청512KiB/응답256KiB·strict 중복/추가 JSON·redirect 거부를
적용한다. 재시도하지 않으며 완료하지 않은 future/body subscription의 취소를 요청한다.
이 예산은 DB/전체 API 시간의 성능 보장이나 이미 실행 중인 제공자 CPU fit의 취소 보장이 아니다.
기존 공개 B0/R06의700ms는 그대로다. 원격/검증 실패에는 UNAVAILABLE이며 약한 B0 추천으로
개인화한 것처럼 응답하지 않는다. 공개 기본 목록은 별도 기존 경로로 사용할 수 있다.

입력400·비로그인/credential/삭제재가입/세션 교체401·의미 변화409·DB 예외500을 구분한다.
원문 파일/계정/응답 오류를 오류 메시지에 반사하지 않는다. 마지막 읽기 이후의 수정까지 잠그는
보장은 아니며 주문 가격은 원래 주문 시점 스냅샷을 사용한다.

[검증/한계](https://github.com/Pubburi/Backend/blob/6fda9d3c2fb42e7d118e15602e601389f92695dd/docs/plans/2026-11-04-owned-content-recommendations.md)를 따르며 전체 R02/R04·서비스
SKU 승인/학습 품질·기본 PostgreSQL cutover 완료로 확대하지 않는다.
