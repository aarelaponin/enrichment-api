package org.joget.gam.enrichment.service;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the pure quantity-split at the heart of automatic trade allocation
 * ({@link EnrichmentService#splitByShare}). BUY splits by capital basis, SELL by holdings
 * basis; rounding is largest-remainder so the parts reconcile exactly to the trade quantity.
 */
public class EnrichmentServiceAutoAllocateTest {

    private LinkedHashMap<String, BigDecimal> basis(Object... kv) {
        LinkedHashMap<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], new BigDecimal(kv[i + 1].toString()));
        return m;
    }

    @Test
    public void splitsWholeSharesByBasis() {
        LinkedHashMap<String, BigDecimal> r =
                EnrichmentService.splitByShare(basis("A", "6000", "B", "4000"), new BigDecimal("100"), 0);
        assertEquals(0, new BigDecimal("60").compareTo(r.get("A")));
        assertEquals(0, new BigDecimal("40").compareTo(r.get("B")));
    }

    @Test
    public void remainderGoesToLargestAndSumsExactly() {
        LinkedHashMap<String, BigDecimal> r =
                EnrichmentService.splitByShare(basis("A", "100", "B", "100", "C", "100"), new BigDecimal("10"), 0);
        BigDecimal sum = r.get("A").add(r.get("B")).add(r.get("C"));
        assertEquals(0, new BigDecimal("10").compareTo(sum));
        assertEquals(0, new BigDecimal("4").compareTo(r.get("A"))); // tie -> smallest id takes remainder
        assertEquals(0, new BigDecimal("3").compareTo(r.get("B")));
    }

    @Test
    public void excludesZeroBasisHolders() {
        LinkedHashMap<String, BigDecimal> r =
                EnrichmentService.splitByShare(basis("A", "0", "B", "50"), new BigDecimal("10"), 0);
        assertFalse(r.containsKey("A"));
        assertEquals(0, new BigDecimal("10").compareTo(r.get("B")));
    }

    @Test
    public void emptyWhenNoPositiveBasis() {
        assertTrue(EnrichmentService.splitByShare(basis("A", "0", "B", "0"), new BigDecimal("10"), 0).isEmpty());
        assertTrue(EnrichmentService.splitByShare(basis("A", "5"), new BigDecimal("0"), 0).isEmpty());
    }

    @Test
    public void supportsFractionalScale() {
        LinkedHashMap<String, BigDecimal> r =
                EnrichmentService.splitByShare(basis("A", "1", "B", "3"), new BigDecimal("100"), 2);
        assertEquals(0, new BigDecimal("25.00").compareTo(r.get("A")));
        assertEquals(0, new BigDecimal("75.00").compareTo(r.get("B")));
    }
}
