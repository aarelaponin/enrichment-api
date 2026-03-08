# Enrichment Workspace Operations Specification

**Form:** F01.05 `trxEnrichment`
**Version:** 1.0
**Date:** 2026-03-08
**Depends on:** SPEC.md (rows-enrichment), F02.04 (loan contract), 01-coa-accounts.md (CoA)

---

## 1. Overview

The enrichment workspace is the primary interface where back-office users review, adjust, and approve enriched transactions before posting to the General Ledger, loan contracts, and customer investment portfolios. Every transaction passes through the workspace; no posting happens without explicit human confirmation.

### 1.1 Status Lifecycle

```
NEW → PROCESSING → ENRICHED ──→ IN_REVIEW ──→ ADJUSTED ──→ READY ──→ CONFIRMED
                       │              │              │           │
                       ├──────────────┴──────────────┘           │
                       │         (any edit returns to ADJUSTED)  │
                       │                                         │
                       └── MANUAL_REVIEW ← ERROR                 └── (terminal)
                                ↓
                           RE-ENRICH → NEW (restart)

PAIRED ──→ READY ──→ CONFIRMED
     └──→ MANUAL_REVIEW

SUPERSEDED (terminal — parent after split/merge)
```

### 1.2 Operation Availability by Status

| Operation Category | ENRICHED | IN_REVIEW | ADJUSTED | PAIRED | READY | MANUAL_REVIEW | ERROR |
|--------------------|:--------:|:---------:|:--------:|:------:|:-----:|:-------------:|:-----:|
| Field edits (§2)   | ✓        | ✓         | ✓        | —      | ✓     | —             | —     |
| Split (§3)         | ✓        | ✓         | ✓        | —      | ✓     | —             | —     |
| Loan ops (§4)      | ✓        | ✓         | ✓        | —      | ✓     | —             | —     |
| Fund alloc (§5)    | ✓        | ✓         | ✓        | —      | ✓     | —             | —     |
| Pairing (§6)       | ✓        | ✓         | ✓        | ✓      | —     | —             | —     |
| FX ops (§7)        | ✓        | ✓         | ✓        | —      | ✓     | —             | —     |
| Period-end (§8)    | ✓        | ✓         | ✓        | —      | ✓     | —             | —     |
| Mark Ready         | ✓        | ✓         | ✓        | ✓      | —     | —             | —     |
| Confirm            | —        | —         | —        | —      | ✓     | —             | —     |
| Reprocess          | —        | —         | —        | —      | —     | ✓             | ✓     |
| Delete             | —        | —         | —        | —      | —     | ✓             | ✓     |
| Notes (read/write) | ✓        | ✓         | ✓        | ✓      | ✓     | ✓             | notes only |

Any field edit on an ENRICHED record auto-transitions it to ADJUSTED. Subsequent edits remain ADJUSTED. The user explicitly transitions to READY when satisfied.

### 1.3 Operation Categories

| # | Category | Section | Scope |
|---|----------|---------|-------|
| A | Transaction Adjustment | §2 | Any transaction — reclassify, reassign, edit amounts |
| B | Split & Allocation | §3 | One record → N children with amount allocation |
| C | Loan Contract | §4 | LOAN_PAYMENT, LOAN_DISBURSEMENT, INT_INCOME, INT_EXPENSE |
| D | Fund Allocation | §5 | Fund-level transactions (customer 12345678) → investor distribution |
| E | Securities & Pairing | §6 | Cross-statement bank↔secu linking |
| F | FX | §7 | FX_EXCHANGE, cross-currency operations |
| G | Period-End | §8 | Accruals, reversals, period lock |
| H | Batch & Workflow | §9 | Bulk status transitions, export |

---

## 2. Transaction Adjustment Operations

These operations apply to **any** enriched transaction regardless of type. They modify individual fields on the F01.05 record.

### 2.1 Reclassify Transaction Type

**Purpose:** Change the `internal_type` (IntTp) when the enrichment pipeline assigned the wrong type or UNCLASSIFIED.

**Preconditions:** Status in {ENRICHED, IN_REVIEW, ADJUSTED, READY}. Single record selected.

**UI Panel — "Reclassify":**

| Element | Description |
|---------|-------------|
| Current type | Read-only badge showing current `internal_type` |
| F14 rule match | Read-only: which rule matched, or "No match" if UNCLASSIFIED |
| Suggested types | Dropdown of all `trxType` records from the master table, grouped by category (loan, securities, fund, operational) |
| Quick suggestions | System shows top 3 likely types based on: (a) description keyword matching against F14 rules, (b) same counterparty's most common types, (c) same customer's transaction history |
| Reason | Required free-text explaining why the type was changed |

**On Save:**
- Sets `internal_type` to new value
- Sets `type_confidence` to "manual"
- Sets `matched_rule_id` to null
- Appends to `processing_notes`: "Reclassified from {old} to {new}: {reason}"
- Auto-transitions ENRICHED → ADJUSTED

**Validation:**
- New type must be a valid `trxType` code
- Cannot reclassify to current type (no-op)

### 2.2 Reassign Customer

**Purpose:** Override the pipeline-assigned customer when identification was wrong or when a specific sub-entity must be used.

**Preconditions:** Status in {ENRICHED, IN_REVIEW, ADJUSTED, READY}. Single record.

**UI Panel — "Reassign Customer":**

