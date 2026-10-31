# P0 embedded Tomcat multipart 보안 패치

기준 main `b1997ac`, 작업 `codex/tomcat-security-patch`다. [의존성 점검](../DEPENDENCY-SECURITY.md)의
공식 공지/현재 조건과 실제 Maven runtime tree를 확인해 Tomcat multipart 처리의 좁은 패치를 선택했다.
Java 17과 기존 API·세션·MyBatis/SQL·실행 OpenAPI/Swagger를 유지한다. 전체 P0 보안 완료가 아니다.

## 원인·대안·선택

Boot 3.5.0이 해석한 core/EL/WebSocket은 모두 Tomcat 10.1.41이었다. CVE-2025-48988/48976의
part 수/헤더 제한 이전 버전이며 기본 multipart resolver가 활성이다. 실제 업로드 화면은 없지만
컨테이너 파싱과 업무 컨트롤러 계약을 구분한다. 대량 악성 입력/메모리 고갈을 실행하지 않았다.

가장 이른 수정인 10.1.42에는 이후 누적 수정이 없다. 확인 시점 공개된 동일 Servlet 6.0 계열의
10.1.61을 tomcat.version으로 고정해 세 모듈을 함께 바꾼다. Boot/BOM 전체/major 교체는 이번
직접 경계보다 범위가 커 별도 검토한다. 나머지 runtime artifact 버전은 전후 같고 SQL 변경은 없다.
springdoc 2.8.9/PathPatternParser도 유지해 기존 matcher 기동 실패를 우회하지 않았다.

## 고정 입력 재현·실제 결과

새 MultipartParserSecurityTests는 실제 Tomcat MultipartStream에 두 입력을 전달한다.
별도 포트·DB 없이 같은 Java 17과 바이트 입력·거부 기대 조건으로 전후를 비교했다.

| 입력 | Tomcat 10.1.41 | Tomcat 10.1.61 |
|---|---|---|
| 정상 Content-Disposition header | 원문 그대로 읽음 | 원문 그대로 읽음 |
| 동일 header + X-Fixture 2,048자, 총 2,111 ASCII byte | 상한 거부 기대와 달리 읽음 | FileUploadIOException의 원인 SizeLimitExceededException, permittedSize=512로 거부 |

이는 두 고정 라이브러리 파싱 입력의 결과이며 실제 HTTP의 전후 오류율·메모리 절약·처리량/
지연/운영 공격 차단률이 아니다. 소량 header 하나로 실제 DoS 성공이나 전체 보안 완료를 주장하지 않는다.

재현은 `./mvnw --batch-mode --no-transfer-progress -Dtest=MultipartParserSecurityTests test`다.
기준 소스의 기존 POM에서 거부 회귀가 1실패/정상 대조는 통과했다. 첫 변경 뒤에는 실제 거부가
동작했으나 wrapper 예외의 message가 null이라 테스트의 message 검사만 실패했다. 원인 예외의
타입/실제 permittedSize를 검사하도록 바꾸고 다시 검증했다. 이 중간 assertion 실패를 제품 오류
개선 분모에 합산하지 않는다. 최초 dependency-tree 실행의 Maven 캐시 쓰기 sandbox 제약도
승인된 실행 범위에서 재실행한 도구 제약이며 제품 실패가 아니다.

최종 `./mvnw --batch-mode --no-transfer-progress verify`는 Java 단위/MockMvc·실제 MVC 문서
91개와 패키징을 통과했고 Python 도구/안전 게이트 27개도 통과했다. 다음 pinned 플러그인으로
runtime tree를 확보하고 새 jar BOOT-INF/lib에서 세 Tomcat 모듈의 동일 10.1.61을 확인했다.

~~~bash
./mvnw --batch-mode --no-transfer-progress \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree -Dscope=runtime
~~~

