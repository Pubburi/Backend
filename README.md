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
- [문제 해결](#case-studies)
- [로컬 실행](#development)
- [검증과 협업](#verification)
- [개발 계획](#roadmap)

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
| 데모 주문 | 주문 생성·내역·상세, 주문 시점 상품/가격 스냅샷·총액, 관리자 완료 처리, 스탬프·등급 |
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

<a id="case-studies"></a>

## 🧩 문제 해결

데이터 조회·입력 정규화·인증 전환·주문 금액 보존에서 내린 판단을 정리했습니다.
아래 결과는 현재 구현의 동작 범위이며, 응답 시간·처리량의 전후 개선 수치는 아직 측정하지 않았습니다.

### 1. 주문 페이지를 유지하면서 상세를 일괄 조립하기

#### 문제

주문 내역은 주문 단위로 페이지를 나누지만, 각 주문에는 여러 상품 상세가 붙습니다. 주문마다 상세를 따로 조회하는 구조는 페이지의 주문 수에 따라 DB 왕복을 늘립니다.

#### 원인

주문과 상세의 일대다 관계를 그대로 순회하면 상세 조회가 반복됩니다. 반대로 상세까지 펼친 JOIN 결과에 바로 페이지를 적용하면 한 주문의 상품 행이 페이지 경계를 차지해 주문 단위 페이지와 맞지 않을 수 있습니다.

#### 해결

주문 페이지를 먼저 조회하고 그 주문 ID들을 한 번에 전달해 상세를 가져옵니다. 상세를 주문 ID로 묶은 뒤 원래 주문 목록에 연결하며, 빈 페이지는 상세 조회 전에 반환합니다.

#### 결과

페이지의 주문 순서와 경계를 유지한 채 각 주문에 해당하는 상세가 연결됩니다. 상세가 없는 주문에는 빈 목록을 부여하고, 사용자·관리자·기간별 주문 조회가 같은 조립 경로를 사용합니다.

#### 대체안/회고

단일 JOIN은 단건 상세에는 단순하지만 목록 페이지의 기준을 별도로 처리해야 합니다. 현재 방식은 조회와 조립의 책임이 분명한 대신 일괄 조회 뒤 메모리에서 묶는 비용이 있습니다. 페이지 크기와 데이터 분포를 통제한 DB 실측으로 지연·메모리 영향을 확인할 과제가 남아 있습니다.

#### 코드

~~~java
private List<OrderInfo> attachDetails(List<OrderInfo> orders) {
	if (orders == null || orders.isEmpty()) {
		return List.of();
	}
	List<Integer> orderIds = orders.stream().map(OrderInfo::getoId).toList();
	Map<Integer, List<OrderDetailInfo>> detailsByOrderId = orderDao.getOrderDetailInfoByOrderIds(orderIds).stream()
			.collect(Collectors.groupingBy(OrderDetailInfo::getOrderId));
	for (OrderInfo order : orders) {
		order.setDetails(detailsByOrderId.getOrDefault(order.getoId(), List.of()));
	}
	return orders;
}
~~~

[주문 조립 메서드](https://github.com/Pubburi/Backend/blob/main/src/main/java/com/pubburi/pub/model/service/OrderServiceImpl.java) · [ID 집합으로 상세를 조회하는 SQL](https://github.com/Pubburi/Backend/blob/main/src/main/resources/mappers/order.xml)

### 2. 기존 로그인을 유지하며 비밀번호 해시를 점진적으로 전환하기

#### 문제

SHA-256으로 저장한 기존 비밀번호와 BCrypt를 사용하는 신규 비밀번호가 공존할 때, 저장 형식 전환 때문에 기존 사용자의 정상 로그인을 막지 않아야 합니다.

#### 원인

저장된 해시만으로 새 BCrypt 해시를 만들 수는 없습니다. 사용자가 입력한 원문이 있는 인증 시점에 기존 형식의 일치 여부와 갱신 필요 여부를 함께 판단해야 합니다.

#### 해결

해시 형식에 맞춰 비밀번호를 확인하고, 인증에 성공한 구형 해시만 BCrypt 갱신 경로로 보냅니다. 신규 가입·비밀번호 수정은 BCrypt를 사용하고 로그인 반환 값에서는 비밀번호 필드를 제거합니다.

#### 결과

BCrypt와 기존 SHA-256을 구분하는 인증 경로, 인증 성공 후 구형 해시 갱신 경로가 구현돼 있습니다. 잘못된 비밀번호는 갱신 경로에 진입하지 않으며 로그인 응답에는 저장 해시를 담지 않습니다.

#### 대체안/회고

전체 사용자에게 비밀번호 재설정을 요구하면 구형 형식을 빠르게 종료할 수 있지만 사용자 조치가 필요합니다. 현재는 로그인 시점 전환을 선택했습니다. 구형 형식 지원 종료 정책과 실제 DB의 갱신 실패 처리, 권한 변경을 기존 세션에 반영하는 문제는 별도 개선 대상으로 남아 있습니다.

#### 코드

~~~java
public boolean matches(String raw, String hashed) {
	if (raw == null || hashed == null) {
		return false;
	}
	if (isBcrypt(hashed)) {
		return encoder.matches(raw, hashed);
	}
	return hashed.equals(legacySha256(raw));
}

public boolean needsUpgrade(String hashed) {
	return hashed != null && !isBcrypt(hashed);
}
~~~

~~~java
@Override
@Transactional
public User login(User user) {
	if (user == null || isBlank(user.getId()) || isBlank(user.getPass())) {
		return null;
	}
	User stored = userDao.selectById(user.getId());
	if (stored == null || !passwordHasher.matches(user.getPass(), stored.getPass())) {
		return null;
	}
	if (passwordHasher.needsUpgrade(stored.getPass())) {
		userDao.updatePassword(stored.getId(), passwordHasher.hash(user.getPass()));
	}
	return stored.withoutPass();
}
~~~

[해시 형식 판단](https://github.com/Pubburi/Backend/blob/main/src/main/java/com/pubburi/pub/model/service/PasswordHasher.java) · [로그인과 갱신 경계](https://github.com/Pubburi/Backend/blob/main/src/main/java/com/pubburi/pub/model/service/UserServiceImpl.java)

### 3. 중복 상품을 정규화한 뒤 같은 수량으로 주문과 적립을 처리하기

#### 문제

주문 요청에 같은 상품이 여러 행으로 들어오면 상세 저장·상품 주문 수·스탬프 적립이 서로 다른 단위로 계산될 수 있습니다. 처리 도중 상품이나 회원을 찾지 못했을 때도 실패를 성공 결과와 구분해야 합니다.

#### 원인

클라이언트가 보낸 행을 그대로 저장하면 상품 단위 수량과 요청 행 수가 섞입니다. 주문 헤더·상세·상품 집계·적립은 서로 다른 DAO에 있어 입력 정규화와 쓰기 실패 처리의 경계를 서비스가 정해야 합니다.

#### 해결

DB 쓰기 전에 상품 ID별로 수량을 합산하고 유효하지 않은 상세를 거부합니다. 정규화한 상세를 기준으로 상품 집계와 적립 수량을 계산하며, 쓰기 결과가 기대와 다르면 예외로 종료합니다. 쓰기 흐름은 서비스의 트랜잭션 경계 안에 둡니다.

#### 결과

같은 상품의 중복 행은 합산된 상세로 전달되고, 해당 합산 수량을 상품 주문 수와 스탬프 적립에 사용합니다. 존재하지 않는 상품에서는 이후 상세·적립 생성을 진행하지 않는 예외 경로가 있습니다.

#### 대체안/회고

클라이언트 합산은 화면 상태를 단순하게 만들지만 다른 클라이언트나 직접 API 호출의 입력까지 보장하지는 않습니다. 서버 정규화를 공통 경계로 선택했습니다. 현재 HTTP·폐기 DB의 쓰기 후 실패 경로까지 검증하지만 모든 단계의 장애·동시성·재시도 멱등성과 상품 삭제 뒤 이력 보존은 별도 개선이 필요합니다.

#### 코드

~~~java
    private List<OrderDetail> normalizeDetails(List<OrderDetail> details) {
        Map<Integer, OrderDetail> byProductId = new LinkedHashMap<>();
        for (OrderDetail detail : details) {
            if (detail == null || detail.getProductId() <= 0 || detail.getQuantity() <= 0) {
                throw new IllegalArgumentException("Invalid order detail");
            }
            byProductId.compute(detail.getProductId(), (productId, existing) -> {
                if (existing == null) {
                    OrderDetail normalized = new OrderDetail();
                    normalized.setProductId(productId);
                    normalized.setQuantity(detail.getQuantity());
                    return normalized;
                }
                try {
                    existing.setQuantity(Math.addExact(existing.getQuantity(), detail.getQuantity()));
                } catch (ArithmeticException ex) {
                    throw new IllegalArgumentException("Order quantity is out of range", ex);
                }
                return existing;
            });
        }
        return List.copyOf(byProductId.values());
    }
~~~

[입력 정규화와 트랜잭션 경계](https://github.com/Pubburi/Backend/blob/main/src/main/java/com/pubburi/pub/model/service/OrderServiceImpl.java) · [상품 집계 SQL](https://github.com/Pubburi/Backend/blob/main/src/main/resources/mappers/product.xml) · [스탬프 증가 SQL](https://github.com/Pubburi/Backend/blob/main/src/main/resources/mappers/user.xml)

### 4. 상품 가격이 바뀌어도 과거 주문 금액을 보존하기

#### 문제

주문이 끝난 뒤 상품 가격을 바꾸자 과거 주문 내역의 금액도 달라졌습니다. 주문 당시 가격을 별도로 남기지 않아 관리자 상품 수정이 이미 만들어진 주문의 의미까지 바꾸는 문제였습니다.

#### 원인

상세 테이블에는 상품 ID와 수량만 있었고 조회할 때 현재 상품 가격을 JOIN해 합계를 계산했습니다. 과거 단가가 없는 기존 주문을 새 스키마로 옮기면서 현재 가격을 과거 실제 금액으로 표시하면 데이터의 출처도 잘못 전달됩니다.

#### 해결

트랜잭션 안에서 상품을 FOR UPDATE로 잠금 조회하고 서버의 상품명·단가·수량별 합계와 주문 총액을 저장합니다. 이후 단건/목록 조회는 저장한 스냅샷을 읽습니다. 새 주문은 ORDER_SNAPSHOT, 과거 단가를 복구할 수 없어 전환 시 고정한 추정값은 LEGACY_ESTIMATE로 구분합니다. 기존 응답 필드를 유지하고 totalPrice·priceBasis를 추가했습니다.

#### 결과

같은 초기 데이터에서 상품 두 개의 단가를 각각 1,000원 올렸을 때 과거 금액이 변한 주문이 **3/3 → 0/3**, 관측된 최대 금액 차이는 **3,000원 → 0원**이었습니다. 신규 주문의 단건·목록 응답과 저장 총액/상세 합계도 일치했습니다. 이는 로컬 데모의 금액 정합성 결과이며 외부 실사용자의 장애율이나 응답 성능 수치가 아닙니다. [입력·원값·재현 조건](docs/measurements/2026-10-31-order-price-invariance.md)을 함께 기록했습니다.

#### 대체안/회고

현재 상품 JOIN을 유지하면 과거 금액 불변을 보장할 수 없어 주문 시점 스냅샷을 선택했습니다. 단가만 저장하는 방법보다 표시 정보와 확정 합계까지 보존하되, 상품별 잠금 SELECT가 추가되는 비용이 있습니다. 과거 자료는 추정임을 명시하고 수동 비파괴 전환 절차를 마련했습니다. 상품 삭제 이력 보존(BE02), 가격 출처를 보여주는 Frontend UI, 동시 요청·락 대기의 추가 검증은 남아 있습니다.

#### 코드

서비스의 트랜잭션 안에서 가격을 캡처하고 저장하는 부분입니다.

~~~java
        int totalQuantity = 0;
        int totalPrice = 0;
        for (OrderDetail detail : normalizedDetails) {
            detail.setOrderId(order.getoId());
            Product product = productDao.selectByIdForUpdate(detail.getProductId());
            if (product == null) {
                throw new IllegalArgumentException("Product not found: " + detail.getProductId());
            }
            // Capture server prices under a row lock before any amount-dependent write.
            if (product.getPrice() < 0) {
                throw new IllegalArgumentException("Invalid product price: " + detail.getProductId());
            }
            detail.setProductName(product.getName());
            detail.setProductType(product.getType());
            detail.setProductImg(product.getImg());
            detail.setUnitPrice(product.getPrice());
            try {
                detail.setSumPrice(Math.multiplyExact(product.getPrice(), detail.getQuantity()));
                totalPrice = Math.addExact(totalPrice, detail.getSumPrice());
                totalQuantity = Math.addExact(totalQuantity, detail.getQuantity());
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("Order amount or quantity is out of range", ex);
            }
            if (productDao.incrementOrderCount(detail.getProductId(), detail.getQuantity()) <= 0) {
                throw new IllegalArgumentException("Product not found: " + detail.getProductId());
            }
        }
        if (orderDetailDao.insertAll(normalizedDetails) != normalizedDetails.size()) {
            throw new IllegalStateException("Order details could not be created");
        }
        if (orderDao.updateTotalPrice(order.getoId(), totalPrice) != 1) {
            throw new IllegalStateException("Order total could not be captured");
        }
        order.setTotalPrice(totalPrice);
~~~

조회는 현재 상품 가격 대신 저장된 상세를 사용합니다.

~~~sql
SELECT d.d_id AS id, d.order_id AS orderId, d.product_id AS productId,
       d.quantity, d.product_name AS name, d.product_type AS type,
       d.unit_price AS unitPrice, d.sum_price AS sumPrice,
       d.product_img AS img
FROM t_order_detail d
WHERE d.order_id = #{orderId}
ORDER BY d.d_id ASC
~~~

[가격 캡처·저장 경계](https://github.com/Pubburi/Backend/blob/main/src/main/java/com/pubburi/pub/model/service/OrderServiceImpl.java) · [상품 잠금 조회](https://github.com/Pubburi/Backend/blob/main/src/main/resources/mappers/product.xml) · [저장된 가격 조회](https://github.com/Pubburi/Backend/blob/main/src/main/resources/mappers/order.xml) · [이전 자료 전환](docs/ORDER-PRICE-SNAPSHOT.md)

<a id="development"></a>

## 🚀 로컬 실행

Java 17과 Docker Compose가 필요합니다. 레포 루트에서 실행합니다.
기존 DB는 [주문 가격 스냅샷 전환](docs/ORDER-PRICE-SNAPSHOT.md)을 먼저 확인하세요. 초기화 SQL을 다시 실행하지 않습니다.

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

검증 명령과 폐기용 DB의 HTTP 검사 절차는 [테스트·CI 안내](docs/TESTING.md), 실행 환경은 [로컬 개발 문서](docs/LOCAL-DEVELOPMENT.md)를 따릅니다. 실행 결과와 회귀 근거는 작업 계획·PR에서 관리합니다.

main은 PR·backend-ci 성공·최신 main 반영·대화 해결을 요구합니다. 필수 승인은 0명이고 관리자에게도 적용합니다. Squash merge 뒤 작업 브랜치를 정리합니다.

자동화 PR은 최신 커밋의 Codex 리뷰와 관련 검증까지 통과해야 병합합니다. [개발·운영 기준](docs/DELIVERY-PLAN.md)을 함께 적용합니다.

<a id="roadmap"></a>

## 🗺️ 개발 계획

기한은 **2027년 2월 5일**이며 P1·P2 기능 모두 필수 목표입니다. 승인된 기획과 [백엔드 작업·일정](docs/DELIVERY-PLAN.md)을 기준으로 진행합니다.

- [ ] 상품 맛·향 특성과 출처 관리
- [ ] 취향·찜·추천 피드백·이벤트 API
- [ ] 추천 서비스 요청·응답과 실패 시 대체 추천
- [ ] 주문 당시 단가·상품 삭제 이력 보존 등 기존 정합성 개선
- [ ] 실제 DB 통합 테스트와 후속 배포 설계