| Element | Description |
|---------|-------------|
| Current customer | Read-only: `customer_code` + `customer_display_name` + match method badge |
| Search box | Type-ahead search across `customer` table: searches `customerName`, `registrationNumber`, `personalId`, `customerFirstName`+`customerLastName` |
| Search results | Table: customerId, displayName, type (Corp/Ind), kycStatus, riskCategory |
| Selected customer accounts | After selecting a customer, show their accounts from `customer_account` for reference |
| Reason | Required free-text |

**On Save:**
- Sets `resolved_customer_id`, `customer_code`, `customer_display_name`
- Sets `customer_match_method` to "MANUAL"
- Appends to `processing_notes`
- Auto-transitions ENRICHED → ADJUSTED

**Validation:**
- Customer must exist in `customer` table
- Warning (non-blocking) if customer `kycStatus` is not `completed`

### 2.3 Edit Amounts

**Purpose:** Correct amounts when the source data was wrong or when a manual adjustment is needed (e.g., after partial payment confirmation).

**Preconditions:** Status in {ENRICHED, IN_REVIEW, ADJUSTED, READY}. Single record.

**UI Panel — inline editing:**

| Field | Editable | Auto-recalculation |
|-------|----------|-------------------|
| `original_amount` | Yes | `total_amount` = `original_amount` + `fee_amount` |
| `fee_amount` | Yes | `total_amount` = `original_amount` + `fee_amount` |
| `total_amount` | Yes (override) | Clears auto-calc; sets `has_fee` = "manual_override" |
| `base_amount_eur` | Read-only | Recalculated as `total_amount × fx_rate_to_eur` when rate exists |
| `base_fee_eur` | Read-only | Recalculated as `fee_amount × fx_rate_to_eur` |

**Validation:**
- Amounts must be numeric with up to 4 decimal places
- Warning if `total_amount` deviates from `original_amount + fee_amount` by more than 0.01

### 2.4 Flip Debit/Credit

**Purpose:** Correct the `debit_credit` direction when the source assigned it incorrectly.

**Preconditions:** Status in {ENRICHED, IN_REVIEW, ADJUSTED, READY}. Single record.

**Behaviour:** Toggle button — D↔C. For securities (source_tp=secu), sets to N (neutral). Flipping also negates `original_amount`, `fee_amount`, `total_amount` signs.

**On Save:**
- Appends to `processing_notes`: "D/C flipped from {old} to {new}"

### 2.5 Override FX Rate

**Purpose:** Manually set the EUR conversion rate when the pipeline rate is missing, stale, or incorrect (e.g., corporate rate vs. market rate).

**Preconditions:** Status in {ENRICHED, IN_REVIEW, ADJUSTED, READY}. Record has `validated_currency` ≠ EUR.

**UI Panel — "FX Override":**

| Element | Description |
|---------|-------------|
| Current rate | Read-only: `fx_rate_to_eur` + `fx_rate_date` + `fx_rate_source` |
| Pipeline rate reference | If source was MISSING, show that explicitly in red |
| New rate | Numeric input (6 decimal places) |
| Rate date | DatePicker, defaults to `transaction_date` |
| Rate source | Dropdown: "manual", "bloomberg", "ecb", "corporate_rate" |
| Resulting EUR amount | Live preview: `total_amount × new_rate` |

**On Save:**
- Sets `fx_rate_to_eur`, `fx_rate_date`, `fx_rate_source`
- Recalculates `base_amount_eur` and `base_fee_eur`
- Sets `requires_eur_parallel` to "yes"

### 2.6 Override GL Account

**Purpose:** Manually specify GL account codes when the default IntTp → GL mapping doesn't apply (e.g., special one-off transactions, reclassifications for regulatory reporting).

**Preconditions:** Status in {ENRICHED, IN_REVIEW, ADJUSTED, READY}. Single record.

**UI Panel — "GL Override":**

| Element | Description |
|---------|-------------|
| Default mapping | Read-only: shows the GL accounts that would be used based on `internal_type` + `debit_credit` + `customer_code` from 01-coa-accounts.md patterns |
| Debit account | Type-ahead search across GL account master. Shows: code, name, category, allowChildren flag |
| Credit account | Same search |
| Reason | Required free-text |

**New F01.05 fields required:**
- `gl_debit_override` (TextField, nullable)
- `gl_credit_override` (TextField, nullable)
- `gl_override_reason` (TextArea, nullable)

**Validation:**
- Selected GL accounts must be leaf accounts (allowChildren = N)
- Warning if GL account category doesn't match the transaction's D/C direction

### 2.7 Add Processing Note

**Purpose:** Add a free-text annotation without changing any other field. Available in all non-terminal statuses.

**Preconditions:** Status in {ENRICHED, IN_REVIEW, ADJUSTED, PAIRED, READY, MANUAL_REVIEW, ERROR}.

**Behaviour:** Opens text area, appends timestamped note: `[2024-07-15 14:23 user@domain] note text`. Does NOT trigger status auto-transition (editing `processing_notes` alone does not move ENRICHED → ADJUSTED).

---

## 3. Split & Allocation Operations

Split creates N child records from one parent. The parent is SUPERSEDED; children inherit all fields except amounts and (optionally) `internal_type`. Total of children must equal parent amount.

### 3.1 Generic Split (existing)

**Purpose:** Divide one transaction into multiple posting lines when it covers more than one economic event.

