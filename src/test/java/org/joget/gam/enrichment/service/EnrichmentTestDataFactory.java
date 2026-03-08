package org.joget.gam.enrichment.service;

import org.joget.apps.form.model.FormRow;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared test helpers: FormRow builders, field maps, config JSON.
 */
public final class EnrichmentTestDataFactory {

    public static final String TABLE = "trxEnrichment";

    private EnrichmentTestDataFactory() {}

    /** Build a FormRow with id, status and version. */
    public static FormRow row(String id, String statusCode, int version) {
        FormRow r = new FormRow();
        r.setId(id);
        if (statusCode != null) r.setProperty("status", statusCode);
        r.setProperty("version", String.valueOf(version));
        return r;
    }

    /** Build a FormRow with extra properties. */
    public static FormRow row(String id, String statusCode, int version,
                              Map<String, String> extra) {
        FormRow r = row(id, statusCode, version);
        extra.forEach(r::setProperty);
        return r;
    }

    /** Mutable map from alternating key/value pairs. */
    public static Map<String, String> fields(String... pairs) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    /** Build a confidence-overrides JSONArray with one rule. */
    public static JSONArray confidenceOverrides(String triggerField,
                                                Map<String, String> setFields,
                                                String... clearFields) {
        JSONArray arr = new JSONArray();
        JSONObject rule = new JSONObject();
        rule.put("triggerField", triggerField);
        if (setFields != null && !setFields.isEmpty()) {
            rule.put("setFields", new JSONObject(setFields));
        }
        if (clearFields != null && clearFields.length > 0) {
            rule.put("clearFields", new JSONArray(clearFields));
        }
        arr.put(rule);
        return arr;
    }

    /** Minimal validationConfig JSON with required fields only. */
    public static String validationJson(String... requiredFields) {
        JSONObject root = new JSONObject();
        root.put("baseCurrency", "EUR");
        JSONArray rf = new JSONArray();
        for (String f : requiredFields) rf.put(f);
        root.put("requiredFields", rf);
        return root.toString();
    }

    /** validationConfig JSON with required fields + one conditional requirement. */
    public static String validationJsonWithConditional(String[] requiredFields,
                                                       String condField,
                                                       String operator,
                                                       Object operatorValue,
                                                       String... condRequiredFields) {
        JSONObject root = new JSONObject();
        root.put("baseCurrency", "EUR");
        JSONArray rf = new JSONArray();
        for (String f : requiredFields) rf.put(f);
        root.put("requiredFields", rf);

        JSONArray crs = new JSONArray();
        JSONObject cr = new JSONObject();
        JSONObject cond = new JSONObject();
        cond.put("field", condField);
        cond.put(operator, operatorValue);
        cr.put("condition", cond);
        JSONArray crf = new JSONArray();
        for (String f : condRequiredFields) crf.put(f);
        cr.put("requiredFields", crf);
        crs.put(cr);
        root.put("conditionalRequirements", crs);
        return root.toString();
    }
}
