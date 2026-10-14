package com.icesi.ingesoftv.productapi.service;

import com.icesi.ingesoftv.productapi.model.Product;
import com.icesi.ingesoftv.productapi.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
@Transactional
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<Product> findAll() {
        return productRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Product> findById(Long id) {
        return productRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Product> findByCategory(String category) {
        return productRepository.findByCategoryIgnoreCase(category);
    }

    public Product save(Product product) {
        return productRepository.save(product);
    }

    public Optional<Product> update(Long id, Product updatedProduct) {
        return productRepository.findById(id).map(existing -> {
            existing.setName(updatedProduct.getName());
            existing.setDescription(updatedProduct.getDescription());
            existing.setPrice(updatedProduct.getPrice());
            existing.setStock(updatedProduct.getStock());
            existing.setCategory(updatedProduct.getCategory());
            return productRepository.save(existing);
        });
    }

    public boolean deleteById(Long id) {
        if (productRepository.existsById(id)) {
            productRepository.deleteById(id);
            return true;
        }
        return false;
    }

    /**
     * Calcula el precio final con descuento aplicado por categoría o promoción.
     * Remediación CWE-476: se valida el Optional con orElseThrow() en lugar de get().
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateDiscountedPrice(Long id, BigDecimal discountPercentage) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Producto no encontrado: " + id));

        if (discountPercentage == null || discountPercentage.compareTo(BigDecimal.ZERO) <= 0) {
            return product.getPrice();
        }
        BigDecimal discountFactor = BigDecimal.ONE.subtract(discountPercentage.divide(new BigDecimal("100")));
        return product.getPrice().multiply(discountFactor);
    }
}