**Preconditions:** Status in {ENRICHED, IN_REVIEW, ADJUSTED, READY}. Exactly 1 record selected.

**UI Panel — "Split Record":**

| Element | Description |
|---------|-------------|
| Parent summary | Read-only: date, description, customer, type, total_amount, currency |
| Split lines (2–10) | Each line: amount (numeric), internal_type (dropdown, defaults to parent), description (text, defaults to parent), customer (defaults to parent) |
| Running total | Live: sum of child amounts vs parent amount |
| Remainder | Live: parent amount − sum. Must be zero to proceed |
| Allocation mode | Quick buttons: "Equal split", "Percentage", "Manual" |

**On Save:**
- Parent transitions to SUPERSEDED
- Creates N child records (origin="split", group_id=parent_id, split_sequence=1..N)
- Each child gets status ENRICHED
- Lineage note: "Split from {parent_id}, line {N} of {total}"

**Validation:**
- Sum of child amounts must equal parent `total_amount` (tolerance: 0.01)
- At least 2 children
- Each child must have non-zero amount

### 3.2 Loan Payment Split (principal + interest)

**Purpose:** The most common split operation. Decomposes a LOAN_PAYMENT or LOAN_DISBURSEMENT into its principal and interest components, linked to a specific loan contract.

**Preconditions:** Status in {ENRICHED, IN_REVIEW, ADJUSTED, READY}. `internal_type` in {LOAN_PAYMENT, INT_INCOME, INT_EXPENSE}. Single record.

**UI Panel — "Loan Payment Split":**

| Element | Description |
|---------|-------------|
| Transaction | Read-only: date, customer, amount, description |
| **Loan contract lookup** | |
| Auto-suggested contracts | System parses description for contract references (regex: `[Ll]eping\s+(\S+)`, `#(\d+)`) and shows matching `loanContract` records |
| Contract search | Type-ahead by: loanId, referenceNumber, customerId |
| Selected contract details | Read-only panel: loanId, loanType, principalAmount, currency, interestRate, interestCalcBasis, repaymentType, maturityDate, status, outstanding balance |
| **Split allocation** | |
| Principal amount | Numeric input; system pre-fills suggestion if contract has amortization schedule |
| Interest amount | Numeric input; system calculates expected interest = `outstandingPrincipal × interestRate/100 × daysBetweenPayments / dayCountBasis` |
| Fee/penalty amount | Optional numeric input for late fees or prepayment penalties |
| Variance indicator | Shows difference between actual payment and expected (principal + interest). Green if within 1%, yellow if 1-5%, red if >5% |
| **Running total** | Must equal parent `total_amount` |

**On Save:**
- Parent → SUPERSEDED
- Child 1 (principal): `internal_type` = LOAN_PAYMENT, `loan_id` = selected contract, `loan_direction` = context-appropriate (LENDER for C, BORROWER for D)
- Child 2 (interest): `internal_type` = INT_INCOME (if C) or INT_EXPENSE (if D), `loan_id` = selected contract
- Child 3 (fee, optional): `internal_type` = COMM_FEE or LEGAL_FEE as appropriate
- All children inherit: customer, counterparty, currency, date, settlement_date

**Informational Support:**
- System shows loan contract payment history (last 5 payments from trxEnrichment where loan_id matches)
- System shows expected next payment amount and date from contract schedule
- Warning if customer on transaction doesn't match customer on loan contract

### 3.3 Multi-Period Accrual Split

**Purpose:** Allocate a single payment across accounting periods when it covers multiple months (e.g., quarterly interest payment split into 3 monthly accruals).

**Preconditions:** Status in {ENRICHED, IN_REVIEW, ADJUSTED, READY}. Single record. Typically INT_INCOME or INT_EXPENSE.

**UI Panel — "Period Allocation":**

| Element | Description |
|---------|-------------|
| Transaction | Read-only summary |
| Period range | From-date to to-date (defaults: transaction_date − 90d to transaction_date) |
| Allocation method | "Equal per month", "Day-count proportional", "Manual" |
| Period lines | Auto-generated: one per month in range. Each line: period (YYYY-MM), days in period, amount, transaction_date override |
| Running total | Must equal parent amount |

**On Save:**
- Parent → SUPERSEDED
- N children (one per period), each with `transaction_date` set to last day of the respective period
- Lineage note: "Period allocation from {parent_id}, period {YYYY-MM}"

### 3.4 Fee Disaggregation

**Purpose:** Break a composite fee (e.g., ADMIN_FEE) into sub-components with different GL account targets (e.g., custody portion, audit portion, legal portion).

**Preconditions:** Status in {ENRICHED, IN_REVIEW, ADJUSTED, READY}. Single record. Typically ADMIN_FEE, MGMT_FEE, COMM_FEE, LEGAL_FEE.

**Behaviour:** Identical to Generic Split (§3.1), but the UI pre-sets the IntTp dropdown to show only fee-related types and allows GL override per child line.

---

## 4. Loan Contract Operations

Operations specific to loan-related transactions. These link enriched transactions to the `loanContract` (F02.04) form and validate financial consistency.

### 4.1 Link to Loan Contract

**Purpose:** Associate a transaction with a specific loan contract record. The enrichment pipeline's LoanResolutionStep may have already set `loan_id` and `loan_direction`, but the user may need to override or set it manually.

