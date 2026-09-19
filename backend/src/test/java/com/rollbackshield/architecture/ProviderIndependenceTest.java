package com.rollbackshield.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that provider-specific branching did not leak into the core when
 * Kubernetes was added: the core application/domain packages must not name
 * runtime providers at all. Provider behavior is reached exclusively through
 * {@code ConnectorRegistry} and capability ports.
 *
 * This is a source scan rather than an ArchUnit rule because the failure
 * mode is a string/enum literal ("if runtime == KUBERNETES"), not an import.
 */
class ProviderIndependenceTest {

    /** Core application packages that must stay provider-neutral. */
    private static final List<String> CORE_PACKAGES = List.of(
        "integrations/application",
        "deployment/application",
        "reversibility/application",
        "rollback/application",
        "servicemapping/application",
        "release/application"
    );

    /**
     * Branching forms that must not appear in core application code. Enum
     * definitions may name providers; core logic may not compare against them.
     */
    private static final List<java.util.regex.Pattern> FORBIDDEN_PATTERNS = List.of(
        java.util.regex.Pattern.compile("ConnectorType\\.[A-Z_]+"),
        java.util.regex.Pattern.compile("\\bKUBERNETES\\b"),
        java.util.regex.Pattern.compile("\\bFABRIC8\\b"),
        java.util.regex.Pattern.compile("io\\.fabric8"),
        java.util.regex.Pattern.compile("software\\.amazon")
    );

    @Test
    void coreCodeContainsNoProviderBranchingLiterals() throws IOException {
        Path backendSource = locateBackendSource();
        List<String> violations = new ArrayList<>();
        for (String packagePath : CORE_PACKAGES) {
            Path directory = backendSource.resolve(packagePath);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(directory)) {
                files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> collectViolations(path, violations));
            }
        }
        assertThat(violations)
            .as("provider names must not appear in core packages; route via ConnectorRegistry instead")
            .isEmpty();
    }

    private static void collectViolations(Path file, List<String> violations) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            for (java.util.regex.Pattern pattern : FORBIDDEN_PATTERNS) {
                if (pattern.matcher(content).find()) {
                    violations.add(file.getFileName() + " matches " + pattern.pattern());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + file, e);
        }
    }

    private static Path locateBackendSource() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path candidate = workingDirectory.resolve("src/main/java/com/rollbackshield");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        // Surefire may run with the module root; walk up defensively.
        Path current = workingDirectory;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            Path nested = current.resolve("src/main/java/com/rollbackshield");
            if (Files.isDirectory(nested)) {
                return nested;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate backend sources from " + workingDirectory);
    }

    @Test
    void forbiddenPatternListItselfIsNonEmpty() {
        assertThat(String.join(",", FORBIDDEN_PATTERNS.stream()
            .map(java.util.regex.Pattern::pattern).toList()).toUpperCase(Locale.ROOT))
            .isNotEmpty();
    }
}
