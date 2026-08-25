package org.joget.gam.enrichment.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class AllocationConfigTest {

    @Test
    public void defaults_returnsAllDefaults() {
        ValidationConfig.AllocationConfig ac = ValidationConfig.AllocationConfig.defaults();
        assertEquals("secu_total_trx", ac.getSecuTable());
        assertEquals("allocationLot", ac.getLotTable());
        assertEquals("portfolioPosition", ac.getPositionTable());
        assertEquals("customerPortfolio", ac.getPortfolioTable());
        assertEquals("customer", ac.getCustomerTable());
        assertEquals("costBasisConfig", ac.getCostBasisTable());
        assertEquals("source_trx_id", ac.getEnrichmentSourceField());
        assertEquals("resolved_asset_id", ac.getEnrichmentAssetField());
        assertEquals("internal_type", ac.getEnrichmentTypeField());
        assertEquals("status", ac.getEnrichmentStatusField());
        assertEquals("fund_allocation_status", ac.getEnrichmentAllocStatusField());
        assertEquals("processing_notes", ac.getEnrichmentNotesField());
        assertEquals("transaction_date", ac.getEnrichmentTrxDateField());
        assertEquals("quantity", ac.getSecuQuantityField());
        assertEquals("price", ac.getSecuPriceField());
        assertEquals("fee", ac.getSecuFeeField());
        assertEquals("displayName", ac.getCustomerDisplayNameField());
        assertEquals("is_fund", ac.getCustomerIsFundField());
        assertEquals(0.000001, ac.getQuantityTolerance(), 0.0000001);
        assertEquals("LOT-??????", ac.getLotIdFormat());
        assertEquals("PP-??????", ac.getPositionIdFormat());
        assertEquals("CPF-??????", ac.getPortfolioIdFormat());
    }

    @Test
    public void defaults_eligibleTypes() {
        ValidationConfig.AllocationConfig ac = ValidationConfig.AllocationConfig.defaults();
        assertTrue(ac.getEligibleTypes().contains("EQ_BUY"));
        assertTrue(ac.getEligibleTypes().contains("EQ_SELL"));
        assertTrue(ac.getEligibleTypes().contains("BOND_BUY"));
        assertTrue(ac.getEligibleTypes().contains("BOND_SELL"));
        assertTrue(ac.getEligibleTypes().contains("SEC_BUY"));
        assertTrue(ac.getEligibleTypes().contains("SEC_SELL"));
        assertEquals(6, ac.getEligibleTypes().size());
    }

    @Test
    public void defaults_eligibleStatuses() {
        ValidationConfig.AllocationConfig ac = ValidationConfig.AllocationConfig.defaults();
        assertTrue(ac.getEligibleStatuses().contains("enriched"));
        assertTrue(ac.getEligibleStatuses().contains("in_review"));
        assertTrue(ac.getEligibleStatuses().contains("adjusted"));
        assertTrue(ac.getEligibleStatuses().contains("ready"));
        assertTrue(ac.getEligibleStatuses().contains("paired"));
        assertEquals(5, ac.getEligibleStatuses().size());
    }

    @Test
    public void parse_customTableNames() {
        JSONObject obj = new JSONObject();
        obj.put("secuTable", "my_secu");
        obj.put("lotTable", "my_lots");
        obj.put("positionTable", "my_positions");

        ValidationConfig.AllocationConfig ac = ValidationConfig.AllocationConfig.parse(obj);
        assertEquals("my_secu", ac.getSecuTable());
        assertEquals("my_lots", ac.getLotTable());
        assertEquals("my_positions", ac.getPositionTable());
        // unspecified fields use defaults
        assertEquals("customerPortfolio", ac.getPortfolioTable());
    }

    @Test
    public void parse_customEligibleTypes() {
        JSONObject obj = new JSONObject();
        obj.put("eligibleTypes", new JSONArray().put("EQ_BUY").put("EQ_SELL"));

        ValidationConfig.AllocationConfig ac = ValidationConfig.AllocationConfig.parse(obj);
        assertEquals(2, ac.getEligibleTypes().size());
        assertTrue(ac.getEligibleTypes().contains("EQ_BUY"));
        assertTrue(ac.getEligibleTypes().contains("EQ_SELL"));
        assertFalse(ac.getEligibleTypes().contains("BOND_BUY"));
    }

    @Test
    public void isBuyType_trueForBuyTypes() {
        ValidationConfig.AllocationConfig ac = ValidationConfig.AllocationConfig.defaults();
        assertTrue(ac.isBuyType("EQ_BUY"));
        assertTrue(ac.isBuyType("BOND_BUY"));
        assertTrue(ac.isBuyType("SEC_BUY"));
    }

    @Test
    public void isBuyType_falseForSellTypes() {
        ValidationConfig.AllocationConfig ac = ValidationConfig.AllocationConfig.defaults();
        assertFalse(ac.isBuyType("EQ_SELL"));
        assertFalse(ac.isBuyType("BOND_SELL"));
        assertFalse(ac.isBuyType(null));
    }

    @Test
    public void isSellType_trueForSellTypes() {
        ValidationConfig.AllocationConfig ac = ValidationConfig.AllocationConfig.defaults();
        assertTrue(ac.isSellType("EQ_SELL"));
        assertTrue(ac.isSellType("BOND_SELL"));
        assertTrue(ac.isSellType("SEC_SELL"));
    }

    @Test
    public void isSellType_falseForBuyTypes() {
        ValidationConfig.AllocationConfig ac = ValidationConfig.AllocationConfig.defaults();
        assertFalse(ac.isSellType("EQ_BUY"));
        assertFalse(ac.isSellType(null));
    }

    @Test
    public void getAllocation_returnsDefaultsWhenMissing() {
        ValidationConfig cfg = ValidationConfig.parse("{}");
        ValidationConfig.AllocationConfig ac = cfg.getAllocation();
        assertNotNull(ac);
        assertEquals("secu_total_trx", ac.getSecuTable());
    }

    @Test
    public void getAllocation_returnsParsedWhenPresent() {
        JSONObject root = new JSONObject();
        root.put("allocation", new JSONObject().put("secuTable", "custom_secu"));
        ValidationConfig cfg = ValidationConfig.parse(root.toString());
        ValidationConfig.AllocationConfig ac = cfg.getAllocation();
        assertEquals("custom_secu", ac.getSecuTable());
    }
}