**Preconditions:** Status in {ENRICHED, IN_REVIEW, ADJUSTED, READY}. `internal_type` in {LOAN_PAYMENT, LOAN_DISBURSEMENT, INT_INCOME, INT_EXPENSE, ASSET_RETURN (when loan-collateral related)}.

**UI Panel — "Link Loan":**

| Element | Description |
|---------|-------------|
| Current linkage | Read-only: `loan_id`, `loan_direction`, `loan_resolution_method` (or "Not linked") |
| Auto-suggestions | Parse description for contract references; show matching contracts |
| Customer's active contracts | All `loanContract` records where customerId = transaction's customer, status in {active, approved} |
| Contract search | By loanId, referenceNumber, or customer name |
| Selected contract | Full details panel (§3.2 layout) |
| Direction | Auto-inferred: D + (LOAN_PAYMENT or INT_EXPENSE) → BORROWER; C + (LOAN_PAYMENT or INT_INCOME) → LENDER. Overridable. |

**On Save:**
- Sets `loan_id`, `loan_direction`, `loan_resolution_method` = "MANUAL"
- Appends to `processing_notes`

### 4.2 Verify Interest Calculation

**Purpose:** Cross-check the interest amount on a transaction against the loan contract terms. Purely informational — does not change any fields.

**Preconditions:** Record has `loan_id` set. `internal_type` in {INT_INCOME, INT_EXPENSE}.

**UI Panel — "Interest Verification" (read-only):**

| Element | Value |
|---------|-------|
| Contract rate | From `loanContract.interestRate` (% p.a.) |
| Day-count basis | From `loanContract.interestCalcBasis` (act_365, act_360, 30_360) |
| Outstanding principal | Last known principal balance (from previous confirmed LOAN_PAYMENT/LOAN_DISBURSEMENT) |
| Period start | Date of last interest payment (from trxEnrichment history) |
| Period end | This transaction's `transaction_date` |
| Days in period | Calculated |
| **Expected interest** | `principal × rate/100 × days / basisDays` |
| **Actual amount** | This transaction's `total_amount` |
| **Variance** | Expected − Actual. Color-coded: green (≤1%), yellow (1-5%), red (>5%) |
| Possible explanations | If floating rate: "Rate may have reset. Current effective rate: X%". If variance matches a round number: "Possible partial accrual" |

### 4.3 Update Outstanding Balance

**Purpose:** After confirming a LOAN_PAYMENT or LOAN_DISBURSEMENT, update the loan contract's outstanding balance.

**Trigger:** Automatic, fires when a loan-linked transaction transitions to CONFIRMED status.

**Logic:**
- LOAN_DISBURSEMENT (LENDER, D=outflow): increase outstanding balance
- LOAN_PAYMENT (LENDER, C=inflow of principal): decrease outstanding balance
- INT_INCOME/INT_EXPENSE: no balance change (interest only)

**New field on `loanContract`:**
- `outstandingBalance` (numeric, updated on each confirmed transaction)
- `lastBalanceUpdateDate` (date)
- `lastBalanceUpdateTrxId` (FK to trxEnrichment)

### 4.3 Create Interest Accrual

**Purpose:** At period-end, create an accrual entry for interest earned but not yet received (or owed but not yet paid).

**Preconditions:** User selects a loan contract (not a transaction). Available from the loan contract detail view or as a batch period-end operation.

**UI Panel — "Interest Accrual":**

| Element | Description |
|---------|-------------|
| Contract | Read-only: loanId, customer, rate, basis |
| Accrual period | From: last interest payment date. To: period-end date (user selects) |
| Days | Calculated |
| Accrual amount | `outstanding × rate/100 × days / basisDays` |
| GL accounts | Debit: 1110 (Bank Interest Receivable) or 1211 (Bond Interest Receivable). Credit: 3102 (Interest Income - Bonds) or 3103 (Interest Income - Cash) |

**On Save:**
- Creates a new F01.05 record: `source_tp` = "manual", `origin` = "accrual", `internal_type` = INT_INCOME (or INT_EXPENSE), `loan_id` = contract, `description` = "Interest accrual {loanId} {period}", status = ENRICHED
- Links to reversal target: `acc_post_id` set for next-period auto-reversal

### 4.4 Flag Early Repayment

**Purpose:** Detect when a LOAN_PAYMENT exceeds the scheduled instalment and may represent early (partial or full) repayment.

**Trigger:** Automatic check when a LOAN_PAYMENT is linked to a contract (§4.1).

**Logic:**
- Compare `total_amount` against expected instalment from contract schedule
- If amount > expected × 1.05: show warning banner "Possible early repayment — amount exceeds scheduled instalment by {X}%"
- If amount ≥ outstanding balance × 0.95: show banner "Possible full repayment — amount approximately equals outstanding balance"

**User actions from banner:**
- "Split into scheduled + prepayment" → opens Loan Payment Split (§3.2) with pre-filled amounts
- "Confirm as scheduled payment" → dismiss warning
- "Mark as full repayment" → sets `processing_notes` flag, loan contract status → closed (after CONFIRMED)

---

## 5. Fund Allocation Model

Genesis Asset Management OÜ (customer `12345678`, `is_fund=yes`) operates a pooled investment fund. Securities are bought/sold in bulk on behalf of multiple investor-customers. Fund-level transactions (MGMT_FEE, TAX, BOND_INT, INT_INCOME on account interest, FX_EXCHANGE, DIV_INCOME from securities) must be allocated to individual investors proportionally.

### 5.1 Data Model (new forms)

