package org.joget.gam.enrichment.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ValidationConfigTest {

    @Test
    public void parse_null_returnsEmpty() {
        ValidationConfig cfg = ValidationConfig.parse(null);
        assertEquals("EUR", cfg.getBaseCurrency());
        assertTrue(cfg.getRequiredFields().isEmpty());
        assertTrue(cfg.getConditionalRequirements().isEmpty());
        assertNull(cfg.getConfidenceOverrides());
        assertNull(cfg.getReconciliation());
        assertNull(cfg.getSplitMerge());
        assertNull(cfg.getConfirmation());
    }

    @Test
    public void parse_emptyString_returnsEmpty() {
        ValidationConfig cfg = ValidationConfig.parse("   ");
        assertEquals("EUR", cfg.getBaseCurrency());
        assertTrue(cfg.getRequiredFields().isEmpty());
    }

    @Test
    public void parse_invalidJson_returnsEmpty() {
        ValidationConfig cfg = ValidationConfig.parse("not json at all");
        assertEquals("EUR", cfg.getBaseCurrency());
        assertTrue(cfg.getRequiredFields().isEmpty());
    }

    @Test
    public void parse_baseCurrency() {
        String json = new JSONObject().put("baseCurrency", "USD").toString();
        ValidationConfig cfg = ValidationConfig.parse(json);
        assertEquals("USD", cfg.getBaseCurrency());
    }

    @Test
    public void parse_requiredFields() {
        JSONObject root = new JSONObject();
        root.put("requiredFields", new JSONArray()
                .put("transaction_date").put("debit_credit"));
        ValidationConfig cfg = ValidationConfig.parse(root.toString());
        assertEquals(2, cfg.getRequiredFields().size());
        assertTrue(cfg.getRequiredFields().contains("transaction_date"));
        assertTrue(cfg.getRequiredFields().contains("debit_credit"));
    }

    @Test
    public void parse_conditionalRequirements() {
        JSONObject root = new JSONObject();
        JSONArray crs = new JSONArray();
        JSONObject cr = new JSONObject();
        cr.put("condition", new JSONObject()
                .put("field", "debit_credit")
                .put("equals", "D"));
        cr.put("requiredFields", new JSONArray().put("fee_amount"));
        crs.put(cr);
        root.put("conditionalRequirements", crs);

        ValidationConfig cfg = ValidationConfig.parse(root.toString());
        assertEquals(1, cfg.getConditionalRequirements().size());
        ValidationConfig.ConditionalRequirement cond = cfg.getConditionalRequirements().get(0);
        assertEquals("debit_credit", cond.getConditionField());
        assertEquals("D", cond.getEquals());
        assertEquals(1, cond.getRequiredFields().size());
        assertEquals("fee_amount", cond.getRequiredFields().get(0));
    }

    @Test
    public void parse_confidenceOverrides() {
        JSONObject root = new JSONObject();
        JSONArray overrides = new JSONArray();
        JSONObject rule = new JSONObject();
        rule.put("triggerField", "internal_type");
        rule.put("setFields", new JSONObject().put("confidence_score", "100"));
        overrides.put(rule);
        root.put("confidenceOverrides", overrides);

        ValidationConfig cfg = ValidationConfig.parse(root.toString());
        assertNotNull(cfg.getConfidenceOverrides());
        assertEquals(1, cfg.getConfidenceOverrides().length());
        assertEquals("internal_type",
                cfg.getConfidenceOverrides().getJSONObject(0).getString("triggerField"));
    }

    @Test
    public void parse_reconciliation() {
        JSONObject root = new JSONObject();
        JSONObject recon = new JSONObject();
        recon.put("amountField", "total_amount");
        recon.put("currencyField", "validated_currency");
        recon.put("statementField", "statement_id");
        recon.put("tolerance", new JSONObject().put("EUR", 0.10).put("_default", 0.05));
        recon.put("sourceTables", new JSONArray().put(
                new JSONObject()
                        .put("tableName", "bank_trx")
                        .put("amountField", "amount")
                        .put("currencyField", "currency")
                        .put("statementField", "stmt_id")));
        root.put("reconciliation", recon);

        ValidationConfig cfg = ValidationConfig.parse(root.toString());
        ValidationConfig.ReconciliationConfig rc = cfg.getReconciliation();
        assertNotNull(rc);
        assertEquals("total_amount", rc.getAmountField());
        assertEquals("validated_currency", rc.getCurrencyField());
        assertEquals(0.10, rc.getTolerance("EUR"), 0.001);
        assertEquals(0.05, rc.getTolerance("USD"), 0.001); // falls back to _default
        assertEquals(1, rc.getSourceTables().size());
        assertEquals("bank_trx", rc.getSourceTables().get(0).getTableName());
    }

    @Test
    public void parse_splitMerge() {
        JSONObject root = new JSONObject();
        JSONObject sm = new JSONObject();
        sm.put("amountField", "original_amount");
        sm.put("feeField", "fee_amount");
        sm.put("totalField", "total_amount");
        sm.put("eurAmountField", "base_amount_eur");
        sm.put("customerField", "customer_code");
        root.put("splitMerge", sm);

        ValidationConfig cfg = ValidationConfig.parse(root.toString());
        ValidationConfig.SplitMergeConfig smc = cfg.getSplitMerge();
        assertNotNull(smc);
        assertEquals("original_amount", smc.getAmountField());
        assertEquals("fee_amount", smc.getFeeField());
        assertEquals("total_amount", smc.getTotalField());
        assertEquals("customer_code", smc.getCustomerField());
    }

    @Test
    public void parse_confirmation() {
        JSONObject root = new JSONObject();
        root.put("confirmation", new JSONObject()
                .put("confirmedByField", "confirmed_by")
                .put("confirmedAtField", "confirmed_at"));

        ValidationConfig cfg = ValidationConfig.parse(root.toString());
        ValidationConfig.ConfirmationConfig cc = cfg.getConfirmation();
        assertNotNull(cc);
        assertEquals("confirmed_by", cc.getConfirmedByField());
        assertEquals("confirmed_at", cc.getConfirmedAtField());
    }
}
