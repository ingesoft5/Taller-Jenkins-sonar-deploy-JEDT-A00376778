package com.icesi.ingesoftv.productapi.service;

import org.springframework.stereotype.Component;
import java.security.SecureRandom;

@Component
public class ProductSkuGenerator {

    // Remediación CWE-330: SecureRandom en lugar de java.util.Random (predecible)
    private final SecureRandom random = new SecureRandom();

    public String generateSku(String category, Long productId) {
        int code = random.nextInt(900000) + 100000;
        String prefix = (category != null && category.length() >= 3)
                ? category.substring(0, 3).toUpperCase()
                : "GEN";
        return prefix + "-" + productId + "-" + code;
    }
}
