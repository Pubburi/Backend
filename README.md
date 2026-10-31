<div align="center">
  <img src="docs/brand/pubburi.webp" alt="주점부리" width="96" />
  <h1>주점부리 · Backend</h1>
  <p><strong>취향에 맞는 한 잔을 발견하고 기록하는 주류 큐레이션</strong></p>
  <p>회원·상품·후기·데모 주문을 제공하는 Spring Boot API</p>
  <a href="https://github.com/Pubburi/Backend/actions/workflows/ci.yml"><img src="https://github.com/Pubburi/Backend/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI" /></a>
  <img src="https://img.shields.io/badge/Java-17-437291" alt="Java 17" /> <img src="https://img.shields.io/badge/Spring_Boot-3.5-6DB33F" alt="Spring Boot 3.5" /> <img src="https://img.shields.io/badge/MySQL-8.4-4479A1" alt="MySQL 8.4" />
</div>

<br />

## 📄 목차

- [프로젝트 개요](#overview)
- [현재 기능과 범위](#features)
- [기술 스택](#technology)
- [시스템 구조](#architecture)
- [로컬 실행](#development)
- [검증과 협업](#verification)
- [개발 계획](#roadmap)
- [관련 프로젝트와 개발자](#links)

<br />

<a id="overview"></a>

## ✍ 프로젝트 개요

주류 탐색·후기·데모 주문과 관리자 기능을 제공하는 공통 API입니다. Java 17·Spring Boot·MyBatis·MySQL로 구성하며 세션 인증을 사용합니다.

<a id="features"></a>

## 🥃 현재 기능과 범위

| 영역 | 현재 제공 기능 |
|---|---|
| 회원 | 회원가입, 세션 로그인·로그아웃, 본인·관리자 접근 제어 |
| 상품 | 목록·검색·주종 필터·정렬·페이지 이동, 상세·인기 상품 |
| 후기 | 상품별 평점·후기 조회와 작성·수정·삭제 |
| 데모 주문 | 주문 생성·내역·상세, 관리자 완료 처리, 스탬프·등급 |
| 관리자 | 상품·회원·후기·매장·주문 관리 |

현재 인기 상품은 누적 주문 수 정렬입니다. 개인화 추천과 추천 서버 호출은 후속 작업입니다.
API와 응답 계약은 [API Map](docs/API-MAP.md)을 참고하세요.

<a id="technology"></a>

## ⚙️ 기술 스택

| 영역 | 기술 |
|---|---|
| 언어·런타임 | Java 17 |
| 서버 | Spring Boot 3.5.0, Bean Validation |
| 저장 | MyBatis 3.0.3, MySQL 8.4 |
| 빌드·테스트 | Maven Wrapper 3.9.9, JUnit 5 |
| CI | GitHub Actions · backend-ci |

<a id="architecture"></a>

## 🏛️ 시스템 구조

~~~mermaid
flowchart LR
    Web["웹 클라이언트"] --> API["/api · 세션 인증"]
    API --> Service["서비스 · 트랜잭션"]
    Service --> Mapper["MyBatis"]
    Mapper --> DB[("MySQL")]
    Service -. "후속 연동" .-> Recommendation["추천 서비스"]
~~~

<a id="development"></a>

## 🚀 로컬 실행

Java 17과 Docker Compose가 필요합니다. 레포 루트에서 실행합니다.

웹·추천 서버까지 함께 실행하려면 [로컬 통합 실행](docs/LOCAL-DEVELOPMENT.md)을 따릅니다. AWS 계정이나 외부 AI 키 없이 사용할 수 있습니다.

~~~bash
./scripts/local-stack.sh init
./scripts/local-stack.sh install
./scripts/local-stack.sh up
~~~

백엔드만 개별 실행할 때는 아래 설정을 사용합니다.

macOS에서 여러 JDK를 사용하는 경우 먼저 export JAVA_HOME=$(/usr/libexec/java_home -v 17)로 Java 17을 선택합니다.

~~~bash
cp .env.example .env
# .env의 빈 DB 항목을 본인 로컬 값으로 채웁니다.
docker compose up -d db
./scripts/dev.sh
~~~

- 기본 서버: http://localhost:9987
- 상품 API: http://localhost:9987/api/products
- Swagger UI: http://localhost:9987/docs
- 필요한 환경변수: PUBBURI_DB_NAME, PUBBURI_DB_USER, PUBBURI_DB_PASSWORD, PUBBURI_DB_ROOT_PASSWORD, PUBBURI_DB_URL.
- PUBBURI_DB_URL에는 로컬 DB와 포트에 맞는 JDBC 접속 문자열을 설정합니다. 비밀번호와 접속 정보는 커밋하지 않습니다.
- 선택 항목: PUBBURI_DB_PORT(기본 3306), PUBBURI_SERVER_PORT(기본 9987), PUBBURI_LOG_LEVEL(기본 INFO).
- SQL은 빈 볼륨에서 처음 DB를 만들 때만 초기화됩니다. 기존 볼륨은 자동으로 다시 초기화하지 않습니다.

종료할 때는 docker compose down을 사용합니다. 일반 종료에서 DB 볼륨은 보존됩니다.

<a id="verification"></a>

## ✅ 검증과 협업

~~~bash
./scripts/check.sh
~~~

기존 테스트와 패키징을 실행합니다. CI는 별도 임시 MySQL을 초기화하고 80개 상품·페이지 응답을 조회해 서버 기동도 확인합니다. CI의 DB 값은 폐기되는 테스트 설정입니다.

main은 PR·backend-ci 성공·최신 main 반영·대화 해결을 요구합니다. 필수 승인은 0명이고 관리자에게도 적용합니다. Squash merge 뒤 작업 브랜치를 정리합니다.

자동화 PR은 최신 커밋의 Codex 리뷰와 관련 검증까지 통과해야 병합합니다. [개발·운영 기준](docs/DELIVERY-PLAN.md)을 함께 적용합니다.

<a id="roadmap"></a>

## 🗺️ 개발 계획

기한은 **2027년 2월 5일**이며 P1·P2 기능 모두 필수 목표입니다. [전체 기능·일정](docs/DELIVERY-PLAN.md)을 기준으로 진행합니다.

- [ ] 상품 맛·향 특성과 출처 관리
- [ ] 취향·찜·추천 피드백·이벤트 API
- [ ] 추천 서비스 요청·응답과 실패 시 대체 추천
- [ ] 주문 당시 단가·상품 삭제 이력 보존 등 기존 정합성 개선
- [ ] 실제 DB 통합 테스트와 후속 배포 설계

<a id="links"></a>

## 🔗 관련 프로젝트와 개발자

| 레포 | 담당 |
|---|---|
| [Backend](https://github.com/Pubburi/Backend) | 회원·상품·후기·데모 주문 및 공통 API |
| [Frontend](https://github.com/Pubburi/Frontend) | 현재 Vue 웹·관리자 화면, 향후 React 전환 |
| [Recommendation](https://github.com/Pubburi/Recommendation) | 추천 서비스 기반, 향후 데이터·알고리즘·평가 |

[기획·해야 할 일](https://www.notion.so/3ff86a7a8f8a80d298adfd1b0e0c6ae6) · [협업 규칙](CONTRIBUTING.md)

| 개발자 | 이 레포의 담당 |
|---|---|
| [오인성](https://github.com/ois0886) | 백엔드 구조·데이터·API·테스트 |

[소스 출처](docs/PROVENANCE.md)
프로젝트에는 별도 담당자의 모바일 앱도 포함되며, 이 레포는 공통 API 연동 범위를 다룹니다.