#### F03.01 — Investor Position (`investorPosition`)

Tracks each investor's units in the fund.

| Field ID | Type | Label | Notes |
|----------|------|-------|-------|
| `positionId` | IdGeneratorField | Position ID | Format: `IP-??????` |
| `customerId` | SelectBox | Investor | FK to `customer`. Excludes the fund entity itself. |
| `customerDisplayName` | ConcatField | Investor Name | Read-only, computed |
| `unitBalance` | TextField | Current Units | Numeric, 6 decimals. Updated by deposit/withdrawal operations. |
| `initialInvestmentDate` | DatePicker | First Investment | Date of first deposit |
| `lastTransactionDate` | DatePicker | Last Transaction | Auto-updated |
| `status` | SelectBox | Status | active, redeemed, suspended |
| `notes` | TextArea | Notes | Free text |

**Constraints:**
- One active position per customer (unique: customerId + status=active)
- unitBalance ≥ 0

#### F03.02 — NAV Calculation (`navCalculation`)

Period-end snapshot of the fund's net asset value.

| Field ID | Type | Label | Notes |
|----------|------|-------|-------|
| `navId` | IdGeneratorField | NAV ID | Format: `NAV-??????` |
| `calculationDate` | DatePicker | Calculation Date | The valuation date |
| `totalAssets` | TextField | Total Assets (EUR) | Sum of: cash positions + securities market value + receivables |
| `totalLiabilities` | TextField | Total Liabilities (EUR) | Sum of: payables + accrued expenses |
| `netAssetValue` | TextField | Net Asset Value (EUR) | totalAssets − totalLiabilities |
| `totalUnitsOutstanding` | TextField | Total Units Outstanding | Sum of all active investor positions' unitBalance |
| `navPerUnit` | TextField | NAV per Unit (EUR) | netAssetValue / totalUnitsOutstanding. 6 decimals. |
| `status` | SelectBox | Status | draft, confirmed |
| `calculatedBy` | TextField | Calculated By | User who created |
| `confirmedBy` | TextField | Confirmed By | User who confirmed (4-eyes) |
| `confirmedDate` | DatePicker | Confirmed Date | |

#### F03.03 — Fund Transaction Allocation (`fundAllocation`)

Records the per-investor breakdown of a fund-level transaction.

| Field ID | Type | Label | Notes |
|----------|------|-------|-------|
| `allocationId` | IdGeneratorField | Allocation ID | Format: `FA-??????` |
| `sourceEnrichmentId` | TextField | Source Transaction | FK to F01.05 record (the fund-level transaction being allocated) |
| `navId` | TextField | NAV Reference | FK to the NAV calculation used for proportional allocation |
| `customerId` | TextField | Investor | FK to customer |
| `customerDisplayName` | ConcatField | Investor Name | |
| `allocationPercentage` | TextField | Allocation % | investor units / total units × 100. 6 decimals. |
| `allocatedAmount` | TextField | Allocated Amount | sourceAmount × allocationPercentage / 100 |
| `currency` | TextField | Currency | Inherited from source |
| `allocatedAmountEur` | TextField | Allocated EUR | EUR equivalent |
| `allocationDate` | DatePicker | Allocation Date | |
| `status` | SelectBox | Status | pending, confirmed |

### 5.2 Record Investor Deposit (ASSET_RETURN inflow)

**Purpose:** When a customer deposits money into the fund (ASSET_RETURN with D/C=C, credited to fund), convert the deposit to investor units at current NAV.

**Trigger:** Manual action on an ASSET_RETURN transaction where `debit_credit` = C (inflow from customer to fund bank account), and the counterparty is an investor (not the fund entity).

**Note:** These transactions already have the investor's customer ID resolved (not 12345678). The deposit goes INTO the fund's bank account FROM the investor.

**UI Panel — "Record Investor Deposit":**

| Element | Description |
|---------|-------------|
| Transaction | Read-only: date, customer (investor), amount, currency |
| Current NAV | Last confirmed NAV: date, navPerUnit. Warning if older than 5 days. |
| Investor's current position | unitBalance, initialInvestmentDate |
| **Units to issue** | `total_amount / navPerUnit`. Read-only calculation. User can override. |
| New unit balance | currentUnits + issuedUnits |

**On Save:**
- Updates `investorPosition.unitBalance` += issued units
- Updates `investorPosition.lastTransactionDate`
- Creates `fundAllocation` record linking this transaction to the investor
- If no `investorPosition` exists for this customer, creates one (status=active, initialInvestmentDate=transaction_date)

### 5.3 Record Investor Withdrawal (ASSET_RETURN outflow)

**Purpose:** When the fund returns capital to an investor (ASSET_RETURN with D/C=D, fund pays out).

**UI Panel:** Same as §5.2 but calculates units to redeem. Warns if redemption exceeds current balance. If full redemption (unitBalance → 0), offers to set position status to "redeemed".

### 5.4 NAV Calculation

**Purpose:** Compute the fund's net asset value at a point in time.

**Trigger:** Manual action from the Fund Management panel (not from a single transaction).

**UI Panel — "Calculate NAV":**

