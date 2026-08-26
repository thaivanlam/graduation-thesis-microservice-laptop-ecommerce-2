package com.ecommerce.product_service.unit;

import com.ecommerce.product_service.util.SKUGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SKUGenerator} - a pure static helper with no collaborators,
 * so it is exercised directly with no test doubles.
 *
 * <p>The generated SKU has the shape {@code CATEGORY-BRAND-PRODUCT-RANDOM}, where the
 * first three segments are derived deterministically from the arguments and only the
 * last segment is random. Every assertion below therefore targets a segment.</p>
 */
@DisplayName("Unit - SKUGenerator")
class SKUGeneratorTest {

    private static final Pattern SKU_SHAPE = Pattern.compile("^[A-Z]{3}-[A-Z0-9]*-[A-Z0-9]+-\\d{6}$");

    private static String[] segments(String sku) {
        return sku.split("-");
    }

    @Test
    @DisplayName("produces the documented four-segment shape")
    void producesFourSegmentShape() {
        String sku = SKUGenerator.generateSKU("Gaming Laptops", "Dell", "Dell XPS 13");

        assertThat(sku).matches(SKU_SHAPE);
        assertThat(segments(sku)).hasSize(4);
    }

    @Nested
    @DisplayName("category segment")
    class CategorySegment {

        @Test
        @DisplayName("takes the first three letters of the category, uppercased")
        void takesFirstThreeLetters() {
            assertThat(segments(SKUGenerator.generateSKU("Gaming Laptops", "MSI", "Katana"))[0])
                    .isEqualTo("GAM");
        }

        @Test
        @DisplayName("pads a short category with X so the segment is always three characters")
        void padsShortCategory() {
            assertThat(segments(SKUGenerator.generateSKU("PC", "MSI", "Katana"))[0]).isEqualTo("PCX");
            assertThat(segments(SKUGenerator.generateSKU("A", "MSI", "Katana"))[0]).isEqualTo("AXX");
        }

        @Test
        @DisplayName("falls back to XXX for a null or empty category")
        void fallsBackForMissingCategory() {
            assertThat(segments(SKUGenerator.generateSKU(null, "MSI", "Katana"))[0]).isEqualTo("XXX");
            assertThat(segments(SKUGenerator.generateSKU("", "MSI", "Katana"))[0]).isEqualTo("XXX");
        }

        @Test
        @DisplayName("ignores digits and punctuation when taking the prefix")
        void ignoresNonLetters() {
            assertThat(segments(SKUGenerator.generateSKU("2-in-1 Laptops", "MSI", "Katana"))[0])
                    .isEqualTo("INL");
        }
    }

    @Nested
    @DisplayName("brand segment")
    class BrandSegment {

        @Test
        @DisplayName("uppercases the brand and strips everything that is not A-Z0-9")
        void normalisesBrand() {
            assertThat(segments(SKUGenerator.generateSKU("Laptops", "Micro-Soft", "Surface"))[1])
                    .isEqualTo("MICROSOFT");
            assertThat(segments(SKUGenerator.generateSKU("Laptops", "hp", "Pavilion"))[1])
                    .isEqualTo("HP");
        }

        @Test
        @DisplayName("keeps digits that are part of the brand")
        void keepsDigits() {
            assertThat(segments(SKUGenerator.generateSKU("Laptops", "Acer5", "Aspire"))[1])
                    .isEqualTo("ACER5");
        }
    }

    @Nested
    @DisplayName("product segment")
    class ProductSegment {

        @Test
        @DisplayName("uses the first alphanumeric word of at least two characters")
        void usesFirstQualifyingWord() {
            // "Dell XPS 13" yields DELL, not XPS: the first qualifying word wins.
            assertThat(segments(SKUGenerator.generateSKU("Laptops", "Dell", "Dell XPS 13"))[2])
                    .isEqualTo("DELL");
        }

        @Test
        @DisplayName("truncates a long word to five characters")
        void truncatesLongWord() {
            assertThat(segments(SKUGenerator.generateSKU("Laptops", "Acer", "Predator Helios 300"))[2])
                    .isEqualTo("PREDA");
        }

        @Test
        @DisplayName("skips a leading one-character word")
        void skipsSingleCharacterWord() {
            assertThat(segments(SKUGenerator.generateSKU("Laptops", "LG", "X Gram 17"))[2])
                    .isEqualTo("GRAM");
        }

        @Test
        @DisplayName("falls back to XXX for a null or empty product name")
        void fallsBackForMissingProductName() {
            assertThat(segments(SKUGenerator.generateSKU("Laptops", "LG", null))[2]).isEqualTo("XXX");
            assertThat(segments(SKUGenerator.generateSKU("Laptops", "LG", ""))[2]).isEqualTo("XXX");
        }
    }

    @Nested
    @DisplayName("random segment")
    class RandomSegment {

        @Test
        @DisplayName("is always six digits, zero-padded")
        void isSixZeroPaddedDigits() {
            for (int i = 0; i < 200; i++) {
                assertThat(segments(SKUGenerator.generateSKU("Laptops", "MSI", "Katana"))[3])
                        .hasSize(6)
                        .containsOnlyDigits();
            }
        }

        @Test
        @DisplayName("varies between calls for identical input, so SKUs are practically unique")
        void variesBetweenCalls() {
            Set<String> generated = new HashSet<>();
            for (int i = 0; i < 100; i++) {
                generated.add(SKUGenerator.generateSKU("Laptops", "MSI", "Katana"));
            }
            // 100 draws from a 1,000,000-wide space: a collision here would be a real defect.
            assertThat(generated).hasSizeGreaterThanOrEqualTo(99);
        }
    }
}
