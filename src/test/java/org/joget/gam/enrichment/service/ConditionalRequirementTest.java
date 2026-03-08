package org.joget.gam.enrichment.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ConditionalRequirementTest {

    // ── matchPattern ──────────────────────────────────────────────────

    @Test
    public void matchPattern_matches() {
        ValidationConfig.ConditionalRequirement cr = parse("matchPattern", "^FX_.*");
        assertTrue(cr.matches("FX_FORWARD"));
    }

    @Test
    public void matchPattern_noMatch() {
        ValidationConfig.ConditionalRequirement cr = parse("matchPattern", "^FX_.*");
        assertFalse(cr.matches("CASH"));
    }

    // ── excludePattern ────────────────────────────────────────────────

    @Test
    public void excludePattern_excluded() {
        ValidationConfig.ConditionalRequirement cr = parse("excludePattern", "^SKIP$");
        assertFalse(cr.matches("SKIP"));
    }

    @Test
    public void excludePattern_notExcluded() {
        ValidationConfig.ConditionalRequirement cr = parse("excludePattern", "^SKIP$");
        assertTrue(cr.matches("KEEP"));
    }

    // ── notEquals ─────────────────────────────────────────────────────

    @Test
    public void notEquals_different() {
        ValidationConfig.ConditionalRequirement cr = parse("notEquals", "EUR");
        assertTrue(cr.matches("USD"));
    }

    @Test
    public void notEquals_same() {
        ValidationConfig.ConditionalRequirement cr = parse("notEquals", "EUR");
        assertFalse(cr.matches("EUR"));
    }

    // ── equals ────────────────────────────────────────────────────────

    @Test
    public void equals_match() {
        ValidationConfig.ConditionalRequirement cr = parse("equals", "D");
        assertTrue(cr.matches("D"));
        assertFalse(cr.matches("C"));
    }

    // ── isNotEmpty ────────────────────────────────────────────────────

    @Test
    public void isNotEmpty_nonEmpty() {
        ValidationConfig.ConditionalRequirement cr = parseBool("isNotEmpty", true);
        assertTrue(cr.matches("something"));
        assertFalse(cr.matches(""));
        assertFalse(cr.matches(null));
    }

    // ── helpers ───────────────────────────────────────────────────────

    private static ValidationConfig.ConditionalRequirement parse(String operator, String value) {
        JSONObject obj = new JSONObject();
        obj.put("condition", new JSONObject()
                .put("field", "test_field")
                .put(operator, value));
        obj.put("requiredFields", new JSONArray().put("required_a"));
        return ValidationConfig.ConditionalRequirement.parse(obj);
    }

    private static ValidationConfig.ConditionalRequirement parseBool(String operator, boolean value) {
        JSONObject obj = new JSONObject();
        obj.put("condition", new JSONObject()
                .put("field", "test_field")
                .put(operator, value));
        obj.put("requiredFields", new JSONArray().put("required_a"));
        return ValidationConfig.ConditionalRequirement.parse(obj);
    }
}
