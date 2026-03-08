package org.joget.gam.enrichment.service;

import org.joget.apps.form.model.FormRow;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.joget.gam.enrichment.service.EnrichmentTestDataFactory.*;
import static org.junit.Assert.*;

public class EnrichmentServiceValidateTest {

    private EnrichmentService service;

    @Before
    public void setUp() {
        service = new EnrichmentService();
    }

    // ── Required fields ───────────────────────────────────────────────

    @Test
    public void allRequiredPresent_noErrors() {
        FormRow r = row("E001", "enriched", 0,
                Map.of("transaction_date", "2025-01-01", "debit_credit", "D"));
        ValidationConfig cfg = ValidationConfig.parse(
                validationJson("transaction_date", "debit_credit"));

        List<String> errors = service.validateRecord(r, cfg);
        assertTrue(errors.isEmpty());
    }

    @Test
    public void missingRequired_returnsError() {
        FormRow r = row("E001", "enriched", 0, Map.of("transaction_date", "2025-01-01"));
        ValidationConfig cfg = ValidationConfig.parse(
                validationJson("transaction_date", "debit_credit"));

        List<String> errors = service.validateRecord(r, cfg);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("debit_credit"));
    }

    @Test
    public void multipleRequiredMissing() {
        FormRow r = row("E001", "enriched", 0);
        ValidationConfig cfg = ValidationConfig.parse(
                validationJson("transaction_date", "debit_credit", "original_amount"));

        List<String> errors = service.validateRecord(r, cfg);
        assertEquals(3, errors.size());
    }

    // ── Conditional: matchPattern ─────────────────────────────────────

    @Test
    public void matchPattern_triggered() {
        FormRow r = row("E001", "enriched", 0,
                Map.of("internal_type", "FX_FORWARD"));
        ValidationConfig cfg = ValidationConfig.parse(
                validationJsonWithConditional(
                        new String[0], "internal_type", "matchPattern", "^FX_.*",
                        "fx_rate_to_eur"));

        List<String> errors = service.validateRecord(r, cfg);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("fx_rate_to_eur"));
    }

    @Test
    public void matchPattern_notTriggered() {
        FormRow r = row("E001", "enriched", 0,
                Map.of("internal_type", "CASH"));
        ValidationConfig cfg = ValidationConfig.parse(
                validationJsonWithConditional(
                        new String[0], "internal_type", "matchPattern", "^FX_.*",
                        "fx_rate_to_eur"));

        List<String> errors = service.validateRecord(r, cfg);
        assertTrue(errors.isEmpty());
    }

    // ── Conditional: excludePattern ───────────────────────────────────

    @Test
    public void excludePattern_triggered() {
        // excludePattern means: condition fires when value does NOT match the pattern
        FormRow r = row("E001", "enriched", 0,
                Map.of("debit_credit", "D")); // "D" does not match "^SKIP$"
        ValidationConfig cfg = ValidationConfig.parse(
                validationJsonWithConditional(
                        new String[0], "debit_credit", "excludePattern", "^SKIP$",
                        "fee_amount"));

        List<String> errors = service.validateRecord(r, cfg);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("fee_amount"));
    }

    // ── Conditional: notEquals ────────────────────────────────────────

    @Test
    public void notEquals_triggered() {
        FormRow r = row("E001", "enriched", 0,
                Map.of("validated_currency", "USD"));
        ValidationConfig cfg = ValidationConfig.parse(
                validationJsonWithConditional(
                        new String[0], "validated_currency", "notEquals", "EUR",
                        "fx_rate_to_eur"));

        List<String> errors = service.validateRecord(r, cfg);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("fx_rate_to_eur"));
    }

    // ── Conditional: isNotEmpty ───────────────────────────────────────

    @Test
    public void isNotEmpty_triggered() {
        FormRow r = row("E001", "enriched", 0,
                Map.of("has_fee", "true")); // non-empty → isNotEmpty fires
        ValidationConfig cfg = ValidationConfig.parse(
                validationJsonWithConditional(
                        new String[0], "has_fee", "isNotEmpty", true,
                        "fee_amount"));

        List<String> errors = service.validateRecord(r, cfg);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("fee_amount"));
    }
}
