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

## Phase 7: Split (via ?save= dispatch)

**Note:** `POST /records/{id}/split` returns Joget 400 due to path variable routing. Use `?save=` dispatch instead.

```bash
PARENT_ID="REPLACE_WITH_ENRICHED_RECORD_ID"

# Happy path — split into 3 allocations
SPLIT_JSON='{"split":true,"id":"'$PARENT_ID'","allocations":[{"customer_code":"CUST001","original_amount":"50000.00","fee_amount":"125.00"},{"customer_code":"CUST002","original_amount":"30000.00","fee_amount":"75.00"},{"customer_code":"CUST003","original_amount":"20000.00","fee_amount":"50.00"}]}'
curl -s "$BASE/records?save=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$SPLIT_JSON'))")" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool

# Split with per-child overrides (loan payment split)
LOAN_SPLIT='{"split":true,"id":"'$PARENT_ID'","allocations":[{"customer_code":"CUST001","original_amount":"8000.00","fee_amount":"0","internal_type":"LOAN_PAYMENT","loan_id":"LOAN-001","loan_direction":"LENDER","description":"Principal repayment"},{"customer_code":"CUST001","original_amount":"1500.00","fee_amount":"0","internal_type":"INT_INCOME","loan_id":"LOAN-001","loan_direction":"LENDER","description":"Interest income"},{"customer_code":"CUST001","original_amount":"500.00","fee_amount":"0","internal_type":"COMM_FEE","description":"Late fee"}]}'
curl -s "$BASE/records?save=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$LOAN_SPLIT'))")" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool

# Split with per-child transaction_date (multi-period accrual)
PERIOD_SPLIT='{"split":true,"id":"'$PARENT_ID'","allocations":[{"customer_code":"CUST001","original_amount":"500.00","fee_amount":"0","transaction_date":"2026-01-31","description":"January accrual"},{"customer_code":"CUST001","original_amount":"500.00","fee_amount":"0","transaction_date":"2026-02-28","description":"February accrual"},{"customer_code":"CUST001","original_amount":"500.00","fee_amount":"0","transaction_date":"2026-03-31","description":"March accrual"}]}'
curl -s "$BASE/records?save=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$PERIOD_SPLIT'))")" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool

# 400 — amounts don't sum to parent
SPLIT_BAD='{"split":true,"id":"'$PARENT_ID'","allocations":[{"customer_code":"CUST001","original_amount":"99999.00","fee_amount":"0"},{"customer_code":"CUST002","original_amount":"1.00","fee_amount":"0"}]}'
curl -s "$BASE/records?save=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$SPLIT_BAD'))")" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool
```

## Phase 7: Merge (via ?save= dispatch)

```bash
# Happy path — merge 3 records
MERGE_JSON='{"merge":true,"sourceIds":"ENRICHED_ID_1,ENRICHED_ID_2,ENRICHED_ID_3","mergedFields":{"internal_type":"SEC_BUY","customer_code":"CUST001","debit_credit":"D","description":"Merged: 3 SEC_BUY orders"}}'
curl -s "$BASE/records?save=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$MERGE_JSON'))")" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool
```

## Phase 8: Create Record (via ?save= dispatch)

```bash
# Create interest accrual
CREATE_JSON='{"create":true,"fields":{"internal_type":"INT_INCOME","debit_credit":"C","total_amount":"1500.00","original_amount":"1500.00","validated_currency":"EUR","transaction_date":"2026-03-31","statement_id":"STMT-2026-03","description":"Interest accrual LOAN-001 March 2026","loan_id":"LOAN-001","customer_code":"CUST001","resolved_customer_id":"CUST001"}}'
curl -s "$BASE/records?save=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$CREATE_JSON'))")" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool

# Create FX gain/loss
CREATE_FX='{"create":true,"fields":{"internal_type":"FX_EXCHANGE","debit_credit":"C","total_amount":"55.12","original_amount":"55.12","validated_currency":"EUR","transaction_date":"2026-03-31","statement_id":"STMT-2026-03","description":"FX gain on USD settlement","gl_debit_override":"1001.LHV-EE","gl_credit_override":"3401"}}'
curl -s "$BASE/records?save=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$CREATE_FX'))")" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool

# 422 — missing mandatory fields
CREATE_BAD='{"create":true,"fields":{"internal_type":"INT_INCOME","debit_credit":"C"}}'
curl -s "$BASE/records?save=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$CREATE_BAD'))")" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool

# 400 — empty fields object
CREATE_EMPTY='{"create":true,"fields":{}}'
curl -s "$BASE/records?save=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$CREATE_EMPTY'))")" -H "api_id: $API_ID" -H "api_key: $API_KEY" | python3 -m json.tool
```
