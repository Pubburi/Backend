SET @@transaction_isolation="read-committed";
SET NAMES utf8mb4;

-- 사용자 테이블
CREATE TABLE t_user (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    stamps INT NOT NULL DEFAULT 0
);

INSERT INTO t_user (id, name, password_hash, role, stamps) VALUES
('admin', '관리자', '$2y$10$P2jZKcx4Z66jff6TPxEsTO8JQgOycI8Jp/j1OVOS6pVzeOw3Iee/.', 'ADMIN', 0),
('id01', '홍길동', '$2y$10$786lYHcZi9Rn1vhrznqTUeNbjK3xssSaWUoi6bf4M9vXzFxYDgPwm', 'USER', 5),
('id02', '이순신', '$2y$10$1lbGqt87pETZ2TGxPxEPRe4s0xPZD6k349JAMEBc9IKk/8nP3Gtwm', 'USER', 2),
('id03', '김유신', '$2y$10$Typye3dxHHSn3ADVSRcp7ehHWYpaNpfg5RddjZEmIsHivhG7n.RH2', 'USER', 7),
('id04', '강감찬', '$2y$10$cpCgAO5h1EsNJ08IZK.gbOvzSCl.Q7AjWOda9g.N6Bbmb37NjacQ2', 'USER', 1),
('id05', '신사임당', '$2y$10$HhwLCRKy73Pz5BpedbN8ge9ON3gURMVr5GbdmMT472nKQpakrx.IK', 'USER', 3);

-- 술 상품 테이블
CREATE TABLE t_product (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL,
    price INT NOT NULL,
    img VARCHAR(100) NOT NULL,
    abv FLOAT NOT NULL,
    order_count INT NOT NULL DEFAULT 0
);

-- INSERT INTO t_product (name, type, price, img, abv, order_count) VALUES
-- ('카스', '맥주', 4000, 'cass.png', 4.5, 12),
-- ('참이슬', '소주', 4500, 'chamisul.png', 17.8, 15),
-- ('진로이즈백', '소주', 4500, 'jinro.png', 16.9, 100),
-- ('호가든', '맥주', 5000, 'hoegaarden.png', 4.9, 9),
-- ('레드와인', '와인', 15000, 'redwine.png', 12.5, 50);

-- 탁주
INSERT INTO t_product (name, type, price, img, abv, order_count) VALUES
('지란지교 탁주', '탁주', 15000, 'takju1.png', 13.00, 90),
('송명섭 막걸리', '탁주', 4100, 'takju2.png', 6.00, 58),
('세종 알밤주', '탁주', 3000, 'takju3.png', 6.00, 74),
('해창 12%', '탁주', 18000, 'takju4.png', 12.00, 33),
('감천 막걸리', '탁주', 7000, 'takju5.png', 6.00, 42),
('이상헌 탁주 14%', '탁주', 15000, 'takju6.png', 14.00, 88),
('사화 막걸리', '탁주', 20000, 'takju7.png', 12.00, 29),
('오마이갓 탁주', '탁주', 23500, 'takju8.png', 10.00, 69),
('수제 탁주 바랑', '탁주', 18000, 'takju9.png', 15.00, 48),
('붉은 원숭이', '탁주', 9000, 'takju10.png', 10.80, 45);

-- 약 · 청주
INSERT INTO t_product (name, type, price, img, abv, order_count) VALUES
('동학 1957 특선', '약 · 청주', 3800, 'cheongju1.png', 13.00, 80),
('서설', '약 · 청주', 15000, 'cheongju2.png', 13.00, 68),
('솔송주', '약 · 청주', 9000, 'cheongju3.png', 13.00, 77),
('능이주', '약 · 청주', 15000, 'cheongju4.png', 13.00, 45),
('매화깊은밤', '약 · 청주', 12000, 'cheongju5.png', 8.00, 52),
('김포 특주', '약 · 청주', 4000, 'cheongju6.png', 15.00, 32),
('고흥 유자주', '약 · 청주', 15000, 'cheongju7.png', 12.00, 44),
('세종대왕어주 약주', '약 · 청주', 32000, 'cheongju8.png', 15.00, 71),
('감사', '약 · 청주', 17000, 'cheongju9.png', 14.00, 52),
('하타', '약 · 청주', 22000, 'cheongju10.png', 16.00, 55);

