package com.ecommerce.product_service.unit;

import com.ecommerce.product_service.imageutil.ImagePathUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ImagePathUtils}, the helper that decides where uploaded product
 * images are written. Its contract matters because the same property (project.image) is
 * read both when the service runs from the module directory and when it runs from the
 * repository root.
 */
@DisplayName("Unit - ImagePathUtils")
class ImagePathUtilsTest {

    @Test
    @DisplayName("returns an absolute configured path unchanged apart from normalisation")
    void keepsAbsolutePath() {
        Path absolute = Paths.get("").toAbsolutePath().resolve("uploads").resolve("..").resolve("images");

        Path resolved = ImagePathUtils.resolveConfiguredPath(absolute.toString());

        assertThat(resolved).isAbsolute();
        assertThat(resolved).isEqualTo(absolute.normalize());
        assertThat(resolved.toString()).doesNotContain("..");
    }

    @Test
    @DisplayName("turns a relative configured path into an absolute one")
    void resolvesRelativePath() {
        Path resolved = ImagePathUtils.resolveConfiguredPath("images/products/");

        assertThat(resolved).isAbsolute();
        // endsWithRaw compares path elements without touching the file system; endsWith
        // would canonicalise and fail because the directory does not exist yet.
        assertThat(resolved).endsWithRaw(Paths.get("images", "products"));
    }

    @Test
    @DisplayName("normalises redundant segments in a relative path")
    void normalisesRelativePath() {
        Path resolved = ImagePathUtils.resolveConfiguredPath("images/../images/products");

        assertThat(resolved.toString()).doesNotContain("..");
        assertThat(resolved).endsWithRaw(Paths.get("images", "products"));
    }

    @Test
    @DisplayName("resolves a relative path underneath the working directory")
    void anchorsRelativePathToWorkingDirectory() {
        Path resolved = ImagePathUtils.resolveConfiguredPath("images");

        // Surefire runs with the module directory as the working directory, so the anchor
        // is product-service itself; the branch that appends "product-service" only fires
        // when the process is started from the repository root.
        assertThat(resolved.getParent()).isEqualTo(Paths.get("").toAbsolutePath());
    }
}
