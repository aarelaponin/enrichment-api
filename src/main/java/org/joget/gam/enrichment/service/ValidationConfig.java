package org.joget.gam.enrichment.service;

import org.joget.commons.util.LogUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable configuration parsed from the validationConfig plugin property JSON.
 * <p>
 * Covers: baseCurrency, requiredFields, conditionalRequirements,
 * confidenceOverrides, reconciliation, splitMerge, and confirmation sections.
 */
public class ValidationConfig {

    private static final String CLASS_NAME = ValidationConfig.class.getName();

    private final String baseCurrency;
    private final List<String> requiredFields;
    private final List<ConditionalRequirement> conditionalRequirements;
    private final JSONArray confidenceOverrides;
    private final ReconciliationConfig reconciliation;
    private final SplitMergeConfig splitMerge;
    private final ConfirmationConfig confirmation;
    private final AllocationConfig allocation;
    private final IncomeAllocationConfig incomeAllocation;
    private final CapitalAllocationConfig capitalAllocation;

    private ValidationConfig(String baseCurrency,
                             List<String> requiredFields,
                             List<ConditionalRequirement> conditionalRequirements,
                             JSONArray confidenceOverrides,
                             ReconciliationConfig reconciliation,
                             SplitMergeConfig splitMerge,
                             ConfirmationConfig confirmation,
                             AllocationConfig allocation,
                             IncomeAllocationConfig incomeAllocation,
                             CapitalAllocationConfig capitalAllocation) {
        this.baseCurrency = baseCurrency;
        this.requiredFields = Collections.unmodifiableList(requiredFields);
        this.conditionalRequirements = Collections.unmodifiableList(conditionalRequirements);
        this.confidenceOverrides = confidenceOverrides;
        this.reconciliation = reconciliation;
        this.splitMerge = splitMerge;
        this.confirmation = confirmation;
        this.allocation = allocation;
        this.incomeAllocation = incomeAllocation;
        this.capitalAllocation = capitalAllocation;
    }

