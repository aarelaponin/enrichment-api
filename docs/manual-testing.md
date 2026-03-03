# Manual Testing — curl Commands

## Setup

```bash
BASE="http://localhost:8082/jw/api/enrichment"
API_ID="API-c46c424a-51c5-491d-b348-a785b4b59c24"
API_KEY="8c04d5332aa34484a62fe1fb1e6e5900"
```

## Phase 0: Health Check

```bash
curl -s "$BASE/health" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool
```

## Phase 1: List Records

```bash
# First page, 5 records
curl -s "$BASE/records?page=1&pageSize=5" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool

# With filter
curl -s "$BASE/records?filter=status=enriched&pageSize=10" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool

# With search
curl -s "$BASE/records?search=customer_code:CUST001" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool

# With sort
curl -s "$BASE/records?sort=transaction_date&order=desc&pageSize=10" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool
```

## Phase 2: Single Record

### GET /records/{id}

```bash
# Pick an ID from the list response above
ID="REPLACE_WITH_REAL_ID"

# Happy path
curl -s "$BASE/records/$ID" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool

# 404 — non-existent ID
curl -s "$BASE/records/does-not-exist" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool
```

### PUT /records/{id}

```bash
# Happy path — update a field (use version from GET response, 0 if missing)
curl -s -X PUT "$BASE/records/$ID" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{"version": 0, "internal_type": "test_value"}' | python3 -m json.tool

# 400 — missing version
curl -s -X PUT "$BASE/records/$ID" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{"internal_type": "test_value"}' | python3 -m json.tool

# 404 — non-existent record
curl -s -X PUT "$BASE/records/does-not-exist" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{"version": 0, "internal_type": "test_value"}' | python3 -m json.tool

# 409 — stale version
curl -s -X PUT "$BASE/records/$ID" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{"version": 999, "internal_type": "test_value"}' | python3 -m json.tool

# 400 — terminal status (use ID of a confirmed record)
curl -s -X PUT "$BASE/records/CONFIRMED_RECORD_ID" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{"version": 0, "some_field": "value"}' | python3 -m json.tool
```

### Auto-status transition (enriched -> adjusted)

```bash
# Edit a field on a record with status=enriched — status should auto-transition to adjusted
curl -s -X PUT "$BASE/records/$ID" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{"version": 0, "internal_type": "changed_value"}' | python3 -m json.tool

# Verify: GET the record again, status should now be "adjusted" and version incremented
curl -s "$BASE/records/$ID" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool
```

## Phase 3: Status Transitions

### POST /records/{id}/status — Single

```bash
# Happy path — transition enriched/adjusted to ready
curl -s -X POST "$BASE/records/$ID/status" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{"targetStatus": "ready", "reason": "Reviewed and approved"}' | python3 -m json.tool

# 400 — invalid transition (e.g. confirmed → ready)
curl -s -X POST "$BASE/records/CONFIRMED_RECORD_ID/status" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{"targetStatus": "ready"}' | python3 -m json.tool

# 400 — unknown status code
curl -s -X POST "$BASE/records/$ID/status" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{"targetStatus": "nonexistent"}' | python3 -m json.tool

# 404 — non-existent record
curl -s -X POST "$BASE/records/does-not-exist/status" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{"targetStatus": "ready"}' | python3 -m json.tool
```

### POST /records/status — Batch

```bash
# Batch transition — mix of valid and invalid records
curl -s -X POST "$BASE/records/status" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{
  "recordIds": ["ENRICHED_ID_1", "ENRICHED_ID_2", "CONFIRMED_ID"],
  "targetStatus": "ready",
  "reason": "Batch approval"
}' | python3 -m json.tool

# 400 — empty recordIds
curl -s -X POST "$BASE/records/status" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{"recordIds": [], "targetStatus": "ready"}' | python3 -m json.tool
```

## Phase 3: Delete Record

### DELETE /records/{id}

```bash
# Happy path — delete a record with status=new
curl -s -X DELETE "$BASE/records/NEW_RECORD_ID" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool

# 400 — cannot delete enriched record
curl -s -X DELETE "$BASE/records/ENRICHED_RECORD_ID" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool

# 400 — cannot delete confirmed record
curl -s -X DELETE "$BASE/records/CONFIRMED_RECORD_ID" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool

# 404 — non-existent record
curl -s -X DELETE "$BASE/records/does-not-exist" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool
```

## Phase 4: Summary

### GET /summary

```bash
# Get per-statement summary counts
curl -s "$BASE/summary" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool
```

## Phase 5: Reconciliation

### GET /reconciliation/{statementId}

