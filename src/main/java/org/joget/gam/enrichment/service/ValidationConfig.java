package org.joget.gam.enrichment.service;

import org.joget.commons.util.LogUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private ValidationConfig(String baseCurrency,
                             List<String> requiredFields,
                             List<ConditionalRequirement> conditionalRequirements,
                             JSONArray confidenceOverrides,
                             ReconciliationConfig reconciliation,
                             SplitMergeConfig splitMerge,
                             ConfirmationConfig confirmation) {
        this.baseCurrency = baseCurrency;
        this.requiredFields = Collections.unmodifiableList(requiredFields);
        this.conditionalRequirements = Collections.unmodifiableList(conditionalRequirements);
        this.confidenceOverrides = confidenceOverrides;
        this.reconciliation = reconciliation;
        this.splitMerge = splitMerge;
        this.confirmation = confirmation;
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

            return new ValidationConfig(baseCurrency, requiredFields, condReqs,
                    overrides, recon, sm, conf);

        } catch (Exception e) {
            LogUtil.warn(CLASS_NAME, "Failed to parse validationConfig: " + e.getMessage());
            return empty();
        }
    }

    private static ValidationConfig empty() {
        return new ValidationConfig("EUR", Collections.emptyList(),
                Collections.emptyList(), null, null, null, null);
    }

    public String getBaseCurrency() { return baseCurrency; }
    public List<String> getRequiredFields() { return requiredFields; }
    public List<ConditionalRequirement> getConditionalRequirements() { return conditionalRequirements; }
    public JSONArray getConfidenceOverrides() { return confidenceOverrides; }
    public ReconciliationConfig getReconciliation() { return reconciliation; }
    public SplitMergeConfig getSplitMerge() { return splitMerge; }
    public ConfirmationConfig getConfirmation() { return confirmation; }

    // ── Inner classes ──────────────────────────────────────────────────

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
}