    /**
     * Parses the validationConfig JSON string. Returns an empty config if null/empty/invalid.
     */
    public static ValidationConfig parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return empty();
        }
        try {
            JSONObject root = new JSONObject(json);

            String baseCurrency = root.optString("baseCurrency", "EUR");

            // requiredFields
            List<String> requiredFields = new ArrayList<>();
            JSONArray rfArr = root.optJSONArray("requiredFields");
            if (rfArr != null) {
                for (int i = 0; i < rfArr.length(); i++) {
                    String f = rfArr.optString(i, "");
                    if (!f.isEmpty()) {
                        requiredFields.add(f);
                    }
                }
            }

            // conditionalRequirements
            List<ConditionalRequirement> condReqs = new ArrayList<>();
            JSONArray crArr = root.optJSONArray("conditionalRequirements");
            if (crArr != null) {
                for (int i = 0; i < crArr.length(); i++) {
                    JSONObject crObj = crArr.optJSONObject(i);
                    if (crObj != null) {
                        condReqs.add(ConditionalRequirement.parse(crObj));
                    }
                }
            }

            // confidenceOverrides
            JSONArray overrides = root.optJSONArray("confidenceOverrides");

            // reconciliation
            ReconciliationConfig recon = null;
            JSONObject reconObj = root.optJSONObject("reconciliation");
            if (reconObj != null) {
                recon = ReconciliationConfig.parse(reconObj);
            }

            // splitMerge
            SplitMergeConfig sm = null;
            JSONObject smObj = root.optJSONObject("splitMerge");
            if (smObj != null) {
                sm = SplitMergeConfig.parse(smObj);
            }

            // confirmation
            ConfirmationConfig conf = null;
            JSONObject confObj = root.optJSONObject("confirmation");
            if (confObj != null) {
                conf = ConfirmationConfig.parse(confObj);
            }

            // allocation
            AllocationConfig alloc = null;
            JSONObject allocObj = root.optJSONObject("allocation");
            if (allocObj != null) {
                alloc = AllocationConfig.parse(allocObj);
            }

            // incomeAllocation
            IncomeAllocationConfig ia = null;
            JSONObject iaObj = root.optJSONObject("incomeAllocation");
            if (iaObj != null) {
                ia = IncomeAllocationConfig.parse(iaObj);
            }

            // capitalAllocation (automatic trade allocation — product/holding/deposit sources)
            CapitalAllocationConfig ca = null;
            JSONObject caObj = root.optJSONObject("capitalAllocation");
            if (caObj != null) {
                ca = CapitalAllocationConfig.parse(caObj);
            }

            return new ValidationConfig(baseCurrency, requiredFields, condReqs,
                    overrides, recon, sm, conf, alloc, ia, ca);

        } catch (Exception e) {
            LogUtil.warn(CLASS_NAME, "Failed to parse validationConfig: " + e.getMessage());
            return empty();
        }
    }

    private static ValidationConfig empty() {
        return new ValidationConfig("EUR", Collections.emptyList(),
                Collections.emptyList(), null, null, null, null, null, null, null);
    }

    public String getBaseCurrency() { return baseCurrency; }
    public List<String> getRequiredFields() { return requiredFields; }
    public List<ConditionalRequirement> getConditionalRequirements() { return conditionalRequirements; }
    public JSONArray getConfidenceOverrides() { return confidenceOverrides; }
    public ReconciliationConfig getReconciliation() { return reconciliation; }
    public SplitMergeConfig getSplitMerge() { return splitMerge; }
    public ConfirmationConfig getConfirmation() { return confirmation; }
    public AllocationConfig getAllocation() {
        return allocation != null ? allocation : AllocationConfig.defaults();
    }
    public IncomeAllocationConfig getIncomeAllocation() {
        return incomeAllocation != null ? incomeAllocation : IncomeAllocationConfig.defaults();
    }
    public CapitalAllocationConfig getCapitalAllocation() {
        return capitalAllocation != null ? capitalAllocation : CapitalAllocationConfig.defaults();
    }

    // ── Inner classes ──────────────────────────────────────────────────

    /**
     * Sources for capital-share trade allocation (D-4): the product registry, customer-product
     * holdings, and capital deposits. Defaults match the F04 master-data forms; overridable via
     * the "capitalAllocation" JSON section so table/field names stay in configuration, not code.
     */
    public static class CapitalAllocationConfig {
        private final String productTable, productIdField, productBusinessLineField, productStatusField;
        private final String investmentBusinessLineValue;
        private final String holdingTable, holdingCustomerField, holdingProductField, holdingRoleField, holdingStatusField, holdingEffectiveFromField;
        private final String investorRoleValue, activeStatusValue;
        private final String depositTable, depositCustomerField, depositAmountField, depositValueDateField;
        private final int shareScale;

        private CapitalAllocationConfig(String productTable, String productIdField, String productBusinessLineField,
                String productStatusField, String investmentBusinessLineValue, String holdingTable,
                String holdingCustomerField, String holdingProductField, String holdingRoleField, String holdingStatusField,
                String holdingEffectiveFromField, String investorRoleValue, String activeStatusValue, String depositTable,
                String depositCustomerField, String depositAmountField, String depositValueDateField, int shareScale) {
            this.productTable = productTable; this.productIdField = productIdField;
            this.productBusinessLineField = productBusinessLineField; this.productStatusField = productStatusField;
            this.investmentBusinessLineValue = investmentBusinessLineValue; this.holdingTable = holdingTable;
            this.holdingCustomerField = holdingCustomerField; this.holdingProductField = holdingProductField;
            this.holdingRoleField = holdingRoleField; this.holdingStatusField = holdingStatusField;
            this.holdingEffectiveFromField = holdingEffectiveFromField; this.investorRoleValue = investorRoleValue;
            this.activeStatusValue = activeStatusValue; this.depositTable = depositTable;
            this.depositCustomerField = depositCustomerField; this.depositAmountField = depositAmountField;
            this.depositValueDateField = depositValueDateField; this.shareScale = shareScale;
        }

        public static CapitalAllocationConfig defaults() {
            return new CapitalAllocationConfig(
                    "product", "productId", "businessLine", "status", "Investment",
                    "customerProductHolding", "customerId", "productId", "role", "status", "effectiveFrom",
                    "Investor", "Active",
                    "customer_deposit", "customerId", "amount", "valueDate", 0);
        }

        static CapitalAllocationConfig parse(JSONObject o) {
            return new CapitalAllocationConfig(
                    o.optString("productTable", "product"),
                    o.optString("productIdField", "productId"),
                    o.optString("productBusinessLineField", "businessLine"),
                    o.optString("productStatusField", "status"),
                    o.optString("investmentBusinessLineValue", "Investment"),
                    o.optString("holdingTable", "customerProductHolding"),
                    o.optString("holdingCustomerField", "customerId"),
                    o.optString("holdingProductField", "productId"),
                    o.optString("holdingRoleField", "role"),
                    o.optString("holdingStatusField", "status"),
                    o.optString("holdingEffectiveFromField", "effectiveFrom"),
                    o.optString("investorRoleValue", "Investor"),
                    o.optString("activeStatusValue", "Active"),
                    o.optString("depositTable", "customer_deposit"),
                    o.optString("depositCustomerField", "customerId"),
                    o.optString("depositAmountField", "amount"),
                    o.optString("depositValueDateField", "valueDate"),
                    o.optInt("shareScale", 0));
        }

        public String getProductTable() { return productTable; }
        public String getProductIdField() { return productIdField; }
        public String getProductBusinessLineField() { return productBusinessLineField; }
        public String getProductStatusField() { return productStatusField; }
        public String getInvestmentBusinessLineValue() { return investmentBusinessLineValue; }
        public String getHoldingTable() { return holdingTable; }
        public String getHoldingCustomerField() { return holdingCustomerField; }
        public String getHoldingProductField() { return holdingProductField; }
        public String getHoldingRoleField() { return holdingRoleField; }
        public String getHoldingStatusField() { return holdingStatusField; }
        public String getHoldingEffectiveFromField() { return holdingEffectiveFromField; }
        public String getInvestorRoleValue() { return investorRoleValue; }
        public String getActiveStatusValue() { return activeStatusValue; }
        public String getDepositTable() { return depositTable; }
        public String getDepositCustomerField() { return depositCustomerField; }
        public String getDepositAmountField() { return depositAmountField; }
        public String getDepositValueDateField() { return depositValueDateField; }
        public int getShareScale() { return shareScale; }
    }

    public static class ConditionalRequirement {
        private final String conditionField;
        private final String matchPattern;
        private final String excludePattern;
        private final String notEquals;
        private final String equals;
        private final boolean isNotEmpty;
        private final List<String> requiredFields;

        private ConditionalRequirement(String conditionField, String matchPattern,
                                       String excludePattern, String notEquals,
                                       String equals, boolean isNotEmpty,
                                       List<String> requiredFields) {
            this.conditionField = conditionField;
            this.matchPattern = matchPattern;
            this.excludePattern = excludePattern;
            this.notEquals = notEquals;
            this.equals = equals;
            this.isNotEmpty = isNotEmpty;
            this.requiredFields = Collections.unmodifiableList(requiredFields);
        }

        static ConditionalRequirement parse(JSONObject obj) {
            JSONObject cond = obj.optJSONObject("condition");
            String field = cond != null ? cond.optString("field", "") : "";
            String matchPat = cond != null ? cond.optString("matchPattern", "") : "";
            String exclPat = cond != null ? cond.optString("excludePattern", "") : "";
            String ne = cond != null ? cond.optString("notEquals", "") : "";
            String eq = cond != null ? cond.optString("equals", "") : "";
            boolean ine = cond != null && cond.optBoolean("isNotEmpty", false);

            List<String> rf = new ArrayList<>();
            JSONArray rfArr = obj.optJSONArray("requiredFields");
            if (rfArr != null) {
                for (int i = 0; i < rfArr.length(); i++) {
                    String f = rfArr.optString(i, "");
                    if (!f.isEmpty()) rf.add(f);
                }
            }
            return new ConditionalRequirement(field, matchPat, exclPat, ne, eq, ine, rf);
        }

        public String getConditionField() { return conditionField; }
        public String getMatchPattern() { return matchPattern; }
        public String getExcludePattern() { return excludePattern; }
        public String getNotEquals() { return notEquals; }
        public String getEquals() { return equals; }
        public boolean isNotEmpty() { return isNotEmpty; }
        public List<String> getRequiredFields() { return requiredFields; }

        /**
         * Evaluates this condition against the given field value.
         */
        public boolean matches(String fieldValue) {
            if (fieldValue == null) fieldValue = "";

            if (!matchPattern.isEmpty()) {
                return fieldValue.matches(matchPattern);
            }
            if (!excludePattern.isEmpty()) {
                return !fieldValue.matches(excludePattern);
            }
            if (!notEquals.isEmpty()) {
                return !notEquals.equals(fieldValue);
            }
            if (!equals.isEmpty()) {
                return equals.equals(fieldValue);
            }
            if (isNotEmpty) {
                return !fieldValue.isEmpty();
            }
            return false;
        }
    }

    public static class ReconciliationConfig {
        private final String amountField;
        private final String currencyField;
        private final String statementField;
        private final String originField;
        private final String manualOriginValue;
        private final String statusField;
        private final List<SourceTableConfig> sourceTables;
        private final Map<String, Double> tolerance;

        private ReconciliationConfig(String amountField, String currencyField,
                                     String statementField, String originField,
                                     String manualOriginValue, String statusField,
                                     List<SourceTableConfig> sourceTables,
                                     Map<String, Double> tolerance) {
            this.amountField = amountField;
            this.currencyField = currencyField;
            this.statementField = statementField;
            this.originField = originField;
            this.manualOriginValue = manualOriginValue;
            this.statusField = statusField;
            this.sourceTables = Collections.unmodifiableList(sourceTables);
            this.tolerance = Collections.unmodifiableMap(tolerance);
        }

        static ReconciliationConfig parse(JSONObject obj) {
            List<SourceTableConfig> tables = new ArrayList<>();
            JSONArray stArr = obj.optJSONArray("sourceTables");
            if (stArr != null) {
                for (int i = 0; i < stArr.length(); i++) {
                    JSONObject st = stArr.optJSONObject(i);
                    if (st != null) {
                        tables.add(new SourceTableConfig(
                                st.optString("tableName", ""),
                                st.optString("amountField", ""),
                                st.optString("currencyField", ""),
                                st.optString("statementField", "")
                        ));
                    }
                }
            }

            Map<String, Double> tol = new HashMap<>();
            JSONObject tolObj = obj.optJSONObject("tolerance");
            if (tolObj != null) {
                for (String key : tolObj.keySet()) {
                    tol.put(key, tolObj.optDouble(key, 0.05));
                }
            }

            return new ReconciliationConfig(
                    obj.optString("amountField", "total_amount"),
                    obj.optString("currencyField", "validated_currency"),
                    obj.optString("statementField", "statement_id"),
                    obj.optString("originField", "origin"),
                    obj.optString("manualOriginValue", "manual"),
                    obj.optString("statusField", "status"),
                    tables,
                    tol
            );
        }

        public String getAmountField() { return amountField; }
        public String getCurrencyField() { return currencyField; }
        public String getStatementField() { return statementField; }
        public String getOriginField() { return originField; }
        public String getManualOriginValue() { return manualOriginValue; }
        public String getStatusField() { return statusField; }
        public List<SourceTableConfig> getSourceTables() { return sourceTables; }
        public Map<String, Double> getToleranceMap() { return tolerance; }

        public double getTolerance(String currency) {
            Double t = tolerance.get(currency);
            if (t != null) return t;
            Double def = tolerance.get("_default");
            return def != null ? def : 0.05;
        }
    }

    public static class SourceTableConfig {
        private final String tableName;
        private final String amountField;
        private final String currencyField;
        private final String statementField;

        public SourceTableConfig(String tableName, String amountField,
                                 String currencyField, String statementField) {
            this.tableName = tableName;
            this.amountField = amountField;
            this.currencyField = currencyField;
            this.statementField = statementField;
        }

        public String getTableName() { return tableName; }
        public String getAmountField() { return amountField; }
        public String getCurrencyField() { return currencyField; }
        public String getStatementField() { return statementField; }
    }

    public static class SplitMergeConfig {
        private final String amountField;
        private final String feeField;
        private final String totalField;
        private final String eurAmountField;
        private final String fxRateField;
        private final String customerField;
        private final String originField;
        private final String parentIdField;
        private final String groupIdField;
        private final String sequenceField;
        private final String lineageNoteField;
        private final String statusField;

        private SplitMergeConfig(String amountField, String feeField, String totalField,
                                 String eurAmountField, String fxRateField,
                                 String customerField, String originField,
                                 String parentIdField, String groupIdField,
                                 String sequenceField, String lineageNoteField,
                                 String statusField) {
            this.amountField = amountField;
            this.feeField = feeField;
            this.totalField = totalField;
            this.eurAmountField = eurAmountField;
            this.fxRateField = fxRateField;
            this.customerField = customerField;
            this.originField = originField;
            this.parentIdField = parentIdField;
            this.groupIdField = groupIdField;
            this.sequenceField = sequenceField;
            this.lineageNoteField = lineageNoteField;
            this.statusField = statusField;
        }

        static SplitMergeConfig parse(JSONObject obj) {
            return new SplitMergeConfig(
                    obj.optString("amountField", "original_amount"),
                    obj.optString("feeField", "fee_amount"),
                    obj.optString("totalField", "total_amount"),
                    obj.optString("eurAmountField", "base_amount_eur"),
                    obj.optString("fxRateField", "fx_rate_to_eur"),
                    obj.optString("customerField", "customer_code"),
                    obj.optString("originField", "origin"),
                    obj.optString("parentIdField", "parent_enrichment_id"),
                    obj.optString("groupIdField", "group_id"),
                    obj.optString("sequenceField", "split_sequence"),
                    obj.optString("lineageNoteField", "lineage_note"),
                    obj.optString("statusField", "status")
            );
        }

        public String getAmountField() { return amountField; }
        public String getFeeField() { return feeField; }
        public String getTotalField() { return totalField; }
        public String getEurAmountField() { return eurAmountField; }
        public String getFxRateField() { return fxRateField; }
        public String getCustomerField() { return customerField; }
        public String getOriginField() { return originField; }
        public String getParentIdField() { return parentIdField; }
        public String getGroupIdField() { return groupIdField; }
        public String getSequenceField() { return sequenceField; }
        public String getLineageNoteField() { return lineageNoteField; }
        public String getStatusField() { return statusField; }
    }

    public static class ConfirmationConfig {
        private final String confirmedByField;
        private final String confirmedAtField;

        private ConfirmationConfig(String confirmedByField, String confirmedAtField) {
            this.confirmedByField = confirmedByField;
            this.confirmedAtField = confirmedAtField;
        }

        static ConfirmationConfig parse(JSONObject obj) {
            return new ConfirmationConfig(
                    obj.optString("confirmedByField", "confirmed_by"),
                    obj.optString("confirmedAtField", "confirmed_at")
            );
        }

        public String getConfirmedByField() { return confirmedByField; }
        public String getConfirmedAtField() { return confirmedAtField; }
    }

    public static class AllocationConfig {
        // Table names
        private final String secuTable;
        private final String lotTable;
        private final String positionTable;
        private final String portfolioTable;
        private final String customerTable;
        private final String costBasisTable;

        // Enrichment field mappings
        private final String enrichmentSourceField;
        private final String enrichmentAssetField;
        private final String enrichmentAssetIsinField;
        private final String enrichmentTypeField;
        private final String enrichmentStatusField;
        private final String enrichmentAllocStatusField;
        private final String enrichmentNotesField;
        private final String enrichmentTrxDateField;

        // Secu transaction field mappings
        private final String secuQuantityField;
        private final String secuPriceField;
        private final String secuFeeField;
        private final String secuAmountField;
        private final String secuTickerField;
        private final String secuCurrencyField;
        private final String secuEnrichmentLinkField;

        // Customer field mappings
        private final String customerDisplayNameField;
        private final String customerIsFundField;

        // Eligible types and statuses
        private final Set<String> eligibleTypes;
        private final Set<String> eligibleStatuses;

        // Tolerance
        private final double quantityTolerance;

        // ID generation
        private final String lotIdFormat;
        private final String lotIdEnvVar;
        private final String positionIdFormat;
        private final String positionIdEnvVar;
        private final String portfolioIdFormat;
        private final String portfolioIdEnvVar;

        private AllocationConfig(String secuTable, String lotTable, String positionTable,
                                 String portfolioTable, String customerTable, String costBasisTable,
                                 String enrichmentSourceField, String enrichmentAssetField,
                                 String enrichmentAssetIsinField, String enrichmentTypeField,
                                 String enrichmentStatusField, String enrichmentAllocStatusField,
                                 String enrichmentNotesField, String enrichmentTrxDateField,
                                 String secuQuantityField, String secuPriceField,
                                 String secuFeeField, String secuAmountField,
                                 String secuTickerField, String secuCurrencyField,
                                 String secuEnrichmentLinkField,
                                 String customerDisplayNameField, String customerIsFundField,
                                 Set<String> eligibleTypes, Set<String> eligibleStatuses,
                                 double quantityTolerance,
                                 String lotIdFormat, String lotIdEnvVar,
                                 String positionIdFormat, String positionIdEnvVar,
                                 String portfolioIdFormat, String portfolioIdEnvVar) {
            this.secuTable = secuTable;
            this.lotTable = lotTable;
            this.positionTable = positionTable;
            this.portfolioTable = portfolioTable;
            this.customerTable = customerTable;
            this.costBasisTable = costBasisTable;
            this.enrichmentSourceField = enrichmentSourceField;
            this.enrichmentAssetField = enrichmentAssetField;
            this.enrichmentAssetIsinField = enrichmentAssetIsinField;
            this.enrichmentTypeField = enrichmentTypeField;
            this.enrichmentStatusField = enrichmentStatusField;
            this.enrichmentAllocStatusField = enrichmentAllocStatusField;
            this.enrichmentNotesField = enrichmentNotesField;
            this.enrichmentTrxDateField = enrichmentTrxDateField;
            this.secuQuantityField = secuQuantityField;
            this.secuPriceField = secuPriceField;
            this.secuFeeField = secuFeeField;
            this.secuAmountField = secuAmountField;
            this.secuTickerField = secuTickerField;
            this.secuCurrencyField = secuCurrencyField;
            this.secuEnrichmentLinkField = secuEnrichmentLinkField;
            this.customerDisplayNameField = customerDisplayNameField;
            this.customerIsFundField = customerIsFundField;
            this.eligibleTypes = Collections.unmodifiableSet(eligibleTypes);
            this.eligibleStatuses = Collections.unmodifiableSet(eligibleStatuses);
            this.quantityTolerance = quantityTolerance;
            this.lotIdFormat = lotIdFormat;
            this.lotIdEnvVar = lotIdEnvVar;
            this.positionIdFormat = positionIdFormat;
            this.positionIdEnvVar = positionIdEnvVar;
            this.portfolioIdFormat = portfolioIdFormat;
            this.portfolioIdEnvVar = portfolioIdEnvVar;
        }

        public static AllocationConfig defaults() {
            return new AllocationConfig(
                    "secu_total_trx", "allocationLot", "portfolioPosition",
                    "customerPortfolio", "customer", "costBasisConfig",
                    "source_trx_id", "resolved_asset_id", "asset_isin",
                    "internal_type", "status", "fund_allocation_status",
                    "processing_notes", "transaction_date",
                    "quantity", "price", "fee", "amount", "ticker", "currency", "enrichment_id",
                    "displayName", "is_fund",
                    new HashSet<>(Arrays.asList("EQ_BUY", "EQ_SELL", "BOND_BUY", "BOND_SELL", "SEC_BUY", "SEC_SELL")),
                    new HashSet<>(Arrays.asList("enriched", "in_review", "adjusted", "ready", "paired")),
                    0.000001,
                    "LOT-??????", "allocationLotCounter",
                    "PP-??????", "portfolioPositionCounter",
                    "CPF-??????", "customerPortfolioCounter"
            );
        }

        static AllocationConfig parse(JSONObject obj) {
            Set<String> types = parseStringSet(obj.optJSONArray("eligibleTypes"),
                    new HashSet<>(Arrays.asList("EQ_BUY", "EQ_SELL", "BOND_BUY", "BOND_SELL", "SEC_BUY", "SEC_SELL")));
            Set<String> statuses = parseStringSet(obj.optJSONArray("eligibleStatuses"),
                    new HashSet<>(Arrays.asList("enriched", "in_review", "adjusted", "ready", "paired")));

            return new AllocationConfig(
                    obj.optString("secuTable", "secu_total_trx"),
                    obj.optString("lotTable", "allocationLot"),
                    obj.optString("positionTable", "portfolioPosition"),
                    obj.optString("portfolioTable", "customerPortfolio"),
                    obj.optString("customerTable", "customer"),
                    obj.optString("costBasisTable", "costBasisConfig"),
                    obj.optString("enrichmentSourceField", "source_trx_id"),
                    obj.optString("enrichmentAssetField", "resolved_asset_id"),
                    obj.optString("enrichmentAssetIsinField", "asset_isin"),
                    obj.optString("enrichmentTypeField", "internal_type"),
                    obj.optString("enrichmentStatusField", "status"),
                    obj.optString("enrichmentAllocStatusField", "fund_allocation_status"),
                    obj.optString("enrichmentNotesField", "processing_notes"),
                    obj.optString("enrichmentTrxDateField", "transaction_date"),
                    obj.optString("secuQuantityField", "quantity"),
                    obj.optString("secuPriceField", "price"),
                    obj.optString("secuFeeField", "fee"),
                    obj.optString("secuAmountField", "amount"),
                    obj.optString("secuTickerField", "ticker"),
                    obj.optString("secuCurrencyField", "currency"),
                    obj.optString("secuEnrichmentLinkField", "enrichment_id"),
                    obj.optString("customerDisplayNameField", "displayName"),
                    obj.optString("customerIsFundField", "is_fund"),
                    types, statuses,
                    obj.optDouble("quantityTolerance", 0.000001),
                    obj.optString("lotIdFormat", "LOT-??????"),
                    obj.optString("lotIdEnvVar", "allocationLotCounter"),
                    obj.optString("positionIdFormat", "PP-??????"),
                    obj.optString("positionIdEnvVar", "portfolioPositionCounter"),
                    obj.optString("portfolioIdFormat", "CPF-??????"),
                    obj.optString("portfolioIdEnvVar", "customerPortfolioCounter")
            );
        }

        static Set<String> parseStringSet(JSONArray arr, Set<String> defaults) {
            if (arr == null || arr.length() == 0) return defaults;
            Set<String> result = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (!s.isEmpty()) result.add(s);
            }
            return result;
        }

        public boolean isBuyType(String internalType) {
            return internalType != null && internalType.endsWith("_BUY");
        }

        public boolean isSellType(String internalType) {
            return internalType != null && internalType.endsWith("_SELL");
        }

        // Getters
        public String getSecuTable() { return secuTable; }
        public String getLotTable() { return lotTable; }
        public String getPositionTable() { return positionTable; }
        public String getPortfolioTable() { return portfolioTable; }
        public String getCustomerTable() { return customerTable; }
        public String getCostBasisTable() { return costBasisTable; }
        public String getEnrichmentSourceField() { return enrichmentSourceField; }
        public String getEnrichmentAssetField() { return enrichmentAssetField; }
        public String getEnrichmentAssetIsinField() { return enrichmentAssetIsinField; }
        public String getEnrichmentTypeField() { return enrichmentTypeField; }
        public String getEnrichmentStatusField() { return enrichmentStatusField; }
        public String getEnrichmentAllocStatusField() { return enrichmentAllocStatusField; }
        public String getEnrichmentNotesField() { return enrichmentNotesField; }
        public String getEnrichmentTrxDateField() { return enrichmentTrxDateField; }
        public String getSecuQuantityField() { return secuQuantityField; }
        public String getSecuPriceField() { return secuPriceField; }
        public String getSecuFeeField() { return secuFeeField; }
        public String getSecuAmountField() { return secuAmountField; }
        public String getSecuTickerField() { return secuTickerField; }
        public String getSecuCurrencyField() { return secuCurrencyField; }
        public String getSecuEnrichmentLinkField() { return secuEnrichmentLinkField; }
        public String getCustomerDisplayNameField() { return customerDisplayNameField; }
        public String getCustomerIsFundField() { return customerIsFundField; }
        public Set<String> getEligibleTypes() { return eligibleTypes; }
        public Set<String> getEligibleStatuses() { return eligibleStatuses; }
        public double getQuantityTolerance() { return quantityTolerance; }
        public String getLotIdFormat() { return lotIdFormat; }
        public String getLotIdEnvVar() { return lotIdEnvVar; }
        public String getPositionIdFormat() { return positionIdFormat; }
        public String getPositionIdEnvVar() { return positionIdEnvVar; }
        public String getPortfolioIdFormat() { return portfolioIdFormat; }
        public String getPortfolioIdEnvVar() { return portfolioIdEnvVar; }
    }

    public static class IncomeAllocationConfig {
        private final String incomeAllocTable;
        private final String incomeAllocIdFormat;
        private final String incomeAllocIdEnvVar;
        private final Set<String> eligibleTypes;
        private final Set<String> eligibleStatuses;
        private final String enrichmentTypeField;
        private final String enrichmentStatusField;
        private final String enrichmentAllocStatusField;
        private final String enrichmentAssetField;
        private final String enrichmentAmountField;
        private final String enrichmentCurrencyField;
        private final String enrichmentFxRateField;
        private final String enrichmentTrxDateField;
        private final String enrichmentNotesField;
        private final String lotTable;
        private final String lotAssetIdField;
        private final String lotCustomerIdField;
        private final String lotDirectionField;
        private final String lotQuantityField;
        private final String lotAllocationDateField;
        private final String lotAssetTickerField;
        private final String customerTable;
        private final String customerDisplayNameField;

        private IncomeAllocationConfig(
                String incomeAllocTable, String incomeAllocIdFormat, String incomeAllocIdEnvVar,
                Set<String> eligibleTypes, Set<String> eligibleStatuses,
                String enrichmentTypeField, String enrichmentStatusField,
                String enrichmentAllocStatusField, String enrichmentAssetField,
                String enrichmentAmountField, String enrichmentCurrencyField,
                String enrichmentFxRateField, String enrichmentTrxDateField,
                String enrichmentNotesField,
                String lotTable, String lotAssetIdField, String lotCustomerIdField,
                String lotDirectionField, String lotQuantityField,
                String lotAllocationDateField, String lotAssetTickerField,
                String customerTable, String customerDisplayNameField) {
            this.incomeAllocTable = incomeAllocTable;
            this.incomeAllocIdFormat = incomeAllocIdFormat;
            this.incomeAllocIdEnvVar = incomeAllocIdEnvVar;
            this.eligibleTypes = Collections.unmodifiableSet(eligibleTypes);
            this.eligibleStatuses = Collections.unmodifiableSet(eligibleStatuses);
            this.enrichmentTypeField = enrichmentTypeField;
            this.enrichmentStatusField = enrichmentStatusField;
            this.enrichmentAllocStatusField = enrichmentAllocStatusField;
            this.enrichmentAssetField = enrichmentAssetField;
            this.enrichmentAmountField = enrichmentAmountField;
            this.enrichmentCurrencyField = enrichmentCurrencyField;
            this.enrichmentFxRateField = enrichmentFxRateField;
            this.enrichmentTrxDateField = enrichmentTrxDateField;
            this.enrichmentNotesField = enrichmentNotesField;
            this.lotTable = lotTable;
            this.lotAssetIdField = lotAssetIdField;
            this.lotCustomerIdField = lotCustomerIdField;
            this.lotDirectionField = lotDirectionField;
            this.lotQuantityField = lotQuantityField;
            this.lotAllocationDateField = lotAllocationDateField;
            this.lotAssetTickerField = lotAssetTickerField;
            this.customerTable = customerTable;
            this.customerDisplayNameField = customerDisplayNameField;
        }

        public static IncomeAllocationConfig defaults() {
            return new IncomeAllocationConfig(
                    "incomeAllocation", "IA-??????", "incomeAllocCounter",
                    new HashSet<>(Arrays.asList("DIV_INCOME", "DIV_TAX", "BOND_INT")),
                    new HashSet<>(Arrays.asList("enriched", "in_review", "adjusted", "ready", "paired")),
                    "internal_type", "status", "fund_allocation_status",
                    "resolved_asset_id", "total_amount", "validated_currency",
                    "fx_rate_to_eur", "transaction_date", "processing_notes",
                    "allocationLot", "assetId", "customerId",
                    "direction", "quantity", "allocationDate", "assetTicker",
                    "customer", "displayName"
            );
        }

        static IncomeAllocationConfig parse(JSONObject obj) {
            Set<String> types = AllocationConfig.parseStringSet(obj.optJSONArray("eligibleTypes"),
                    new HashSet<>(Arrays.asList("DIV_INCOME", "DIV_TAX", "BOND_INT")));
            Set<String> statuses = AllocationConfig.parseStringSet(obj.optJSONArray("eligibleStatuses"),
                    new HashSet<>(Arrays.asList("enriched", "in_review", "adjusted", "ready", "paired")));

            return new IncomeAllocationConfig(
                    obj.optString("incomeAllocTable", "incomeAllocation"),
                    obj.optString("incomeAllocIdFormat", "IA-??????"),
                    obj.optString("incomeAllocIdEnvVar", "incomeAllocCounter"),
                    types, statuses,
                    obj.optString("enrichmentTypeField", "internal_type"),
                    obj.optString("enrichmentStatusField", "status"),
                    obj.optString("enrichmentAllocStatusField", "fund_allocation_status"),
                    obj.optString("enrichmentAssetField", "resolved_asset_id"),
                    obj.optString("enrichmentAmountField", "total_amount"),
                    obj.optString("enrichmentCurrencyField", "validated_currency"),
                    obj.optString("enrichmentFxRateField", "fx_rate_to_eur"),
                    obj.optString("enrichmentTrxDateField", "transaction_date"),
                    obj.optString("enrichmentNotesField", "processing_notes"),
                    obj.optString("lotTable", "allocationLot"),
                    obj.optString("lotAssetIdField", "assetId"),
                    obj.optString("lotCustomerIdField", "customerId"),
                    obj.optString("lotDirectionField", "direction"),
                    obj.optString("lotQuantityField", "quantity"),
                    obj.optString("lotAllocationDateField", "allocationDate"),
                    obj.optString("lotAssetTickerField", "assetTicker"),
                    obj.optString("customerTable", "customer"),
                    obj.optString("customerDisplayNameField", "displayName")
            );
        }

        public String getIncomeAllocTable() { return incomeAllocTable; }
        public String getIncomeAllocIdFormat() { return incomeAllocIdFormat; }
        public String getIncomeAllocIdEnvVar() { return incomeAllocIdEnvVar; }
        public Set<String> getEligibleTypes() { return eligibleTypes; }
        public Set<String> getEligibleStatuses() { return eligibleStatuses; }
        public String getEnrichmentTypeField() { return enrichmentTypeField; }
        public String getEnrichmentStatusField() { return enrichmentStatusField; }
        public String getEnrichmentAllocStatusField() { return enrichmentAllocStatusField; }
        public String getEnrichmentAssetField() { return enrichmentAssetField; }
        public String getEnrichmentAmountField() { return enrichmentAmountField; }
        public String getEnrichmentCurrencyField() { return enrichmentCurrencyField; }
        public String getEnrichmentFxRateField() { return enrichmentFxRateField; }
        public String getEnrichmentTrxDateField() { return enrichmentTrxDateField; }
        public String getEnrichmentNotesField() { return enrichmentNotesField; }
        public String getLotTable() { return lotTable; }
        public String getLotAssetIdField() { return lotAssetIdField; }
        public String getLotCustomerIdField() { return lotCustomerIdField; }
        public String getLotDirectionField() { return lotDirectionField; }
        public String getLotQuantityField() { return lotQuantityField; }
        public String getLotAllocationDateField() { return lotAllocationDateField; }
        public String getLotAssetTickerField() { return lotAssetTickerField; }
        public String getCustomerTable() { return customerTable; }
        public String getCustomerDisplayNameField() { return customerDisplayNameField; }
    }
}