-- 과실주
INSERT INTO t_product (name, type, price, img, abv, order_count) VALUES
('고도리 샤인머스캣 와인', '과실주', 23000, 'fruitwine1.png', 10.50, 70),
('금이산 복숭아 와인', '과실주', 16000, 'fruitwine2.png', 12.00, 51),
('코아베스트 보쉐', '과실주', 28000, 'fruitwine3.png', 11.50, 61),
('미라실 울프 살구와인', '과실주', 25000, 'fruitwine4.png', 9.00, 42),
('선운 복분자주', '과실주', 7800, 'fruitwine5.png', 13.00, 91),
('크라테 자두 와인', '과실주', 22500, 'fruitwine6.png', 8.50, 33),
('7004S 키위와인', '과실주', 22000, 'fruitwine7.png', 8.00, 46),
('한스오차드', '과실주', 22400, 'fruitwine8.png', 11.00, 79),
('세인트하우스 복숭아 스파클링 와인', '과실주', 35000, 'fruitwine9.png', 6.00, 11),
('소백산 산향기 스위트 와인', '과실주', 15500, 'fruitwine10.png', 12.00, 36);

-- 증류주
INSERT INTO t_product (name, type, price, img, abv, order_count) VALUES
('추사 40%', '증류주', 30000, 'distilled1.png', 40.00, 81),
('서울의 밤 25%', '증류주', 7900, 'distilled2.png', 25.00, 54),
('안동소주 일품 40% 골드', '증류주', 28000, 'distilled3.png', 40.00, 63),
('모리', '증류주', 9800, 'distilled4.png', 19.00, 46),
('담솔 40%', '증류주', 22000, 'distilled5.png', 40.00, 82),
('금설', '증류주', 39000, 'distilled6.png', 35.00, 55),
('술샘16', '증류주', 12000, 'distilled7.png', 16.00, 62),
('황금보리 25%', '증류주', 14000, 'distilled8.png', 25.00, 87),
('감홍로', '증류주', 45000, 'distilled9.png', 40.00, 40),
('이도 22%', '증류주', 13000, 'distilled10.png', 22.00, 101);

-- 위스키
INSERT INTO t_product (name, type, price, img, abv, order_count) VALUES
('산토리 올드 위스키', '위스키', 72600, 'whiskey1.png', 43.00, 121),
('히비키 하모니', '위스키', 198000, 'whiskey2.png', 43.00, 59),
('에반 윌리엄스 블랙', '위스키', 33000, 'whiskey3.png', 43.00, 133),
('조니워커 블루 750ml', '위스키', 289000, 'whiskey4.png', 40.00, 99),
('발베니 더블우드 12년', '위스키', 120000, 'whiskey5.png', 40.00, 83),
('맥캘란 12년 셰리 오크', '위스키', 139000, 'whiskey6.png', 40.00, 58),
('로얄살루트 21년 700ml', '위스키', 219000, 'whiskey7.png', 40.00, 69),
('글렌파클라스', '위스키', 14000, 'whiskey8.png', 46.00, 55),
('네이키드 몰트', '위스키', 42000, 'whiskey9.png', 40.00, 39),
('달모어 15년', '위스키', 169000, 'whiskey10.png', 40.00, 93);

-- 양주
INSERT INTO t_product (name, type, price, img, abv, order_count) VALUES
('골드 오브 모리셔스 다크 럼', '양주', 69000, 'yangju1.png', 40.00, 33),
('바카디 카르타블랑카', '양주', 28000, 'yangju2.png', 40.00, 101),
('론디아즈 151 럼 750ml', '양주', 24000, 'yangju3.png', 75.50, 22),
('헤네시 VSOP', '양주', 107000, 'yangju4.png', 40.00, 111),
('꼬냑 메스트로 XO', '양주', 307000, 'yangju5.png', 40.00, 95),
('레미 마틴 VSOP', '양주', 88000, 'yangju6.png', 40.00, 61),
('돈 파파 포트 캐스크 럼', '양주', 229000, 'yangju7.png', 40.00, 72),
('론 자카파 23 750ml', '양주', 103900, 'yangju8.png', 40.00, 54),
('밀디아니 VSOP', '양주', 55000, 'yangju9.png', 40.00, 38),
('샤또 드 카스탕데 아르마냑 24년', '양주', 289000, 'yangju10.png', 42.00, 12);

-- 맥주
INSERT INTO t_product (name, type, price, img, abv, order_count) VALUES
('버드와이저 캔', '맥주', 11000, 'beer1.png', 5.00, 66),
('기네스 드래프트', '맥주', 4900, 'beer2.png', 4.20, 72),
('카스 캔 500ml', '맥주', 2800, 'beer3.png', 4.50, 89),
('아사히 슈퍼 드라이 생맥주 캔', '맥주', 4900, 'beer4.png', 5.00, 41),
('에비스 캔 500ml', '맥주', 5000, 'beer5.png', 5.00, 42),
('노을 수제 에일', '맥주', 16000, 'beer6.png', 4.50, 33),
('밀러 드래프트', '맥주', 13200, 'beer7.png',5.00, 21),
('크로넨버그 1664 블랑', '맥주', 18000, 'beer8.png', 5.00, 67),
('타이거 레몬', '맥주', 4500, 'beer9.png', 4.50, 37),
('클라우드 캔 500ml', '맥주', 2750, 'beer10.png', 5.00, 13);

