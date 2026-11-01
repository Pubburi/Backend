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
| 회원 | 회원가입, 세션 로그인·로그아웃, 현재 계정·역할 기반 접근 제어, 삭제·비밀번호 변경 세션 회수 |
| 명시취향 | 본인 전용 조회·전체 교체 API, 설문 상태·미입력/빈 선호·soft 목표/hard 범위 구분 |
| 계정 찜 | 본인 전용 저장·해제·단건 상태·페이지 API, 반복 요청 멱등성·현재 상품 정보·삭제/재가입 격리 |
| 기본 주류 목록 | 비개인 ID순 B0 연결, 전체 응답 대기·크기 제한, 현재 DB 재조회와 장애 대체 |
| 상품 | 목록·이름/주종 검색, 가격·도수·명시 맛/향 AND 필터·정렬·페이지 이동, 상세·인기 상품 |
| 상품 특성 | 별도 조회·관리자 전체 교체 API, 용량·설명·맛/향 태그·출처, 미확인/검수된 빈 태그 구분 |
| 색인 관리 | 선택적 로컬 OpenSearch·nori, 명시 ID/버전 이벤트 재생·중복/역전 처리·삭제 표식. 업무 outbox 연동은 진행 예정 |
| 후기 | 상품별 평점·후기 조회와 작성·수정·삭제 |
| 데모 주문 | 주문 생성·내역·상세, 주문 시점 상품/가격 스냅샷·총액, 상품·회원 삭제 후 이력 보존, 관리자 완료 처리·스탬프 |
| 관리자 | 상품·회원·후기·매장·주문 관리 |

현재 인기 상품은 누적 주문 수 정렬이며 기본 추천 목록은 별도 ID순입니다. 개인화 추천은 후속 작업입니다.
API와 응답 계약은 [API Map](docs/API-MAP.md)을 참고하세요.

<a id="technology"></a>

## ⚙️ 기술 스택

| 영역 | 기술 |
|---|---|
| 언어·런타임 | Java 17 |
| 서버 | Spring Boot 3.5.0, Tomcat 10.1.61, Bean Validation |
| JSON | Jackson BOM 2.21.8 · 문자셋/엄격 입력 계약 |
| 저장 | MyBatis 3.0.3, MySQL 8.4 |
| 로컬 색인 | OpenSearch 3.9.0 · analysis-nori 3.9.0 · 명시 관리 CLI |
| API 문서 | springdoc 2.8.9 · OpenAPI 3.1 · Swagger UI |
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
    Service -->|B0/대체| Recommendation["추천 서비스"]
    Events["명시 이벤트 파일"] --> CLI["색인 관리 CLI"]
    CLI --> Search["로컬 OpenSearch · nori"]
~~~

로컬 색인 기동·재생과 미완료 범위는 [실행 안내](docs/LOCAL-SEARCH.md)를 따릅니다. 현재 상품 검색은 MySQL을 사용합니다.

<a id="case-studies"></a>

## 🧩 문제 해결

데이터 조회·입력 정규화·세션의 계정 수명·주문 이력 보존에서 내린 판단을 정리했습니다.
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

### 2. 같은 ID 재가입과 권한 변경에서 기존 세션을 구분하기

#### 문제

관리자 권한을 내리거나 계정을 삭제해도 기존 쿠키는 계속 인증됐습니다. 삭제 중 사용하지 않은 쿠키는 같은 ID로 재가입한 새 계정의 프로필에도 접근했습니다.

#### 원인

Guard가 로그인 때의 역할·이름·스탬프를 신뢰했습니다. ID만 DB에서 재확인해도 삭제 후 같은 문자열 ID로 만든 계정은 구분하지 못합니다. 본인 정보 수정 뒤 계정을 다시 읽어 세션을 갱신하는 방법도 다른 관리자가 그 사이 바꾼 자격을 구세션에 부여할 수 있었습니다.

#### 해결

가입마다 내부 `auth_epoch`를 만들고 명시적 비밀번호 변경 때 회전합니다. 보호 요청은 ID와 epoch가 모두 일치하는 현재 계정을 조회해 역할·표시 정보를 사용합니다. 본인 비밀번호 수정은 잠금 안에서 캡처한 변경 전·후 버전만 반환하고, 관측한 세션이 변경 전 버전과 같을 때만 그 수정이 만든 버전으로 갱신합니다. 일반 정보 수정은 세션 버전을 갱신하지 않습니다.

#### 결과