```bash
STMT_ID="REPLACE_WITH_STATEMENT_ID"

# Happy path
curl -s "$BASE/reconciliation/$STMT_ID" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool

# Non-existent statement — returns zero amounts
curl -s "$BASE/reconciliation/does-not-exist" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool
```

## Phase 6: Confirm

### POST /records/confirm

```bash
# Happy path — confirm ready records
curl -s -X POST "$BASE/records/confirm" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{
  "recordIds": ["READY_ID_1", "READY_ID_2"],
  "allowPartial": true
}' | python3 -m json.tool

# Mixed — some ready, some not
curl -s -X POST "$BASE/records/confirm" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{
  "recordIds": ["READY_ID", "ENRICHED_ID", "CONFIRMED_ID"],
  "allowPartial": true
}' | python3 -m json.tool

# Strict mode — fail entire batch if any invalid
curl -s -X POST "$BASE/records/confirm" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{
  "recordIds": ["READY_ID_WITH_MISSING_FIELDS"],
  "allowPartial": false
}' | python3 -m json.tool
```

## Phase 7: Split

### POST /records/{id}/split

```bash
PARENT_ID="REPLACE_WITH_ENRICHED_RECORD_ID"

# Happy path — split into 3 allocations
curl -s -X POST "$BASE/records/$PARENT_ID/split" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{
  "allocations": [
    {"customer_code": "CUST001", "original_amount": "50000.00", "fee_amount": "125.00"},
    {"customer_code": "CUST002", "original_amount": "30000.00", "fee_amount": "75.00"},
    {"customer_code": "CUST003", "original_amount": "20000.00", "fee_amount": "50.00"}
  ]
}' | python3 -m json.tool

# 400 — amounts don't sum to parent
curl -s -X POST "$BASE/records/$PARENT_ID/split" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{
  "allocations": [
    {"customer_code": "CUST001", "original_amount": "99999.00", "fee_amount": "0"},
    {"customer_code": "CUST002", "original_amount": "1.00", "fee_amount": "0"}
  ]
}' | python3 -m json.tool

# 400 — only 1 allocation
curl -s -X POST "$BASE/records/$PARENT_ID/split" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{
  "allocations": [
    {"customer_code": "CUST001", "original_amount": "100000.00", "fee_amount": "250.00"}
  ]
}' | python3 -m json.tool

# 400 — missing customer_code
curl -s -X POST "$BASE/records/$PARENT_ID/split" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{
  "allocations": [
    {"original_amount": "50000.00", "fee_amount": "125.00"},
    {"customer_code": "CUST002", "original_amount": "50000.00", "fee_amount": "125.00"}
  ]
}' | python3 -m json.tool

# 404 — non-existent parent
curl -s -X POST "$BASE/records/does-not-exist/split" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{
  "allocations": [
    {"customer_code": "CUST001", "original_amount": "50000.00", "fee_amount": "0"},
    {"customer_code": "CUST002", "original_amount": "50000.00", "fee_amount": "0"}
  ]
}' | python3 -m json.tool
```

## Phase 7: Merge

### POST /records/merge

```bash
# Happy path — merge 3 records
curl -s -X POST "$BASE/records/merge" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{
  "recordIds": ["ENRICHED_ID_1", "ENRICHED_ID_2", "ENRICHED_ID_3"],
  "mergedFields": {
    "internal_type": "SEC_BUY",
    "customer_code": "CUST001",
    "debit_credit": "D",
    "description": "Merged: 3 SEC_BUY orders"
  }
}' | python3 -m json.tool

# 400 — different currencies
curl -s -X POST "$BASE/records/merge" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{
  "recordIds": ["EUR_RECORD_ID", "USD_RECORD_ID"],
  "mergedFields": {"internal_type": "SEC_BUY", "customer_code": "CUST001", "debit_credit": "D"}
}' | python3 -m json.tool

# 400 — only 1 record
curl -s -X POST "$BASE/records/merge" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{
  "recordIds": ["SINGLE_ID"],
  "mergedFields": {}
}' | python3 -m json.tool

# 400 — missing required merge fields (sources disagree, no override provided)
curl -s -X POST "$BASE/records/merge" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{
  "recordIds": ["ENRICHED_ID_1", "ENRICHED_ID_2"],
  "mergedFields": {}
}' | python3 -m json.tool

# 404 — non-existent source record
curl -s -X POST "$BASE/records/merge" -H "api_id: $API_ID" -H "api_key: $API_KEY" -H "Content-Type: application/json" -d '{
  "recordIds": ["ENRICHED_ID_1", "does-not-exist"],
  "mergedFields": {"internal_type": "SEC_BUY", "customer_code": "CUST001", "debit_credit": "D"}
}' | python3 -m json.tool
```
