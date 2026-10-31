# 실행 의존성 보안 점검

2026-11-01에 기준 `b1997ac`의 실제 Maven runtime tree와 공식 공지를 대조했다. 이번 변경은
embedded Tomcat의 multipart 처리에 한정한다. 전체 의존성의 취약점 0건·전체 P0 보안 완료나
실제 운영 DoS/침해를 복구한 기록이 아니다. 조사·조건 판정·실행 검증을 구분한다.

## 이번 패치와 실제 버전

| 실행 구성 | 이전 | 변경 후 |
|---|---|---|
| tomcat-embed-core / el / websocket | 각각 10.1.41 | 각각 10.1.61 |
| Spring Boot / Framework | 3.5.0 / 6.2.7 | 유지 |
| spring-security-crypto | 6.5.0 | 유지 |
| MyBatis starter / MyBatis | 3.0.3 / 3.5.14 | 유지 |
| springdoc / Swagger UI | 2.8.9 / 5.21.0 | 유지 |
| Jackson databind / MySQL Connector/J | 2.19.0 / 9.2.0 | 유지 |

pom의 `tomcat.version` 한 속성으로 core/EL/WebSocket을 같은 패치에 정렬했다. 트리뿐 아니라
실행 jar의 BOOT-INF/lib에서도 세 10.1.61 모듈을 확인했다. 직접 의존성의 개별 버전을 서로 다르게
덮어쓰지 않았다. Java 17·DB 스키마·기존 API/세션과 PathPatternParser는 유지한다.

[Apache 보안 공지](https://tomcat.apache.org/security-10.html)의 CVE-2025-48988과 CVE-2025-48976은
10.1.41까지의 multipart part 수/헤더 메모리 제한 문제다. 최초 수정은 10.1.42이지만 그 버전이
현재의 누적 보안 수정 전체를 포함하지는 않는다. 이번에는 확인 시점에 공개된 같은 10.1 계열의
10.1.61을 선택했다. [현재 Connector 문서](https://tomcat.apache.org/tomcat-10.1-doc/config/http.html)의
part header 기본 상한은 512B다. 대량 메모리 고갈이나 악성 트래픽 부하는 실행하지 않았다.

업무 API는 JSON을 사용하고 업로드 기능은 없지만 Spring Boot의 기본 multipart resolver는
활성 상태다. 따라서 컨트롤러가 업로드를 받지 않는다는 이유만으로 컨테이너 파싱 경계를 제외하지
않는다. [원본 보안 수정](https://github.com/apache/tomcat/commit/667ddd76e2a0e762f3a784d86f0d25e7fd7cdb86)은
고정 10KiB header 제한을 설정 가능한 512B 기본 제한으로 바꾸었다. 이 경계를 소량의 실제
라이브러리 입력으로 먼저 재현했다. 이것은 메모리 절약량/공격 성공률 측정이 아니다.

일반 multipart 파싱 예외는 고정 JSON 400·BAD_MULTIPART_REQUEST로 분류한다. Spring이 크기
초과 하위 타입으로 분류한 header/요청 제한은 413·PAYLOAD_TOO_LARGE로 유지하고 원인 메시지/경로를 노출하지 않는다.
정상 형태지만 JSON 업무 API가 지원하지 않는 multipart의 기존 415는 유지한다. 새 파서 제한을
catch-all의 내부 오류 500으로 숨기지 않도록 단위와 실제 HTTP 경계를 함께 검증한다.

## 호환성 선택과 미해결 범위

[Spring Boot 3.5 계열의 컨테이너 기준](https://docs.spring.io/spring-boot/3.5/system-requirements.html)은
Tomcat 10.1·Servlet 6.0을 지원한다. 다만 [의존성 재정의 안내](https://docs.spring.io/spring-boot/how-to/build.html#howto.build.customize-dependency-versions)는
기본 조합 외 버전의 호환성 위험을 명시한다. 문서의 계열 지원만으로 Boot 3.5.0+10.1.61의 성공을
추정하지 않고 Java 17 전체 Maven·실제 jar/DB·실행 OpenAPI/Swagger로 검증한다.

Boot BOM 전체를 올리는 대안은 다른 Spring/Jackson/로깅/DB 드라이버까지 동시에 바꾼다. 이번
직접 재현한 multipart 경계는 Tomcat으로 제한해 인과와 롤백 범위를 좁혔다. 이전 springdoc
2.8.17/2.10의 matcher 실패는 [기존 기록](plans/2026-11-01-product-feature-contract.md)을 따르며,
호환 검증된 2.8.9를 유지한다. 장기적으로 Boot/BOM과 나머지 의존성의 보안·지원 수명을 계속 확인해야 한다.

| 추가 확인한 공식 공지 | 현재 코드/로컬 구성에서의 조건 판정 |
|---|---|
| [CVE-2026-41841](https://spring.io/security/cve-2026-41841/) | Framework 버전은 영향 범위이나 인증/공개 정적 리소스의 공유 캐시 구성을 사용하지 않음. 실정보 노출로 집계하지 않음 |
| [CVE-2026-41853](https://spring.io/security/cve-2026-41853/) | multipart를 해석하는 WAF/proxy 우회 조건이 현재 로컬 구성에 없음. Tomcat 패치로 Spring 결함까지 수정했다고 주장하지 않음 |
| [CVE-2026-41848](https://spring.io/security/cve-2026-41848/) | 사용자 입력을 AntPathMatcher의 pattern으로 전달하는 코드 없음. 전역 matcher를 우회하지 않음 |
| [CVE-2025-41248/41249](https://spring.io/blog/2025/09/15/spring-framework-and-spring-security-fixes-for-CVE-2025-41249-and-CVE-2025-41248/) | 현재 수동 AccessGuard이며 parameterized method-security annotation 경계는 사용하지 않음. crypto만 추가한 것을 전체 Security 필터 체인으로 오인하지 않음 |

위 표는 조사한 조건의 현재 판정이며 나머지 공지가 안전하다는 면책 목록이 아니다. 외부 배포·
proxy/HTTP2/업로드·정적 캐시/인증 방식이 바뀌면 다시 검토한다. 검사 도구의 건수나 버전 상승을
사용자 오류율·응답 속도 개선으로 환산하지 않는다.

재현 명령·원값·실행 artifact·유효/무효 시도는 [작업 기록](plans/2026-11-01-tomcat-security-patch.md)에 둔다.