| Element | Description |
|---------|-------------|
| Calculation date | DatePicker |
| **Assets breakdown** | |
| Cash at bank | Sum of bank account balances for customer 12345678 (from last confirmed statement, or manual input) |
| Securities at market value | Sum of positions × last price (from asset_master / external feed). Editable per position. |
| Receivables | Outstanding loan principal + accrued interest (from loanContract where customerId matches fund's borrowers) |
| Other assets | Manual input |
| **Liabilities breakdown** | |
| Payables | Manual input (unpaid fees, taxes due) |
| Accrued expenses | From accrual entries |
| **Calculation** | |
| Total assets | Sum of above |
| Total liabilities | Sum of above |
| Net asset value | Assets − liabilities |
| Total units outstanding | From sum of all active investorPosition.unitBalance |
| **NAV per unit** | netAssetValue / totalUnitsOutstanding |
| Comparison | Previous NAV per unit + change % |

**On Save:**
- Creates `navCalculation` record with status=draft
- Requires second user to confirm (4-eyes principle) → status=confirmed

### 5.5 Allocate Fund Income/Expense

**Purpose:** Distribute a fund-level transaction (customer 12345678) to individual investors proportionally.

**Preconditions:** Transaction has `resolved_customer_id` = 12345678. `internal_type` in {DIV_INCOME, BOND_INT, INT_INCOME, MGMT_FEE, TAX, COMM_FEE, FX_EXCHANGE, DIV_TAX}. At least one confirmed NAV exists.

**UI Panel — "Fund Allocation":**

| Element | Description |
|---------|-------------|
| Transaction | Read-only: date, type, amount, currency, description |
| NAV reference | Dropdown of confirmed navCalculation records. Default: most recent before transaction_date. |
| **Allocation preview table** | |
| Column: Investor | customerDisplayName |
| Column: Units | from investorPosition.unitBalance as of NAV date |
| Column: Share % | units / totalUnitsOutstanding × 100 |
| Column: Allocated amount | transaction amount × share % |
| Column: Allocated EUR | allocated amount × fx_rate |
| **Totals row** | Must equal transaction amount |
| Rounding adjustment | System allocates rounding difference (±0.01) to the largest investor |

**On Save:**
- Creates N `fundAllocation` records (one per investor with non-zero units)
- Links each to the source enrichment ID and the NAV reference
- Does NOT split the F01.05 record (the fund-level transaction remains as-is in the workspace; allocations are tracked in fundAllocation)
- Sets flag on F01.05: `fund_allocation_status` = "allocated"

**New F01.05 field:**
- `fund_allocation_status` (SelectBox: null, "allocated", "partially_allocated")

**GL Implications:**
Fund-level transactions post to the fund's GL accounts. The fundAllocation records drive the sub-ledger entries per investor. Example for DIV_INCOME of 10.00 EUR from META dividends, fund has 3 investors (40%, 35%, 25%):

```
GL Journal (fund level):
  Dr 1001.LHV-EE          10.00   (Bank clearing — already from statement)
  Cr 3101.12345678         10.00   (Dividend income — fund account)

Sub-ledger allocation (from fundAllocation):
  Cr 3101.12345678.INV001   4.00   (40% to investor 1)
  Cr 3101.12345678.INV002   3.50   (35% to investor 2)
  Cr 3101.12345678.INV003   2.50   (25% to investor 3)
```

### 5.6 Allocation Preview

**Purpose:** Before confirming, show the user the full allocation breakdown for all fund-level transactions in a statement.

**Trigger:** Batch action on the "Ready for Posting" tab when fund-level transactions are selected.

**UI:** Modal table showing: transaction summary row → expandable allocation lines per investor. Export to CSV button for fund manager review.

---

## 6. Securities & Pairing Operations

### 6.1 Manual Pair

**Purpose:** Link a bank settlement transaction (SEC_BUY or SEC_SELL) with its corresponding securities trade when auto-pairing didn't match.

**Preconditions:** Record is bank-type with `internal_type` in {SEC_BUY, SEC_SELL}. Status in {ENRICHED, IN_REVIEW, ADJUSTED}.

**UI Panel — "Manual Pair":**

| Element | Description |
|---------|-------------|
| Bank transaction | Read-only: date, amount, description, current pair status |
| Candidate secu transactions | Auto-filtered: same statement, source_tp=secu, matching direction (bank SEC_BUY ↔ secu EQ_BUY/BOND_BUY), amount within ±5%, date within ±3 days |
| Expanded candidates | If no matches: relax to ±10% amount, ±5 days |
| Selected secu record | Full detail: ticker, quantity, price, fee, total |
| **Match quality** | |
| Amount match | `secu.original_amount` vs `bank.total_amount` — show difference |
| Date match | Days apart |
| Fee reconciliation | `secu.fee_amount` vs implicit fee from bank (`bank.total_amount - secu.original_amount`) |

**On Save:**
- Creates `trx_pair` record linking both IDs
- Both records transition to PAIRED status
- Sets `pair_id` on both F01.05 records

### 6.2 Unpair

**Purpose:** Break an incorrect auto-pair.

**Preconditions:** Record status = PAIRED.

**On Save:**
- Deletes `trx_pair` record
- Both records transition PAIRED → ENRICHED
- Clears `pair_id` on both

### 6.3 Link COMM_FEE to Trade

**Purpose:** Associate a commission fee with its related securities trade for cost-basis and P&L reporting.

**Preconditions:** `internal_type` = COMM_FEE.

**UI Panel — "Link to Trade":**

| Element | Description |
|---------|-------------|
| Fee transaction | Read-only: date, amount, description (contains ticker hint, e.g., "Securities commission fee (CRWD)") |
| Ticker extraction | System extracts ticker from description pattern `\(([A-Z0-9]+)\)` |
| Matching trades | Secu transactions with same ticker, date within ±3 days |
| Selected trade | Full details |

**On Save:**
- Sets `source_reference` = trade's enrichment ID
- Appends to `processing_notes`

### 6.4 Link DIV_TAX to Dividend

**Purpose:** Associate dividend withholding tax with its corresponding dividend income.

**Preconditions:** `internal_type` = DIV_TAX.

**UI:** Same pattern as §6.3. System extracts ticker from "Income tax withheld ({TICKER})". Matches against DIV_INCOME records with same ticker and similar date.

**On Save:**
- Sets `source_reference` = dividend's enrichment ID

### 6.5 Adjust Settlement Difference

**Purpose:** When a paired bank+secu transaction has a fee/rounding difference that doesn't balance.

**Preconditions:** Record is PAIRED. Pair has non-zero discrepancy.

**Behaviour:** Opens split panel pre-populated to create an adjustment child record covering the difference, typed as COMM_FEE or FX_EXCHANGE depending on context.

---

## 7. FX Operations

### 7.1 Pair FX Legs

**Purpose:** Link the debit and credit legs of a single FX exchange event. The 142-transaction dataset shows two FX_EXCHANGE rows for the same date (D: -806.13 EUR, C: +861.25 EUR with rate 1.068380).

**Preconditions:** `internal_type` = FX_EXCHANGE. Both records in {ENRICHED, IN_REVIEW, ADJUSTED}.

**UI Panel — "Pair FX Legs":**

| Element | Description |
|---------|-------------|
| This leg | Read-only: D/C, amount, description (contains exchange rate) |
| Matching legs | Other FX_EXCHANGE records same date, opposite D/C, same rate reference |
| Extracted rate | Parsed from description: `exchange rate (\d+\.\d+)` |
| Calculated cross-check | Sell amount × rate = buy amount (tolerance 0.01) |

**On Save:**
- Creates a pair (similar to secu pairing) linking both legs
- Both set `source_reference` to each other's ID

### 7.2 Override Exchange Rate

See §2.5 (FX Override under Transaction Adjustments).

### 7.3 Create FX Gain/Loss Entry

**Purpose:** When settling an FX position at a rate different from the booking rate, create a realized gain/loss entry.

**Preconditions:** FX pair exists. Rate at booking differs from rate at settlement.

**UI Panel — "FX Gain/Loss":**

| Element | Description |
|---------|-------------|
| Original booking | Rate, amounts, date |
| Settlement | Rate, amounts, date |
| Gain/loss amount | Calculated difference in EUR |
| GL accounts | Dr/Cr 3401 (FX Gains) or 4301 (FX Losses) |

**On Save:**
- Creates new F01.05 record: `source_tp`=manual, `origin`=fx_gain_loss, `internal_type`=FX_GAIN or FX_LOSS

---

## 8. Period-End Operations

### 8.1 Create Accrual Entry

**Purpose:** Record income or expense that has been earned/incurred but not yet received/paid at period-end.

**Trigger:** Manual action from "New Manual Entry" or batch period-end wizard.

**UI Panel — "Create Accrual":**

| Element | Description |
|---------|-------------|
| Accrual type | Dropdown: Interest accrual (loan), Bond interest accrual, Management fee accrual, Custom |
| Related entity | Loan contract (for interest) or customer (for fees) |
| Period | From-date, to-date |
| Amount | Auto-calculated for loan interest (§4.3 logic), manual for others |
| Reversal | Checkbox: "Auto-reverse on first day of next period" (default: checked) |

**On Save:**
- Creates F01.05 record: `source_tp`=manual, `origin`=accrual
- If auto-reverse checked: creates a linked reversal record with `transaction_date` = first day of next period, opposite D/C, `origin`=accrual_reversal, `acc_post_id` linking to original

### 8.2 Reverse Prior Accrual

**Purpose:** Manually reverse an accrual when the auto-reversal wasn't set up or when the timing differs.

**Preconditions:** Select an existing accrual record (origin=accrual).

**Behaviour:** Creates a mirror record with opposite D/C and same amount, linked via `acc_post_id`.

### 8.3 Lock Period

**Purpose:** Prevent further changes to transactions in a closed accounting period.

**UI:** Period selection (YYYY-MM). All F01.05 records with `transaction_date` in that period and status = CONFIRMED become immutable. Records in earlier workflow stages must be resolved (approved or deleted) before the period can be locked.

**Validation:**
- All transactions in period must be in {CONFIRMED, SUPERSEDED} status
- Reconciliation must be within tolerance for all currencies
- At least one user with "approver" role must confirm the lock

**New table:** `periodLock` — period (YYYY-MM), lockedBy, lockedDate, status (locked/unlocked)

### 8.4 Reclassify Between Periods

**Purpose:** Move a transaction from one accounting period to another (e.g., a late-arriving invoice that should be booked in the prior period).

**Preconditions:** Target period must not be locked. Source period must not be locked (or user has override permission).

**Behaviour:** Changes `transaction_date` to a date within the target period. Appends to `processing_notes`.

---

## 9. Batch & Workflow Operations

### 9.1 Bulk Mark Ready

**Purpose:** Move multiple transactions to READY status in one action.

**Preconditions:** All selected records in {ENRICHED, ADJUSTED, IN_REVIEW, PAIRED}.

**UI:** Select rows via checkboxes → toolbar "Mark as Ready" button. Confirmation dialog shows count and lists any records that will be skipped (wrong status).

### 9.2 Bulk Confirm for Posting

**Purpose:** Confirm multiple READY transactions for GL posting.

**Preconditions:** All selected records in READY status.

**UI:** Toolbar "Confirm for Posting" button → modal showing:
- Count of records to confirm
- Per-currency reconciliation summary
- Fund allocation status (all fund transactions allocated?)
- Loan linkage status (all loan transactions linked?)
- Warnings for missing data

**Validation before confirm:**
- Each record has: `internal_type` ≠ UNCLASSIFIED, `resolved_customer_id` ≠ null (for bank transactions), `validated_currency` set
- Fund transactions: `fund_allocation_status` = "allocated"
- Loan transactions: `loan_id` set
- Reconciliation within tolerance

### 9.3 Re-enrich

**Purpose:** Send records back through the enrichment pipeline for reprocessing.

**Preconditions:** Status in {ERROR, MANUAL_REVIEW}.

**Behaviour:** Transitions to NEW, which makes the record eligible for the next enrichment run.

### 9.4 Export for Review

**Purpose:** Generate a report of current workspace state for fund manager or auditor review before final confirmation.

**UI:** Button on Summary tab. Generates CSV or XLSX with all non-superseded transactions, grouped by status, including:
- Transaction details
- Customer assignment + confidence
- Loan linkage
- Fund allocation breakdown
- Exception flags

---

## 10. Cross-Reference Matrix

Transaction type to applicable operations:

| IntTp | Reclassify | Reassign Cust | Loan Split | Fund Alloc | Link Trade | Pair FX | Accrual |
|-------|:----------:|:-------------:|:----------:|:----------:|:----------:|:-------:|:-------:|
| LOAN_PAYMENT | ✓ | ✓ | **primary** | — | — | — | — |
| LOAN_DISBURSEMENT | ✓ | ✓ | ✓ | — | — | — | — |
| INT_INCOME | ✓ | ✓ | ✓ (if loan) | ✓ (if fund) | — | — | ✓ |
| INT_EXPENSE | ✓ | ✓ | ✓ (if loan) | — | — | — | ✓ |
| DIV_INCOME | ✓ | — | — | ✓ (if fund) | — | — | — |
| DIV_TAX | ✓ | — | — | ✓ (if fund) | link to div | — | — |
| BOND_INT | ✓ | — | — | ✓ (if fund) | — | — | ✓ |
| MGMT_FEE | ✓ | — | — | ✓ (if fund) | — | — | — |
| TAX | ✓ | — | — | ✓ (if fund) | — | — | — |
| COMM_FEE | ✓ | — | — | ✓ (if fund) | **primary** | — | — |
| ADMIN_FEE | ✓ | ✓ | — | — | — | — | — |
| LEGAL_FEE | ✓ | ✓ | — | — | — | — | — |
| FX_EXCHANGE | ✓ | — | — | ✓ (if fund) | — | **primary** | — |
| ASSET_RETURN | ✓ | ✓ | — | deposit/withdraw | — | — | — |
| INV_INCOME | ✓ | ✓ | — | — | — | — | — |
| SEC_BUY | — | — | — | — | **primary** | — | — |
| SEC_SELL | — | — | — | — | **primary** | — | — |
| EQ_BUY/SELL | — | — | — | — | auto-paired | — | — |
| BOND_BUY | — | — | — | — | auto-paired | — | — |
| SPLIT_IN/OUT | — | — | — | — | — | — | — |
| UNCLASSIFIED | **primary** | ✓ | — | — | — | — | — |

**Legend:**
- **primary** = this is the most important operation for this transaction type
- ✓ = available and commonly used
- ✓ (if fund) = only when `resolved_customer_id` = 12345678
- ✓ (if loan) = only when `loan_id` is set or `internal_type` is loan-related
- — = not applicable

---

## 11. New F01.05 Fields Required

Summary of new fields needed on the trxEnrichment form to support these operations:

| Field ID | Type | Section | Purpose |
|----------|------|---------|---------|
| `gl_debit_override` | TextField | GL Override | Manual GL debit account code |
| `gl_credit_override` | TextField | GL Override | Manual GL credit account code |
| `gl_override_reason` | TextArea | GL Override | Reason for GL override |
| `fund_allocation_status` | SelectBox | Fund | null / "allocated" / "partially_allocated" |
| `period_locked` | Hidden | Period | "yes" if period is locked |

## 12. New Forms Required

| Form ID | Table | Purpose | Section |
|---------|-------|---------|---------|
| `investorPosition` (F03.01) | investor_position | Track investor units in fund | §5.1 |
| `navCalculation` (F03.02) | nav_calculation | Fund NAV snapshots | §5.1 |
| `fundAllocation` (F03.03) | fund_allocation | Per-investor transaction allocation | §5.1 |
| `periodLock` (F03.04) | period_lock | Accounting period lock status | §8.3 |

## 13. New Loan Contract Fields Required

| Field ID | Type | Purpose |
|----------|------|---------|
| `outstandingBalance` | TextField (numeric) | Current outstanding principal |
| `lastBalanceUpdateDate` | DatePicker | Date of last balance update |
| `lastBalanceUpdateTrxId` | TextField | FK to last confirming trxEnrichment record |
