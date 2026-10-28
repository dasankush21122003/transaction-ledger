# Transaction Ledger API

Backend for a simple ledger: accounts, credit/debit transactions, balances, paginated history and transfers between accounts. Java 21+, Gradle, Jackson, and an embedded H2 database (no install needed). Endpoint details are in [docs/API.md](docs/API.md).

## Running

```bash
./gradlew run                 # http://localhost:8080, data in ./data/
./gradlew run --args=9000     # different port (or set PORT)
./gradlew test
```

The database file location can be changed with `LEDGER_DB_PATH` (default `./data/ledger`).

Example:

```bash
curl -XPOST localhost:8080/accounts -H 'Content-Type: application/json' -d '{"name":"Alice"}'
curl -XPOST localhost:8080/accounts/<id>/transactions -H 'Content-Type: application/json' \
  -d '{"type":"credit","amount":100,"description":"top up","reference_id":"r1"}'
curl localhost:8080/accounts/<id>/balance
curl 'localhost:8080/accounts/<id>/transactions?type=credit&page=1&limit=10'
```

## Layout

```
com.ledger
  Main
  api/        ApiServer                 routing, request parsing, JSON
  service/    LedgerService             business rules, transaction boundaries
  db/         Database                  connections, schema, commit/rollback
              AccountRepository         SQL for accounts
              TransactionRepository     SQL for transactions
              ImmutableTransactionTrigger
  model/      Account, Transaction
  exception/  LedgerException           HTTP status + error code
```

## Data integrity

- Each write runs in one database transaction, so a transaction row and its balance update are saved together or not at all. A transfer saves both legs and both balance updates the same way.
- The account row is locked (`SELECT ... FOR UPDATE`) before the duplicate and balance checks. Concurrent debits can't overdraw, and concurrent retries of one `reference_id` apply once. Transfers lock the two accounts in id order to avoid deadlocks.
- Constraints in the schema: `balance >= 0`, `amount > 0`, type limited to CREDIT/DEBIT, non-blank name, a foreign key from transactions to accounts, and `UNIQUE (account_id, reference_id)`.
- Transactions are append-only. There is no update/delete endpoint, and a trigger rejects `UPDATE`/`DELETE` on the table even from direct SQL.
- Amounts are `DECIMAL(19,2)` / `BigDecimal`.

## Assumptions

- One implicit currency with 2 decimal places. More decimals, or amounts of 10^17 and up, are rejected.
- A repeated `reference_id` on the same account is treated as a retry: the response is `200` with `"status": "ALREADY_EXISTS"` and the original transaction, and nothing changes. New transactions return `201`. The match is on `reference_id` only, so a retry with a different amount still returns the original.
- Sending `balance`, `id` or `created_at` when creating an account is a 400 rather than being ignored.
- A transfer needs a `reference_id`, which is stored on both legs and must be unused on both accounts. Retrying it returns the original transfer. Transferring to the same account is rejected.
- History is newest first; rows with identical timestamps fall back to insertion order. Defaults are `page=1`, `limit=20`, with a maximum limit of 100. A page past the end returns an empty list.
- No authentication. Account ids are random UUIDs.
- Accounts and transactions can't be edited or deleted through the API. Mistakes are corrected by posting an offsetting transaction.
- H2 keeps local setup simple. Moving to PostgreSQL would mean changing the driver, the URL and the identity column in the schema.