동일 조건의 로컬 MySQL 8.4·실제 HTTP에서 권한 강등 후 관리자 API 허용, 삭제 후 인증, 같은 ID 재가입 후 구쿠키 접근, 비밀번호 변경 후 구세션 인증이 **각각 1/1→0/1**로 바뀌었습니다. 강등은 403, 나머지는 401이며 새 계정 로그인은 유지합니다. 별도 이름/스탬프 시나리오의 오래된 표시도 1/1→0/1입니다. 서로 다른 각 1회 시나리오의 정확성 결과로, 실사용자 오류율·성능 수치는 아닙니다. [원값·조건·전환 근거](docs/measurements/2026-11-01-session-authority.md)를 분리해 기록했습니다.

#### 대체안/회고

서버의 모든 세션을 찾아 지우는 방법은 계정별 세션 색인과 수명 관리가 필요합니다. 현재는 계정 행의 버전과 요청 시점 DB 확인을 선택했습니다. 대신 Guard마다 DB 읽기 비용이 들고 이미 인증 검사를 통과한 요청 전체를 취소하지는 않습니다. DB 실패에는 저장된 권한으로 허용하거나 세션을 지우지 않습니다. 분산 세션·전체 동시성·지연 측정은 남아 있으며, 해시 형식만 바꾸는 재해시는 자격 회전과 구분합니다.

#### 코드

~~~java
public SessionUser currentUser(HttpSession session) {
	Object value = value(session);
	if (!(value instanceof SessionUser observed)) {
		return null;
	}
	if (observed.id() == null || observed.authEpoch() == null || observed.authEpoch().isBlank()) {
		clearIfCurrent(session, observed);
		return null;
	}
	// DB 예외는 전파한다. 저장된 역할로 대신 허용하거나 세션을 지우지 않는다.
	User current = userDao.selectSessionIdentity(observed.id(), observed.authEpoch());
	if (!sameIdentity(value(session), observed)) {
		return null;
	}
	if (current == null) {
		clearIfCurrent(session, observed);
		return null;
	}
	return SessionUser.from(current);
}
~~~

~~~java
public void refreshAfterCredentialChange(HttpSession session, SessionUser observed, UserUpdateResult updated) {
	if (!updated.credentialsChanged()) {
		return;
	}
	try {
		synchronized (session) {
			if (sameIdentity(value(session), observed)) {
				if (!java.util.Objects.equals(updated.id(), observed.id())
						|| !java.util.Objects.equals(updated.previousAuthEpoch(), observed.authEpoch())) {
					session.removeAttribute(SESSION_USER);
				} else {
					session.setAttribute(SESSION_USER, new SessionUser(observed.id(), observed.name(),
							observed.stamps(), observed.role(), updated.authEpoch()));
				}
			}
		}
	} catch (IllegalStateException invalidated) {
		// 동시 로그아웃으로 제거된 신원을 복원하지 않는다.
	}
}
~~~

