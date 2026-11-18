<div align="center">
  <h1>주점부리 · Backend</h1>
  <p>주점부리의 Spring Boot API</p>
  <a href="https://github.com/Pubburi/Backend/actions/workflows/ci.yml"><img src="https://github.com/Pubburi/Backend/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI" /></a>
  <img src="https://img.shields.io/badge/Java-17-437291" alt="Java 17" /> <img src="https://img.shields.io/badge/Spring_Boot-3.5-6DB33F" alt="Spring Boot 3.5" /> <img src="https://img.shields.io/badge/PostgreSQL-18.6-4169E1" alt="PostgreSQL 18.6" />
</div>

<br />

## 📄 목차

- [프로젝트 개요](#overview)
- [현재 기능과 범위](#features)
- [기술 스택](#technology)
- [시스템 구조](#architecture)
- [문제 해결](#problem-solving)
- [로컬 실행](#development)
- [검증과 협업](#verification)
- [개발 계획](#roadmap)

<br />

<a id="overview"></a>

## ✍ 프로젝트 개요

고객은 주류를 검색하고, 가격·도수·맛·향으로 목록을 좁히거나 후기를 남깁니다. Backend는 상품과 계정 데이터를 관리하고, 데모 주문의 상품명과 가격을 주문 시점 그대로 보관합니다.

<a id="features"></a>

## 🥃 현재 기능과 범위

| 영역 | 현재 제공 기능 |
|---|---|
| 계정 | 회원가입·로그인·프로필 수정. 보호 요청마다 현재 계정과 역할을 확인합니다. |
| 찜·취향·기록 | 계정별 찜과 취향을 저장합니다. PostgreSQL에서는 마신 술이나 관심 있는 술을 비공개로 기록할 수 있습니다. |
| 상품·후기 | 이름과 주종으로 검색하고 가격·도수·맛·향으로 좁힙니다. 상세에서 용량·설명·출처와 회원 후기를 봅니다. |
| 테마 탐색 | 카탈로그에 출처와 용량이 등록된 상품을 도수와 용량 기준으로 묶습니다. |
| 장바구니·주문 | PostgreSQL에서 기기 간 카트를 공유하고 비회원 카트를 병합합니다. 상품이나 회원을 삭제해도 주문 금액과 상세는 남습니다. |
| 관리자 | 상품·특성·회원·후기·매장·주문을 관리하고 데모 주문을 완료 처리합니다. |

인기 상품은 누적 주문 수순입니다. 카탈로그 가격은 시연 값입니다.

<a id="technology"></a>

## ⚙️ 기술 스택

| 분류 | 기술 스택 |
|---|---|
| 언어 | Java · Python |
| 프레임워크·라이브러리 | Spring Boot · Bean Validation · Jackson |
| 서버 | Tomcat |
| 데이터베이스 | MySQL · PostgreSQL |
| 데이터 접근 | MyBatis · Spring Data JPA · Hibernate · PyMySQL · Psycopg |
| DB 마이그레이션 | Flyway |
| 검색 | OpenSearch · analysis-nori |
| API 문서 | springdoc · OpenAPI · Swagger UI |
| 빌드 | Maven Wrapper |
| 테스트 | JUnit |
| CI | GitHub Actions |

<a id="architecture"></a>

## 🏛️ 시스템 구조

기본 Maven JAR은 PostgreSQL과 JPA를 사용합니다. MySQL과 MyBatis 비교용 JAR은 별도로 빌드합니다.

```mermaid
flowchart LR
    Web["웹 클라이언트"] --> API["/api · 세션 인증"]
    API --> Service["서비스 · 트랜잭션"]
    Service --> JPA["Spring Data JPA · Hibernate"]
    JPA --> DB[("PostgreSQL")]
    DB -->|상품 변경 기록| Relay["검색 relay"]
    Relay --> Search["로컬 OpenSearch · nori"]
```

검색은 기본적으로 DB에서 처리합니다. 별도로 실행한 relay가 상품 변경을 OpenSearch에 전송합니다. 엔진 검색을 켜면 API는 결과를 현재 DB와 대조하고, 누락이나 지연이 있거나 엔진에 연결할 수 없으면 같은 조건으로 DB를 조회합니다.

PostgreSQL로 옮길 때는 원본을 읽기 전용으로 추출하고, 승인한 자료만 빈 DB에 복사한 뒤 ID와 원문 JSON을 대조합니다.

<a id="problem-solving"></a>
<a id="case-studies"></a>

## 🧩 문제 해결

### 응답을 잃어도 장바구니와 주문의 중복 변경 막기

#### 문제

장바구니 병합 응답이 유실되는 상황을 개발 중 재현했습니다. 처리 기록이 404를 반환한 직후 원래 요청이 완료됐습니다. 새 요청 ID로 병합을 다시 보내자 수량이 2개에서 4개가 됐습니다.

#### 원인

404가 반환돼도 앞선 요청은 실행을 기다릴 수 있습니다. 새 요청이 최신 `revision`을 읽으면 버전 검사도 중복 합산을 막지 못합니다. 주문을 검증할 때는 bulk SQL 뒤의 `EntityManager.clear()`가 장바구니 엔티티를 분리해 주문만 저장되고 수량과 버전은 갱신되지 않는 경우도 발견했습니다.

#### 해결

계정 행을 잠그고 요청 UUID와 내용 해시로 처리 기록을 찾은 뒤 장바구니 버전을 검사합니다. 결과 확인 요청은 저장된 `APPLIED`를 읽거나, 처리 전인 UUID를 `RETIRED`로 저장해 나중에 같은 요청이 실행되지 않게 합니다. 주문과 수량 차감, 처리 기록은 같은 트랜잭션에 둡니다. 주문 SQL 뒤에는 장바구니를 다시 잠금 조회해 JPA가 관리하는 엔티티를 수정합니다.

#### 결과

격리된 PostgreSQL 18.6에 HTTP 요청을 보냈습니다. 먼저 폐기한 요청은 나중에 도착해도 409를 반환했고, 원래 요청이 먼저 끝나면 저장한 결과를 읽었습니다. 주문 응답을 잃어도 주문은 한 건만 생겼으며, 3개 중 2개를 주문하자 1개가 남았습니다. 처리 기록 저장을 실패시키면 주문·수량·버전이 함께 롤백됐습니다. 백업을 복구하고 JAR을 다시 띄운 뒤에도 `RETIRED`가 유지됐습니다.

#### 대체안/회고

버튼을 잠가도 다른 기기에서 다시 요청할 수 있습니다. 주문과 장바구니 차감을 별도 HTTP 요청으로 처리하면 하나만 성공할 수 있습니다. 두 변경을 한 트랜잭션에 두면 같은 계정의 쓰기는 순차로 처리되고, 처리 기록을 계정이 존재하는 동안 보관해야 합니다. 비회원 카트는 병합 결과를 확인할 때까지 기기에 남깁니다.

#### 코드

```java
@Transactional(isolation=Isolation.READ_COMMITTED)
public Result resolve(SessionUser owner, UUID operation, CartInput.Resolve input) {
    lockOwner(owner);
    String hash = CartInput.intent(input.items(), input.orderType(), input.orderTable());
    var receipt = matching(owner, operation, input.kind(), hash);
    if (receipt != null)
        return new Result(operation, true, receipt.status(),
            view(owner.id(), carts.findById(owner.id()).orElseThrow()), receipt.orderId());
    var cart = locked(owner);
    expected(cart, cart.version());
    advance(cart);
    operations.saveAndFlush(JpaCartOperation.retire(owner.id(), operation, input.kind(), hash));
    return new Result(operation, false, Status.RETIRED, view(owner.id(), cart), null);
}
```

### 계정 변경과 재가입 뒤에도 예전 세션의 권한 회수하기

#### 문제

관리자를 일반 회원으로 바꿔도 기존 쿠키로 관리자 목록을 조회할 수 있었습니다. 회원을 삭제한 뒤 같은 ID로 재가입하면, 삭제 전에 로그인한 쿠키가 새 계정의 프로필에 접근하는 문제도 재현했습니다.

#### 원인

권한 검사가 로그인 당시 세션에 저장한 역할을 사용했습니다. 현재 DB에서 ID가 존재하는지만 확인해도 같은 문자열 ID로 다시 만든 계정을 구별할 수 없습니다. 세션의 이름과 적립 수도 계정 수정 전 값으로 남았습니다.

#### 해결

계정 생성과 명시적인 비밀번호 변경 때 `auth_epoch` UUID를 발급하고, 보호 요청마다 ID와 이 값으로 현재 계정을 조회합니다. 역할·이름·적립 수는 조회한 DB 값을 사용합니다. DB 조회가 끝난 뒤에도 세션의 신원이 같은지 확인해, 그사이 로그아웃하거나 다른 계정으로 로그인한 요청을 되살리지 않게 했습니다.

#### 결과

Java 17·MySQL 8.4의 별도 폐기 DB와 실제 JAR HTTP에서 같은 다섯 고정 시나리오를 전후 비교했습니다.

| 기존 쿠키로 보낸 요청 | 수정 전 | 수정 후 |
|---|---|---|
| 관리자 강등 뒤 관리자 목록 조회 | 200 | 403 |
| 회원 삭제 뒤 본인 정보 조회 | 200 | 401 |
| 삭제와 재가입 사이 사용하지 않은 쿠키로 새 계정 프로필 조회 | 200 | 401 |
| 비밀번호 변경 뒤 본인 정보 조회 | 200 | 401 |

이름과 적립 수를 수정한 뒤에는 기존 쿠키로도 바뀐 값이 조회됐습니다. 각 시나리오를 한 번씩 비교한 정합성 결과이며, 동시 부하에서의 지연·처리량은 추가 측정 대상입니다.

#### 대체안/회고

서버의 모든 세션을 찾아 삭제하는 방식은 세션 저장소 전체를 추적해야 합니다. 계정 버전을 대조하면 계정 수명과 비밀번호 변경을 DB 한 곳에서 판단할 수 있지만, 보호 요청마다 DB 조회 비용이 듭니다. 조회 이후 이미 실행 중인 업무 요청까지 취소하려면 업무 트랜잭션에서도 계정 상태를 확인해야 합니다.

#### 코드

`AccessGuard.currentUser`는 현재 계정을 조회한 뒤 세션 신원을 다시 확인합니다.

```java
User current = userDao.selectSessionIdentity(observed.id(), observed.authEpoch());
if (!sameIdentity(value(session), observed)) {
    return null;
}
if (current == null) {
    clearIfCurrent(session, observed);
    return null;
}
return SessionUser.from(current);
```

<a id="development"></a>

## 🚀 로컬 실행

Java 17과 Docker Compose를 준비합니다. `./mvnw verify`로 PostgreSQL JAR을, `./mvnw -f tools/mysql-baseline/pom.xml verify`로 MySQL 비교용 JAR을 빌드합니다. 검증한 PostgreSQL을 실행 대상으로 선택한 뒤 `./scripts/dev.sh`로 Backend만 띄울 수 있습니다.

웹과 API를 함께 띄울 때는 Backend와 Frontend를 같은 상위 폴더에 둡니다. Java 17, Node.js 24, Python 3, Docker Compose를 준비한 뒤 Backend에서 실행합니다.

```bash
./scripts/local-stack.sh init
./scripts/local-stack.sh install
./scripts/local-stack.sh up
```

MySQL 비교 환경을 직접 띄우려면 `test -f .env || cp .env.example .env`로 설정 파일을 준비합니다. `.env`에 DB 이름, 사용자, 사용자와 root의 비밀번호, JDBC 주소를 채웁니다. macOS에서 다른 JDK를 쓰고 있다면 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`로 바꿉니다.

```bash
docker compose up -d db
./scripts/dev.sh --provider mysql
```

기본 포트는 9987입니다. [상품 API](http://localhost:9987/api/products) · [Swagger UI](http://localhost:9987/docs)

기존 DB의 적용 기록은 [MySQL 이관 SQL](tools/mysql-baseline/migrations/)로 확인합니다. `docker compose down`으로 종료하면 DB 볼륨은 남습니다.

<a id="verification"></a>

## ✅ 검증과 협업

```bash
./scripts/check.sh
```

폐기용 DB의 HTTP·스키마·검색 검증은 `backend-ci`에서 실행합니다.

런타임·API·DB 변경과 최적화에는 격리된 실제 HTTP 부하 테스트를 수행합니다. 변경 전에 기준 커밋·데이터·입력·환경·부하·표본 수·워밍업·캐시·반복 조건과 판정 기준을 정하고, 같은 조건의 p50·p95·처리량·오류율과 데이터 정합성을 비교합니다. 원값과 한계는 비밀을 제거해 열린 PR과 기존 GitHub 기록에 남깁니다.

최신 `main`을 반영한 PR에서 `backend-ci`가 성공하고 리뷰 대화가 모두 해결되면 squash merge합니다. 자동화 PR은 최신 커밋의 Codex 리뷰도 확인합니다. [협업 규칙](CONTRIBUTING.md)

<a id="roadmap"></a>

## 🗺️ 개발 계획

F01–F12 MVP의 목표는 **2026년 12월 31일**, 최종 인계는 **2027년 1월 15일(KST)**입니다. Notion의 기존 할 일을 모두 완료하면 곧바로 오류와 성능을 측정해 개선합니다.
