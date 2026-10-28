# API

Base URL `http://localhost:8080`. JSON in and out, snake_case fields, UTC ISO-8601 timestamps.

## Errors

All errors look like this:

```json
{"error": {"code": "INSUFFICIENT_BALANCE", "message": "insufficient balance: balance=100.00, debit=150.00"}}
```

| Status | Code | Cause |
|---|---|---|
| 400 | `VALIDATION_ERROR` | missing or invalid field, bad type, amount <= 0 or more than 2 decimals, client-supplied `balance`, bad `page`/`limit`/`type` |
| 400 | `INVALID_REQUEST_BODY` | body isn't valid JSON or isn't an object |
| 404 | `ACCOUNT_NOT_FOUND` | unknown account id |
| 404 | `NOT_FOUND` | unknown endpoint |
| 405 | `METHOD_NOT_ALLOWED` | wrong method, including any attempt to modify a transaction |
| 409 | `CONFLICT` | `reference_id` already used by a different transaction |
| 422 | `INSUFFICIENT_BALANCE` | debit or transfer larger than the balance |
| 500 | `INTERNAL_ERROR` | unexpected failure |

## Accounts

### POST /accounts

Body: `{"name": "Alice"}`. `name` is required (max 255 chars). `balance`, `id` and `created_at` are rejected if sent.

201:
```json
{"id": "9aab6748-ac43-4abd-9980-2272933327b7", "name": "Alice", "balance": 0.00, "created_at": "2026-10-28T00:02:14.227700Z"}
```

### GET /accounts/{id}

Returns the account above with its current balance. 404 if it doesn't exist.

### GET /accounts/{id}/balance

```json
{"account_id": "9aab6748-ac43-4abd-9980-2272933327b7", "balance": 70.00}
```

## Transactions

### POST /accounts/{id}/transactions

| Field | Required | Notes |
|---|---|---|
| `type` | yes | `credit` or `debit`, case-insensitive |
| `amount` | yes | JSON number, greater than 0, at most 2 decimals |
| `reference_id` | yes | string, max 100 chars, unique per account |
| `description` | no | string, max 500 chars |

201:
```json
{
  "status": "CREATED",
  "transaction": {
    "id": "fbdd3c41-3a48-43e9-8388-da9741a4c2d2",
    "account_id": "9aab6748-ac43-4abd-9980-2272933327b7",
    "type": "CREDIT",
    "amount": 100.00,
    "description": "top up",
    "reference_id": "r1",
    "transfer_id": null,
    "created_at": "2026-10-28T00:02:14.331947Z"
  },
  "balance": 100.00
}
```

If the account already has a transaction with this `reference_id`, the response is 200 with `"status": "ALREADY_EXISTS"`, the original transaction and the unchanged balance. Nothing is created.

A debit larger than the balance returns 422 and records nothing.

### GET /accounts/{id}/transactions

| Query | Default | Notes |
|---|---|---|
| `type` | both | `credit` or `debit` |
| `page` | 1 | integer >= 1 |
| `limit` | 20 | integer from 1 to 100 |

Newest first.

```json
{"data": [ ... ], "page": 2, "limit": 2, "total_records": 3, "total_pages": 2}
```

Totals reflect the `type` filter. A page past the end gives an empty `data` array.

### Modifying transactions

Transactions are immutable. `PUT`, `PATCH`, `DELETE` and `POST` on `/accounts/{id}/transactions/{txId}` return 405, as do `PUT`, `PATCH` and `DELETE` on the list URL.

## Transfers

### POST /transfers

| Field | Required | Notes |
|---|---|---|
| `from_account_id` | yes | must exist and differ from `to_account_id` |
| `to_account_id` | yes | must exist |
| `amount` | yes | greater than 0, at most 2 decimals |
| `reference_id` | yes | must be unused on both accounts |
| `description` | no | max 500 chars |

201:
```json
{
  "status": "CREATED",
  "transfer_id": "c1f2...",
  "debit":  {"account_id": "<from>", "type": "DEBIT",  "amount": 30.00, "transfer_id": "c1f2...", "...": "..."},
  "credit": {"account_id": "<to>",   "type": "CREDIT", "amount": 30.00, "transfer_id": "c1f2...", "...": "..."},
  "source_balance": 70.00,
  "destination_balance": 80.00
}
```

Repeating a transfer with the same source account and `reference_id` returns 200 with `"status": "ALREADY_EXISTS"` and the original legs.

Other errors: 404 if either account is missing (the message says which), 409 if the reference is already used on the destination, 422 if the source balance is too low. On any error no balance changes and no rows are written.