[현재 계정 확인·조건부 세션 갱신](https://github.com/Pubburi/Backend/blob/main/src/main/java/com/pubburi/pub/controller/auth/AccessGuard.java) · [잠금 안의 버전 캡처](https://github.com/Pubburi/Backend/blob/main/src/main/java/com/pubburi/pub/model/service/UserServiceImpl.java) · [ID·epoch 조회](https://github.com/Pubburi/Backend/blob/main/src/main/resources/mappers/user.xml) · [계약·전환](docs/SESSION-AUTHORITY.md)

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

클라이언트 합산은 화면 상태를 단순하게 만들지만 다른 클라이언트나 직접 API 호출의 입력까지 보장하지는 않습니다. 서버 정규화를 공통 경계로 선택했습니다. 현재 HTTP·폐기 DB의 쓰기 후 실패 경로까지 검증하지만 모든 단계의 장애·동시성·재시도 멱등성은 별도 개선이 필요합니다.

#### 코드

~~~java
    private List<OrderDetail> normalizeDetails(List<OrderDetail> details) {
        Map<Integer, OrderDetail> byProductId = new LinkedHashMap<>();
        for (OrderDetail detail : details) {
            if (detail == null || detail.getProductId() == null || detail.getProductId() <= 0 || detail.getQuantity() <= 0) {
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

### 4. 가격 변경·참조 삭제에서 주문 이력을 분리하기

#### 문제

주문이 끝난 뒤 상품 가격을 바꾸면 과거 금액이 달라졌고, 상품·회원 삭제는 상세나 주문 자체를 지웠습니다. 활성 상품/계정의 수명이 이미 만들어진 주문 기록까지 바꾸는 문제였습니다.

#### 원인

가격은 현재 상품 JOIN에, 행 생존은 상품·회원 FK의 CASCADE에 묶여 있었습니다. 가격 스냅샷만 추가해도 FK가 상세 행을 지우면 표현/수량이 사라집니다. 계정을 계속 남기는 방식은 이름·자격정보 보관과 모든 조회의 삭제 상태 처리를 요구합니다.

#### 해결

트랜잭션 안에서 상품을 FOR UPDATE로 잠금 조회하고 서버의 상품명·단가·수량별 합계와 주문 총액을 저장합니다. 이후 단건/목록 조회는 저장한 스냅샷을 읽습니다. 새 주문은 ORDER_SNAPSHOT, 과거 단가를 복구할 수 없어 전환 시 고정한 추정값은 LEGACY_ESTIMATE로 구분합니다. 상품·회원 FK는 nullable/SET NULL로 바꿔 참조만 끊고 주문을 보존합니다. 회원 삭제는 회원 잠금→자유 입력 메모 정리→계정 삭제 순서로 처리하며 null 소유자 주문은 관리자만 조회합니다.

#### 결과

가격 변경 실험의 금액 불변 위반은 **3/3 → 0/3**이었습니다. 별도로 같은 초기 데이터의 세 주문에 상품 삭제→회원 삭제를 적용하자 상세 보존 위반은 **2/3 → 0/3**, 회원 삭제 뒤 주문 유실은 **3/3 → 0/3**으로 바뀌었습니다. 삭제 후에도 66,000/217,800/178,200원의 금액과 상세가 남고 참조는 null이 됩니다. 이는 로컬 데모의 정확성 결과이며 실사용자 장애율·응답 성능 수치가 아닙니다. [가격 원값](docs/measurements/2026-10-31-order-price-invariance.md) · [삭제 흐름의 분모·원값·재현](docs/measurements/2026-10-31-order-history-preservation.md)을 구분해 기록했습니다.

#### 대체안/회고

주문 스냅샷과 nullable FK로 업무 기록을 활성 참조에서 분리하고 회원 이름/비밀번호를 복제하지 않았습니다. 과거 가격은 추정임을 명시합니다. FK 전환에서 실제 MySQL 1826 오류를 만나 동일 ALTER의 제거/추가를 분리했고, 쓰기 중단·백업·중간 상태 확인이 필요해졌습니다. 상품별 가격 SELECT와 회원 삭제 잠금의 비용은 미측정이며 전체 동시성 검증이 남아 있습니다. 삭제 계정 세션의 회수는 두 번째 사례의 별도 인증 경계로 처리합니다. 이미 삭제된 행을 복원한 결과로 확대하지 않습니다.

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

참조 삭제와 회원 자유 입력 정리는 다음 경계로 처리합니다.

~~~sql
ALTER TABLE t_order DROP FOREIGN KEY fk_order_user;
ALTER TABLE t_order
    MODIFY COLUMN user_id VARCHAR(100) NULL,
    ADD CONSTRAINT fk_order_history_user FOREIGN KEY (user_id)
        REFERENCES t_user(id) ON DELETE SET NULL;

ALTER TABLE t_order_detail DROP FOREIGN KEY fk_order_detail_product;
ALTER TABLE t_order_detail
    MODIFY COLUMN product_id INT NULL,
    ADD CONSTRAINT fk_order_history_product FOREIGN KEY (product_id)
        REFERENCES t_product(id) ON DELETE SET NULL;
~~~

~~~java
@Override
@Transactional
public int removeUser(String id) {
    if (isBlank(id) || userDao.selectByIdForUpdate(id) == null) {
        return 0;
    }
    // Block new order FK references until notes are cleared and the user is deleted.
    userDao.clearOrderNotes(id);
    if (userDao.delete(id) != 1) {
        throw new IllegalStateException("User could not be deleted");
    }
    return 1;
}
~~~

[가격 캡처·저장 경계](https://github.com/Pubburi/Backend/blob/main/src/main/java/com/pubburi/pub/model/service/OrderServiceImpl.java) · [상품 잠금 조회](https://github.com/Pubburi/Backend/blob/main/src/main/resources/mappers/product.xml) · [저장된 가격 조회](https://github.com/Pubburi/Backend/blob/main/src/main/resources/mappers/order.xml) · [회원 삭제 트랜잭션](https://github.com/Pubburi/Backend/blob/main/src/main/java/com/pubburi/pub/model/service/UserServiceImpl.java) · [삭제 정책·FK 전환](docs/ORDER-HISTORY-PRESERVATION.md)

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
