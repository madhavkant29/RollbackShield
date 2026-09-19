package com.rollbackshield.connectors.github.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rollbackshield.connectors.github.adapter.GitHubApiClient.GitHubApiException;
import com.rollbackshield.integrations.domain.ConnectionTestResult;
import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.MigrationFile;
import com.rollbackshield.integrations.domain.connector.CapabilityProvider;
import com.rollbackshield.integrations.domain.connector.Connector;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DiscoveryContributionPort;
import com.rollbackshield.integrations.domain.connector.RepositoryInspection;
import com.rollbackshield.integrations.domain.connector.SourceMetadataPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real GitHub connectivity over the REST API: repository discovery,
 * metadata, commits, tags, and file contents (Dockerfiles, deployment files,
 * Flyway migrations). Authentication is GitHub App installation credentials
 * (minted as short-lived installation tokens) or, for local/dev, a token
 * resolved from a secret store. No repository data is ever fabricated: empty
 * lists mean GitHub returned empty lists.
 */
@Component
public class GitHubConnector implements Connector, CapabilityProvider, SourceMetadataPort,
    DiscoveryContributionPort {

    private static final int MAX_REPO_PAGES = 3;
    private static final int PER_PAGE = 100;
    private static final int MAX_MIGRATION_FILES = 200;

    private final GitHubApiClient api;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedToken> appTokens = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public GitHubConnector(@Value("${rollbackshield.github.api-base-url:}") String apiBaseUrl) {
        this.api = new GitHubApiClient(apiBaseUrl);
    }

    GitHubConnector(GitHubApiClient api) {
        this.api = api;
    }

    @Override
    public ConnectorType type() {
        return ConnectorType.GITHUB;
    }

    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of(ConnectorCapability.SOURCE_DISCOVERY, ConnectorCapability.SOURCE_METADATA);
    }

    @Override
    public Set<com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind>
        supportedCredentialKinds() {
        return Set.of(
            com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind.GITHUB_APP,
            com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind.GITHUB_TOKEN,
            com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind.GITHUB_PUBLIC);
    }

    @Override
    public ConnectionTestResult testConnection(ConnectorContext context) {
        try {
            if (context.credentials() instanceof CredentialMaterial.GitHubPublic) {
                String owner = context.config("owner", null);
                if (owner == null || owner.isBlank()) {
                    return ConnectionTestResult.failed(
                        "GitHub public mode requires configuration.owner (which account's public "
                            + "repositories to discover)", Map.of("apiBaseUrl", api.baseUrl()));
                }
                JsonNode user = read(context, "/users/" + GitHubApiClient.encode(owner), null);
                Map<String, String> details = new LinkedHashMap<>();
                details.put("apiBaseUrl", api.baseUrl());
                details.put("authMode", "GITHUB_PUBLIC");
                details.put("owner", user.path("login").asText(owner));
                details.put("publicRepos", String.valueOf(user.path("public_repos").asInt()));
                return ConnectionTestResult.ok(
                    "GitHub API reachable (public, no credentials); owner "
                        + user.path("login").asText(owner) + " found", details);
            }
            String token = token(context);
            JsonNode response = read(context, context.credentials() instanceof CredentialMaterial.GitHubAppCredentials
                ? "/installation/repositories?per_page=1"
                : "/user", token);
            Map<String, String> details = new LinkedHashMap<>();
            details.put("apiBaseUrl", api.baseUrl());
            if (response.has("login")) {
                details.put("login", response.path("login").asText());
                details.put("accountType", response.path("type").asText());
            } else {
                details.put("repositoryCount",
                    String.valueOf(response.path("total_count").asInt()));
            }
            details.put("authMode", context.credentials() instanceof CredentialMaterial.GitHubAppCredentials
                ? "GITHUB_APP" : "GITHUB_TOKEN");
            return ConnectionTestResult.ok("GitHub API reachable and credentials accepted", details);
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed(safeMessage(e), Map.of("apiBaseUrl", api.baseUrl()));
        }
    }

    @Override
    public List<DiscoveredResource> discover(ConnectorContext context) {
        if (context.credentials() instanceof CredentialMaterial.GitHubPublic) {
            return discoverPublicRepositories(context);
        }
        String token = token(context);
        boolean appAuth = context.credentials() instanceof CredentialMaterial.GitHubAppCredentials;
        List<DiscoveredResource> repositories = new ArrayList<>();
        for (int page = 1; page <= MAX_REPO_PAGES; page++) {
            JsonNode response = read(context, appAuth
                ? "/installation/repositories?per_page=" + PER_PAGE + "&page=" + page
                : "/user/repos?per_page=" + PER_PAGE + "&page=" + page
                    + "&affiliation=owner,collaborator,organization_member&sort=pushed",
                token);
            JsonNode items = appAuth ? response.path("repositories") : response;
            if (!items.isArray() || items.isEmpty()) {
                break;
            }
            for (JsonNode repository : items) {
                repositories.add(toResource(context, repository));
            }
            if (items.size() < PER_PAGE) {
                break;
            }
        }
        return repositories;
    }

    /** Lists an owner's public repositories without credentials (demo/dev mode). */
    private List<DiscoveredResource> discoverPublicRepositories(ConnectorContext context) {
        String owner = context.config("owner", null);
        if (owner == null || owner.isBlank()) {
            return List.of();
        }
        List<DiscoveredResource> repositories = new ArrayList<>();
        for (int page = 1; page <= MAX_REPO_PAGES; page++) {
            JsonNode response = read(context, "/users/" + GitHubApiClient.encode(owner)
                + "/repos?per_page=" + PER_PAGE + "&page=" + page + "&sort=pushed", null);
            if (!response.isArray() || response.isEmpty()) {
                break;
            }
            for (JsonNode repository : response) {
                repositories.add(toResource(context, repository));
            }
            if (response.size() < PER_PAGE) {
                break;
            }
        }
        return repositories;
    }

    @Override
    public RepositoryInspection inspectSource(ConnectorContext context, String repositoryExternalId) {
        String token = token(context);
        String repoPath = "/repos/" + repositoryExternalId;
        JsonNode repository = read(context, repoPath, token);
        String defaultBranch = repository.path("default_branch").asText("main");

        List<String> commitShas = new ArrayList<>();
        String headSha = null;
        String headMessage = null;
        Instant headAt = null;
        JsonNode commits = read(context, repoPath + "/commits?sha=" + GitHubApiClient.encode(defaultBranch)
            + "&per_page=5", token);
        if (commits.isArray() && !commits.isEmpty()) {
            JsonNode head = commits.get(0);
            headSha = head.path("sha").asText(null);
            headMessage = head.path("commit").path("message").asText(null);
            String date = head.path("commit").path("committer").path("date").asText(null);
            headAt = date == null ? null : Instant.parse(date);
            commits.forEach(commit -> commitShas.add(commit.path("sha").asText()));
        }

        List<String> tags = new ArrayList<>();
        JsonNode tagNodes = read(context, repoPath + "/tags?per_page=10", token);
        if (tagNodes.isArray()) {
            tagNodes.forEach(tag -> tags.add(tag.path("name").asText()));
        }

        List<String> dockerfiles = new ArrayList<>();
        List<String> migrationPaths = new ArrayList<>();
        List<String> liquibasePaths = new ArrayList<>();
        List<String> deploymentPaths = new ArrayList<>();
        JsonNode tree = read(context, repoPath + "/git/trees/"
            + GitHubApiClient.encode(defaultBranch) + "?recursive=1", token);
        if (tree.path("tree").isArray()) {
            for (JsonNode entry : tree.path("tree")) {
                String path = entry.path("path").asText("");
                if (path.endsWith("Dockerfile") || path.endsWith("Dockerfile.prod")) {
                    dockerfiles.add(path);
                }
                if (isMigrationPath(path)) {
                    migrationPaths.add(path);
                }
                if (isLiquibasePath(path)) {
                    liquibasePaths.add(path);
                }
                if (isDeploymentPath(path)) {
                    deploymentPaths.add(path);
                }
            }
        }

        return new RepositoryInspection(repositoryExternalId, defaultBranch, headSha, headMessage, headAt,
            tags, !dockerfiles.isEmpty(), dockerfiles, migrationPaths, liquibasePaths, deploymentPaths,
            commitShas);
    }

    @Override
    public List<MigrationFile> fetchMigrationFiles(ConnectorContext context, String repositoryExternalId,
                                                   List<String> paths) {
        String token = token(context);
        List<MigrationFile> files = new ArrayList<>();
        for (String path : paths.stream().limit(MAX_MIGRATION_FILES).toList()) {
            String content = api.getRawFile(context,
                "/repos/" + repositoryExternalId + "/contents/" + path, token);
            if (content == null) {
                continue;
            }
            files.add(toMigrationFile(path, content));
        }
        return files;
    }

    @Override
    public String fetchFile(ConnectorContext context, String repositoryExternalId, String path) {
        return api.getRawFile(context, "/repos/" + repositoryExternalId + "/contents/" + path,
            token(context));
    }

    @Override
    public List<String> changedMigrationFilesBetween(ConnectorContext context, String repositoryExternalId,
                                                     String baseSha, String headSha) {
        if (baseSha == null || headSha == null) {
            return List.of();
        }
        JsonNode comparison = read(context, "/repos/" + repositoryExternalId + "/compare/"
            + GitHubApiClient.encode(baseSha) + "..." + GitHubApiClient.encode(headSha), token(context));
        List<String> changed = new ArrayList<>();
        for (JsonNode file : comparison.path("files")) {
            String path = file.path("filename").asText("");
            if (isMigrationPath(path) && "added".equals(file.path("status").asText())) {
                changed.add(path);
            }
        }
        return changed;
    }

    private DiscoveredResource toResource(ConnectorContext context, JsonNode repository) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("private", String.valueOf(repository.path("private").asBoolean()));
        metadata.put("defaultBranch", repository.path("default_branch").asText(""));
        metadata.put("archived", String.valueOf(repository.path("archived").asBoolean()));
        metadata.put("language", repository.path("language").asText(""));
        metadata.put("pushedAt", repository.path("pushed_at").asText(""));
        metadata.put("htmlUrl", repository.path("html_url").asText(""));
        return DiscoveredResource.of(context.integration().id(), ConnectorType.GITHUB,
            DiscoveredResourceType.SOURCE_REPOSITORY, repository.path("full_name").asText(),
            repository.path("name").asText(), null, metadata);
    }

    private static MigrationFile toMigrationFile(String path, String content) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String withoutExtension = fileName.endsWith(".sql")
            ? fileName.substring(0, fileName.length() - 4) : fileName;
        String version = withoutExtension;
        String description = "";
        int separator = withoutExtension.indexOf("__");
        if (separator >= 0) {
            version = withoutExtension.substring(0, separator);
            description = withoutExtension.substring(separator + 2).replace('_', ' ');
        }
        return new MigrationFile(version, description, path, content);
    }

    static boolean isMigrationPath(String path) {
        String lower = path.toLowerCase();
        if (!lower.endsWith(".sql")) {
            return false;
        }
        return lower.contains("/db/migration/") || lower.startsWith("db/migration/")
            || lower.contains("/migration/") || lower.contains("/flyway/");
    }

    /**
     * Liquibase changelogs (XML/YAML/JSON/SQL under a changelog directory, or
     * any path carrying "liquibase"). These are detected for service mapping
     * and evidence; this build's deterministic migration analyser only
     * classifies Flyway-style SQL files.
     */
    static boolean isLiquibasePath(String path) {
        String lower = path.toLowerCase();
        boolean changelogFile = lower.endsWith(".xml") || lower.endsWith(".yaml") || lower.endsWith(".yml")
            || lower.endsWith(".json") || lower.endsWith(".sql");
        if (!changelogFile) {
            return false;
        }
        return lower.contains("/db/changelog/") || lower.startsWith("db/changelog/")
            || lower.contains("liquibase") || lower.contains("master.xml") || lower.contains("changelog");
    }

    static boolean isDeploymentPath(String path) {
        String lower = path.toLowerCase();
        return lower.startsWith(".github/workflows/")
            || lower.contains("docker-compose")
            || lower.endsWith("k8s.yaml") || lower.endsWith("k8s.yml")
            || lower.contains("/k8s/") || lower.contains("/kubernetes/")
            || lower.contains("argocd") || lower.contains("deployment.yaml");
    }

    private String token(ConnectorContext context) {
        CredentialMaterial material = context.credentials();
        if (material instanceof CredentialMaterial.GitHubToken token) {
            return token.token();
        }
        if (material instanceof CredentialMaterial.GitHubAppCredentials app) {
            String key = app.appId() + "|" + app.installationId();
            CachedToken cached = appTokens.get(key);
            if (cached != null && cached.valid()) {
                return cached.token();
            }
            String jwt = GitHubAppJwt.create(app.appId(), app.privateKeyPem());
            JsonNode response = parse(api.installationToken(context, jwt, app.installationId()));
            String token = response.path("token").asText(null);
            String expiresAt = response.path("expires_at").asText(null);
            if (token == null) {
                throw new GitHubApiException("GitHub App token exchange returned no token");
            }
            Instant expiry = expiresAt == null ? Instant.now().plusSeconds(3000) : Instant.parse(expiresAt);
            appTokens.put(key, new CachedToken(token, expiry));
            return token;
        }
        if (material instanceof CredentialMaterial.GitHubPublic) {
            // No Authorization header: public endpoints only.
            return null;
        }
        throw new GitHubApiException("GitHub connector received "
            + material.getClass().getSimpleName() + "; expected app, token or public credentials");
    }

    private JsonNode read(ConnectorContext context, String path, String token) {
        return parse(api.get(context, path, token));
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json == null ? "{}" : json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new GitHubApiException("GitHub returned a response that could not be parsed: "
                + e.getOriginalMessage());
        }
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private record CachedToken(String token, Instant expiresAt) {
        boolean valid() {
            return Instant.now().isBefore(expiresAt.minusSeconds(300));
        }
    }
}
