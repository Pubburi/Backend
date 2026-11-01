# 실행 의존성 보안 점검

2026-11-01에 `b1997ac`의 Tomcat과 `6adac1a`의 Jackson 실행 의존성을 공식 공지와 대조했다.
각각 multipart 처리와 Reader 필드명 제한을 분리해 패치했다. 전체 의존성의 취약점 0건·전체 P0 보안 완료나
실제 운영 DoS/침해를 복구한 기록이 아니다. 조사·조건 판정·실행 검증을 구분한다.

## 이번 패치와 실제 버전

| 실행 구성 | 이전 | 변경 후 |
|---|---|---|
| tomcat-embed-core / el / websocket | 각각 10.1.41 | 각각 10.1.61 |
| Spring Boot / Framework | 3.5.0 / 6.2.7 | 유지 |
| spring-security-crypto | 6.5.0 | 유지 |
| MyBatis starter / MyBatis | 3.0.3 / 3.5.14 | 유지 |
| springdoc / Swagger UI | 2.8.9 / 5.21.0 | 유지 |
| Jackson core/databind/datatype/module/yaml | 2.19.0 | BOM 2.21.8 기준 2.21.8 |
| Jackson annotations | 2.19.0 | 2.21 |
| MySQL Connector/J | 9.2.0 | 유지 |

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

## Jackson Reader 필드명 제한

현재 2.19.0은 공식 [GHSA-649p-m576-vr99](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-649p-m576-vr99)의
영향 범위다. 문자 입력 경로가 긴 필드명을 모두 모은 뒤 상한을 검사하는 문제를 실제 소량
Reader로 재현했다. **fresh 버퍼에 한정한** name 상한 64자·필드명 2,048자·128자 chunk의 거부 전
소비는 2,054→256자였다. 그러나 기본 shared pool에 큰 버퍼가 남아 있으면 같은 작은 키는 패치
버전에서도 2,054자를 전부 읽을 수 있다. 패치는 기존 버퍼 안의 모든 입력을 정확히 제한값에서
멈추는 방식이 아니라, 누적 segment가 다시 커지기 전에 제한을 검사한다.

실제 기본 pool에서 60,000자 문자열을 먼저 읽어 재사용을 준비한 뒤 **기본 name 상한 50,000자·
키 140,000자**를 같은 chunk로 비교했다. 기준은 140,006자, 패치는 76,032자를 소비한 뒤 거부했다.
CI 실패를 이 primed 조건으로 재현해 주 회귀를 보강했고 fresh 작은 입력은 보조 검사로 명시했다.
모든 숫자는 해당 Reader/재사용 조건의 소비량이며 전 HTTP 수신/메모리·DoS·응답 시간 개선으로 환산하지 않는다.

최초 수정은 2.21.6이고, [공식 2.21.8 릴리스](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21.8)와
[BOM](https://github.com/FasterXML/jackson-bom/releases/tag/jackson-bom-2.21.8)을 확인해 같은 2.x LTS
조합으로 정렬했다. 실제 바뀐 runtime artifact는 core/databind·jdk8/jsr310·parameter-names·yaml과
annotations의 일곱 개다. annotations가 2.21인 것은 BOM의 정식 구성이고 전부 같은 patch 번호로
덮어쓰지 않았다. Boot/Framework/crypto·MyBatis/SQL·Tomcat 10.1.61·springdoc은 유지했다.

[Spring 6.2.7 converter](https://github.com/spring-projects/spring-framework/blob/v6.2.7/spring-web/src/main/java/org/springframework/http/converter/json/AbstractJackson2HttpMessageConverter.java)는
ISO-8859-1 같은 비Unicode charset을 Reader로 읽는다. 정상 UTF-8/ISO-8859-1/UTF-16 DTO와
기본 필드명 상한을 넘긴 요청의 고정 400·업무 호출/세션 보존을 회귀로 확인한다. 기본 50,000자
필드명 제한과 기존 입력 계약은 유지하고 새 문서 길이 상한은 추가하지 않았다.

별도 [문서 길이 공지](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-2m67-wjpj-xhg9)의
10B 제한/13B blocking 입력은 기존 버전에서도 거절됐다. 이를 이번에 고친 취약점으로 집계하지 않는다.
[다형성 공지](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-m8xf-2cr7-77ph)는
현재 default typing/class-name polymorphism을 사용하지 않아 실제 RCE로 재현하거나 집계하지 않았다.

## 지원 수명과 잔여 검토

[Spring 공식 발표](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)에서 3.5.16은
마지막 OSS 3.5 릴리스다. 3.5.0을 단순히 그 버전으로 바꾸는 것만으로 지원 수명/모든 보안이
해결되지는 않는다. Java 17과 업무 계약을 유지하는 지원 중 BOM 전환·Framework/전이 라이브러리의
적용 조건은 독립 후속 과제다. 이 Reader 패치로 BE07 전체를 완료하지 않는다.

crypto 6.5.0은 [CVE-2025-22234](https://spring.io/security/cve-2025-22234/)에 열거된 영향 버전과
다르고 현재 DaoAuthenticationProvider도 사용하지 않는다. 최신 [WebAuthn 공지](https://spring.io/security/cve-2026-47841/)와
[DPoP 공지](https://spring.io/security/cve-2026-41707/) 역시 현재 WebAuthn/분산 세션·DPoP 모듈이
없다. 이 한정 판정을 모든 crypto/API 보안의 안전 판정으로 확대하지 않는다.

재현·실행 근거는 [Tomcat 기록](plans/2026-11-01-tomcat-security-patch.md)과
[Jackson 기록](plans/2026-11-01-jackson-reader-security.md)에 구분해 둔다.
