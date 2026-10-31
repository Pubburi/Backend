# 상품 특성 JSON 미디어 오류의 4xx 응답

기준 커밋 `85932e4b4042acfd3b86442e43331d9bc706bf93`의 상품 특성 증가분을 같은 브랜치에서
수정한다. 첫 feat 커밋을 보존하고 JSON 전용 계약에서 직접 관측한 오류 분류만 fix로 분리한다.
기존 기능·전역 matcher·라우팅 정책·업무 DB/마이그레이션은 변경하지 않는다.

## 재현과 원인

최초 기능·표준 smoke가 통과한 3cf… 불변 jar에서 정상 관리자 쿠키로
`PUT /api/admin/products/{id}/features`, `Content-Type: text/plain`, `Accept: application/json`,
본문 `{}`를 실제 HTTP 한 번 요청하자 500이었다. JSON consumes가 거부한
HttpMediaTypeNotSupportedException을 일반 Exception handler가 내부 오류로 처리했다.
이것은 고정 입력 N=1의 오류 분류 관측이며 운영 장애율·성능 수치가 아니다.

수정 전 MockMvc도 같은 PUT에서 500을 재현했다. 별도 GET의 `Accept: text/plain`은 406이지만
공통 오류 envelope가 없는 빈 본문이었다. GET의 상태를 500→406으로 고쳤다고 기록하지 않는다.
새 JSON media를 요구하면서 이 클라이언트 오류 경계를 기존 검증에 포함하지 못한 점을 보강한다.

## 최소 수정

- HttpMediaTypeNotSupportedException만 415/`UNSUPPORTED_MEDIA_TYPE`으로 처리한다.
- HttpMediaTypeNotAcceptableException만 406/`NOT_ACCEPTABLE`로 처리한다.
- 두 오류는 JSON Content-Type과 ApiResponse의 null data/message·오류 필드를 명시해 실패한
  Accept 협상이 오류 envelope 직렬화를 다시 막지 않게 한다. 요청 원문/인증값을 반사하지 않는다.
- 일반 내부 오류는 기존 500 경로를 유지한다. 전역 경로 matcher·상품/태그 저장 로직은 바꾸지 않는다.
- 필수 변이 smoke는 정상 관리자 세션의 text/plain PUT, text/plain Accept GET과 그 뒤 기존
  저장 특성 불변을 확인한다. 기존 요청 기본값은 그대로 두고 검증용 content_type/accept만 선택 가능하게 한다.

## 검증 상태

Java 17 전체 57개·Maven verify/package, Python 도구/안전 게이트 22개·구문·repository/diff 검증을 통과했다.
단위의 PUT은 415 공통 envelope와 서비스 호출 0, GET은 406 JSON envelope를 확인한다. 기존
full MVC의 JSON media·응답 8/요청 7필드·nullable·Swagger 검증도 생략하지 않는다.

변경 불변 jar SHA-256은 `50f6dde23f83154970710f6a1cd310e1ca0c3dc3a32718dda407ce93654a1a1a`다.
조정자가 새 jar의 실제 HTTP 미디어 오류와 전체 표준 smoke를 확인했다.
기존 기능 17개 invalid 입력·rollback·삭제/스냅샷과 새 미디어 경계는 별도 검증 범위다.
실제 추가 PUT은 415/`UNSUPPORTED_MEDIA_TYPE`, GET은 406/`NOT_ACCEPTABLE` JSON ApiResponse를
반환했다. 같은 최종 jar에서 기존 17개 invalid JSON 요청 400과 상태 보존·DB 쓰기 실패 rollback·
삭제 후 특성/태그 0행과 과거 주문 9,600원 스냅샷·OpenAPI 200도 다시 확인했다. 필수 Python 변이
smoke에서도 두 헤더 경계를 통과했다. 기존 사용자 DB·004 SQL은 변경하지 않았다.

처음 실제 PUT의 500 한 번과 수정 후 415는 동일 고정 요청의 오류 분류 근거다. GET의 before는
단위에서 관측한 406 빈 본문이고 after는 실제 406 JSON이며, 서로 다른 검증 층을 숨겨 합산하지
않는다. PR 최신 head CI/리뷰·병합/main CI는 조정자 게이트로 남긴다.

## 범위와 사례 비교

상품 특성 신규 기능 전체나 F03 화면/데이터 감사·추천 모델 완료로 확대하지 않는다. API 클라이언트
오류 분류와 envelope의 작은 결함 수정이며 현재 네 README 사례보다 더 강한 업무 문제 해결이라는
근거는 없다. README 사례/순서를 유지하고 실제 성능/사고 감소율도 만들지 않는다.
