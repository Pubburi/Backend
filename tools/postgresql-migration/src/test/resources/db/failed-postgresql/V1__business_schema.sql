CREATE TABLE transaction_partial_fixture (id INTEGER PRIMARY KEY);
INSERT INTO transaction_partial_fixture VALUES (1);
CREATE TABLE transaction_failure_fixture (id INTEGER REFERENCES deliberately_missing_fixture(id));