첫 패치 jar `782a026871bb6b091b359e7c75bf494c2de31b404abbcfa1f924a6d7a8a6beb6`의 실제 HTTP는
상한을 거부했지만 multipart 예외를 catch-all이 500으로 처리했다. 기준 jar의 같은 두 로그인 요청은
정상 87B body/추가 header 2,048B의 2,146B body 모두 415였고, 첫 패치에서는 각각 415/500이었다.
로그의 실제 SizeLimitExceededException과 OpenAPI 32개 경로를 확인했다. 단위의 2,111B header와
이 HTTP body는 서로 다른 입력 집합이며 혼합 분모로 집계하지 않는다.

이를 최종 성공으로 쓰지 않고 일반 MultipartException은 고정 JSON 400·BAD_MULTIPART_REQUEST,
MaxUploadSizeExceededException은 별도 413·PAYLOAD_TOO_LARGE로 처리했다. 원인/파일경로를
응답에 복사하지 않으며 Accept 불일치에도 JSON envelope를 유지한다. 정상 미지원 미디어의
415는 바꾸지 않았다. 새 MockMvc 세 경계와 폐기 환경 필수 smoke의 소량 415/413·고정 메시지
검사를 추가했다. 광범위한 라우팅/보안 정책 변경은 없다.

최종 jar의 실제 header 초과는 Spring이 MaxUploadSizeExceededException으로 래핑해 413이었다.
일반 MultipartException의 400 단위 검증과 구분한다. 첫 smoke는 이 실제 경계를 일반 400으로
잘못 기대해 실패했으며 예외를 무시하지 않고 실제 하위 타입/고정 메시지에 맞춰 413으로 정정했다.
애플리케이션 handler·jar는 바꾸지 않았다.

최종 불변 jar SHA-256: `ae6e0d1e2bd668be9bcb76ab7a17840dc5d3f63d942a68a0082462e041ba8156`.
조정자가 같은 jar의 전용 MySQL 8.4에서 최종 실제 HTTP와 전체 smoke를 통과했다.

| 실제 HTTP 고정 입력 | 기준 10.1.41 | 최종 10.1.61 + 예외 분류 |
|---|---|---|
| 정상 87B multipart body | 415·UNSUPPORTED_MEDIA_TYPE | 같은 415·고정 메시지 유지 |
| 추가 header 2,048B의 2,146B multipart body | 415·UNSUPPORTED_MEDIA_TYPE | 413·PAYLOAD_TOO_LARGE·고정 JSON |
| 올바른 실행 OpenAPI 경로 | 32개 path | 32개 path·실행 문서/Swagger 유지 |

최종 header 크기 예외 메시지는 `Multipart request is too large`이며 원인/경로를 포함하지 않는다.
중간 jar의 500을 이 413으로 분류했지만, 기준 버전의 415도 클라이언트 오류였으므로 전체 오류
감소율을 만들지 않는다. 파서의 제한 적용과 예외 분류를 별도 결과로 기록한다.

최종 `scripts/smoke.sh`의 아홉 범주는 기본 응답·문서와 새 multipart, 기존 세션/권한·주문 가격/
쓰기 후 실패 rollback·삭제 이력·계정 epoch·상품 특성·명시취향·찜을 검증했다. 첫 probe의 잘못된
`/api/docs` 경로는 검증 경로 오류로 제외했고 올바른 `/v3/api-docs`를 사용했다. 기존 개발/사용자
DB·공유 포트는 이 작업자가 사용하지 않았다. PR/병합 CI 검증은 이 로컬 결과와 별도다.

## 인계·사례 비교

현재 README 네 사례는 실제 권한 회수·업무 금액/이력 결함과 정규화/페이지 조립을 설명한다.
의존성 audit/소량 parser 보안 경계를 실제 사용자 침해 해결이나 성능 최적화로 포장해 교체할
이유가 없어 기존 네 항목/순서를 유지했다. 실제 버전 표기만 갱신하고 근거는 이 계획에 남긴다.

Framework/BOM·나머지 전이 라이브러리 보안/지원 수명과 외부 구성 변경은 후속 점검이다. 이번
Tomcat 변경만으로 모든 취약점이 해결됐다고 표시하지 않는다. 최종 PR의 최신 head CI·완료된
Codex 리뷰·구체적 미해결 지적 없음·보호 규칙과 병합/main CI는 조정자가 확인한다.