-- 사케
INSERT INTO t_product (name, type, price, img, abv, order_count) VALUES
('다이야메 900ml 25%', '사케', 50000, 'sake1.png', 25.00, 72),
('닷사이 준마이 다이긴죠 45', '사케', 62700, 'sake2.png', 15.00, 46),
('구로 기리시마 900ml', '사케', 50000, 'sake3.png', 25.00, 51),
('고래 사케', '사케', 19500, 'sake4.png', 15.00, 40),
('소미 베이지 준마이 다이긴죠', '사케', 39800, 'sake5.png', 14.00, 43),
('오미네 3 그레인', '사케', 77000, 'sake6.png', 14.50, 30),
('준마이 북극곰의 눈물', '사케', 25200, 'sake7.png',14.50, 25),
('자쿠 미야비노토모 나카토리 준마이 다이긴죠', '사케', 66300, 'sake8.png', 16.00, 62),
('쿠보타 준마이 다이긴죠', '사케', 47000, 'sake9.png', 15.00, 57),
('미이노코토부키 준마이 긴죠', '사케', 59000, 'sake10.png', 14.00, 79);

-- 주문 테이블
CREATE TABLE t_order (
    o_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    order_type VARCHAR(10) NOT NULL,
    order_table VARCHAR(20),
    order_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed CHAR(1) DEFAULT 'N',
    CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES t_user(id) ON DELETE CASCADE
);

INSERT INTO t_order (user_id, order_type, order_table) VALUES
('id01', 'OFFLINE', 'A1'),
('id02', 'ONLINE', NULL),
('id03', 'OFFLINE', 'B2');

-- 주문 상세
CREATE TABLE t_order_detail (
    d_id INT AUTO_INCREMENT PRIMARY KEY,
    order_id INT NOT NULL,
    product_id INT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    CONSTRAINT fk_order_detail_order FOREIGN KEY (order_id) REFERENCES t_order(o_id) ON DELETE CASCADE,
    CONSTRAINT fk_order_detail_product FOREIGN KEY (product_id) REFERENCES t_product(id) ON DELETE CASCADE
);

INSERT INTO t_order_detail (order_id, product_id, quantity) VALUES
(1, 1, 2),
(1, 4, 1),
(2, 2, 1),
(3, 5, 1);

-- 리뷰 테이블
CREATE TABLE t_comment (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    product_id INT NOT NULL,
    rating FLOAT NOT NULL DEFAULT 1,
    comment VARCHAR(200),
    CONSTRAINT fk_comment_user FOREIGN KEY (user_id) REFERENCES t_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_product FOREIGN KEY (product_id) REFERENCES t_product(id) ON DELETE CASCADE
);

INSERT INTO t_comment (user_id, product_id, rating, comment) VALUES
('id01', 1, 4.5, '시원하고 좋아요!'),
('id02', 2, 4.0, '깔끔한 소주'),
('id03', 5, 5.0, '분위기 있을 때 최고');

-- 스탬프 적립
CREATE TABLE t_stamp (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    order_id INT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    CONSTRAINT fk_stamp_user FOREIGN KEY (user_id) REFERENCES t_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_stamp_order FOREIGN KEY (order_id) REFERENCES t_order(o_id) ON DELETE CASCADE
);

INSERT INTO t_stamp (user_id, order_id, quantity) VALUES
('id01', 1, 2),
('id02', 2, 1),
('id03', 3, 3);

CREATE TABLE t_market (
    id INT AUTO_INCREMENT PRIMARY KEY,
    lat DOUBLE NOT NULL,
    lng DOUBLE NOT NULL,
    img VARCHAR(200),
    name VARCHAR(100) NOT NULL
);

INSERT INTO t_market (lat, lng, img, name) VALUES
(36.100000, 128.400000, 'market1.png', '테스트 매장 1'),
(36.101000, 128.401000, 'market2.png', '테스트 매장 2'),
(36.102000, 128.402000, 'market3.png', '테스트 매장 3'),
(36.103000, 128.403000, 'market4.png', '테스트 매장 4'),
(36.104000, 128.404000, 'market4.png', '테스트 매장 5');

CREATE INDEX idx_product_type ON t_product(type);
CREATE INDEX idx_product_order_count ON t_product(order_count);
CREATE INDEX idx_order_user_time ON t_order(user_id, order_time);
CREATE INDEX idx_order_detail_order ON t_order_detail(order_id);
CREATE INDEX idx_order_detail_product ON t_order_detail(product_id);
CREATE INDEX idx_comment_product ON t_comment(product_id);

COMMIT;

-- 조회용 예시
SELECT * FROM t_user;
SELECT * FROM t_product;
SELECT * FROM t_order;
SELECT * FROM t_comment;
SELECT * FROM t_market;
