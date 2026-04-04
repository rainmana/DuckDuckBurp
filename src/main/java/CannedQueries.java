import java.util.List;

public class CannedQueries {

    public record Query(String name, String sql) {
        @Override public String toString() { return name; }
    }

    public record Category(String name, List<Query> queries) {
        @Override public String toString() { return name; }
    }

    public static final List<Category> ALL = List.of(

        new Category("Recon", List.of(
            new Query("All Hosts", """
                SELECT host, protocol,
                       COUNT(*)             AS requests,
                       COUNT(DISTINCT path) AS unique_paths
                FROM traffic
                GROUP BY host, protocol
                ORDER BY requests DESC
                """),
            new Query("Full URL Inventory", """
                SELECT DISTINCT protocol || '://' || host || path AS url, method
                FROM traffic
                ORDER BY url
                """),
            new Query("HTTP Methods Used", """
                SELECT method, COUNT(*) AS count
                FROM traffic
                GROUP BY method
                ORDER BY count DESC
                """),
            new Query("Response Code Breakdown", """
                SELECT status_code, COUNT(*) AS count
                FROM traffic
                GROUP BY status_code
                ORDER BY status_code
                """),
            new Query("Traffic Timeline (by minute)", """
                SELECT strftime(timestamp, '%Y-%m-%d %H:%M') AS minute,
                       COUNT(*) AS requests
                FROM traffic
                GROUP BY minute
                ORDER BY minute DESC
                LIMIT 60
                """)
        )),

        new Category("Authentication", List.of(
            new Query("All Auth Failures (401/403)", """
                SELECT id, host, method, path, status_code
                FROM traffic
                WHERE status_code IN (401, 403)
                ORDER BY id DESC
                """),
            new Query("Paths with Mixed Auth (bypass?)", """
                SELECT path,
                       COUNT(CASE WHEN status_code = 200           THEN 1 END) AS ok_hits,
                       COUNT(CASE WHEN status_code IN (401, 403)   THEN 1 END) AS auth_fail_hits
                FROM traffic
                GROUP BY path
                HAVING ok_hits > 0 AND auth_fail_hits > 0
                ORDER BY auth_fail_hits DESC
                """),
            new Query("Login & Auth Endpoints", """
                SELECT DISTINCT id, host, method, path, status_code
                FROM traffic
                WHERE regexp_extract(lower(path),
                    'login|signin|auth|oauth|sso|saml|token|session|logout|register|signup'
                ) <> ''
                ORDER BY host, path
                """),
            new Query("Bearer / API Key Headers", """
                SELECT DISTINCT id, host, path,
                       req_headers
                FROM traffic
                WHERE lower(req_headers::VARCHAR) LIKE '%authorization%'
                   OR lower(req_headers::VARCHAR) LIKE '%x-api-key%'
                ORDER BY host, path
                LIMIT 50
                """)
        )),

        new Category("Input Surface", List.of(
            new Query("Non-GET Endpoints", """
                SELECT DISTINCT method, host, path,
                       COUNT(*) AS requests
                FROM traffic
                WHERE method NOT IN ('GET', 'HEAD', 'OPTIONS')
                GROUP BY method, host, path
                ORDER BY method, requests DESC
                """),
            new Query("Endpoints with Query Parameters", """
                SELECT DISTINCT host,
                       split_part(path, '?', 1) AS base_path,
                       split_part(path, '?', 2) AS query_string
                FROM traffic
                WHERE path LIKE '%?%'
                ORDER BY host, base_path
                """),
            new Query("Requests with Bodies", """
                SELECT id, host, method, path, status_code,
                       length(req_body) AS body_length
                FROM traffic
                WHERE req_body IS NOT NULL AND length(req_body) > 0
                ORDER BY body_length DESC
                LIMIT 50
                """),
            new Query("JSON Requests", """
                SELECT id, host, method, path, status_code
                FROM traffic
                WHERE lower(req_headers::VARCHAR) LIKE '%application/json%'
                ORDER BY id DESC
                LIMIT 50
                """)
        )),

        new Category("Errors & Leakage", List.of(
            new Query("All 5xx Server Errors", """
                SELECT id, host, path, status_code
                FROM traffic
                WHERE status_code >= 500
                ORDER BY id DESC
                """),
            new Query("All 4xx Client Errors", """
                SELECT id, host, path, status_code
                FROM traffic
                WHERE status_code >= 400 AND status_code < 500
                ORDER BY id DESC
                """),
            new Query("Largest Responses (top 20)", """
                SELECT id, host, method, path, status_code, resp_length
                FROM traffic
                ORDER BY resp_length DESC
                LIMIT 20
                """),
            new Query("404 Not Found", """
                SELECT path, COUNT(*) AS hits
                FROM traffic
                WHERE status_code = 404
                GROUP BY path
                ORDER BY hits DESC
                """),
            new Query("Error Responses with Large Bodies", """
                SELECT id, host, path, status_code, resp_length
                FROM traffic
                WHERE status_code >= 400 AND resp_length > 1000
                ORDER BY resp_length DESC
                LIMIT 20
                """)
        )),

        new Category("OWASP Top 10", List.of(
            new Query("A01 – Broken Access Control (mixed auth paths)", """
                SELECT path,
                       COUNT(CASE WHEN status_code = 200         THEN 1 END) AS ok_200,
                       COUNT(CASE WHEN status_code IN (401,403)  THEN 1 END) AS denied
                FROM traffic
                GROUP BY path
                HAVING ok_200 > 0 AND denied > 0
                ORDER BY denied DESC
                """),
            new Query("A01 – Privileged Paths Returning 200", """
                SELECT id, host, method, path, status_code
                FROM traffic
                WHERE status_code = 200
                  AND regexp_extract(lower(path),
                      'admin|manager|superuser|root|internal|private|restricted|staff|moderator') <> ''
                ORDER BY host, path
                LIMIT 50
                """),
            new Query("A02 – Sensitive Data Over Plain HTTP", """
                SELECT id, host, method, path, status_code
                FROM traffic
                WHERE protocol = 'http'
                  AND regexp_extract(lower(path || coalesce(req_body, '')),
                      'password|passwd|secret|token|apikey|api_key|credential|ssn|credit') <> ''
                ORDER BY id DESC
                LIMIT 50
                """),
            new Query("A03 – Injection Patterns in Requests", """
                SELECT id, host, method, path, req_body
                FROM traffic
                WHERE regexp_extract(lower(path || ' ' || coalesce(req_body, '')),
                    'union.{0,20}select|drop.{0,5}table|exec.{0,5}\\(|<script|javascript:') <> ''
                LIMIT 50
                """),
            new Query("A05 – Exposed Debug / Config Endpoints", """
                SELECT id, host, method, path, status_code
                FROM traffic
                WHERE status_code = 200
                  AND regexp_extract(lower(path),
                      '\\.env|phpinfo|server-status|actuator|swagger|/api-docs|/openapi|/metrics|/health|debug|trace') <> ''
                ORDER BY host, path
                LIMIT 50
                """),
            new Query("A07 – Auth Failure Hotspots (brute force?)", """
                SELECT host, path, COUNT(*) AS failures
                FROM traffic
                WHERE status_code IN (401, 403)
                GROUP BY host, path
                HAVING COUNT(*) >= 3
                ORDER BY failures DESC
                """),
            new Query("A10 – SSRF / Internal URL Indicators", """
                SELECT id, host, method, path, req_body
                FROM traffic
                WHERE regexp_extract(path || ' ' || coalesce(req_body, ''),
                    '192\\.168\\.|10\\.[0-9]+\\.|172\\.1[6-9]\\.|127\\.0|localhost|file://|dict://|gopher://') <> ''
                LIMIT 50
                """)
        )),

        new Category("CWE Web", List.of(
            new Query("CWE-79: XSS – Script Patterns in Input", """
                SELECT id, host, method, path, req_body
                FROM traffic
                WHERE regexp_extract(lower(coalesce(req_body, '') || ' ' || path),
                    '<script|javascript:|onerror=|onload=|alert\\(|eval\\(|document\\.cookie') <> ''
                LIMIT 50
                """),
            new Query("CWE-89: SQL Injection – Common Payloads", """
                SELECT id, host, method, path, req_body
                FROM traffic
                WHERE regexp_extract(lower(coalesce(req_body, '') || ' ' || path),
                    'union.{0,15}select|or.{0,5}1.{0,3}=.{0,3}1|drop.{0,5}table|; *--') <> ''
                LIMIT 50
                """),
            new Query("CWE-22: Path Traversal Sequences", """
                SELECT id, host, method, path, status_code
                FROM traffic
                WHERE path LIKE '%../%'
                   OR lower(path) LIKE '%2e2e%'
                   OR regexp_extract(lower(path), '\\.\\.' || '%2f' || '|\\.\\.' || '%5c') <> ''
                ORDER BY id DESC
                LIMIT 50
                """),
            new Query("CWE-352: POST/PUT/DELETE without CSRF Token", """
                SELECT id, host, method, path, req_headers
                FROM traffic
                WHERE method IN ('POST', 'PUT', 'DELETE', 'PATCH')
                  AND lower(req_headers::VARCHAR) NOT LIKE '%csrf%'
                  AND lower(req_headers::VARCHAR) NOT LIKE '%xsrf%'
                ORDER BY id DESC
                LIMIT 50
                """),
            new Query("CWE-601: Open Redirect Parameters", """
                SELECT id, host, method, path, status_code
                FROM traffic
                WHERE regexp_extract(lower(path),
                    'redirect=|url=|next=|return=|rurl=|dest=|target=|forward=|goto=') <> ''
                ORDER BY id DESC
                LIMIT 50
                """),
            new Query("CWE-918: SSRF – Server-Side URL Parameters", """
                SELECT id, host, method, path, req_body
                FROM traffic
                WHERE regexp_extract(lower(path || ' ' || coalesce(req_body, '')),
                    'ssrf|internal|intranet|192\\.168\\.|10\\.[0-9]+\\.|127\\.0|localhost|file://|dict://') <> ''
                LIMIT 50
                """)
        )),

        new Category("Security", List.of(
            new Query("Interesting Paths", """
                SELECT id, host, method, path, status_code
                FROM traffic
                WHERE regexp_extract(lower(path),
                    'admin|debug|backup|upload|config|secret|token|password|\\.env|swagger|graphql|actuator|phpmyadmin|\\.git|wp-admin|jenkins|kibana|passwd'
                ) <> ''
                ORDER BY host, path
                """),
            new Query("Numeric ID Patterns (IDOR?)", """
                SELECT host, method,
                       regexp_replace(path, '[0-9]+', '{id}') AS path_pattern,
                       COUNT(DISTINCT path)                   AS id_variations
                FROM traffic
                WHERE regexp_extract(path, '/[0-9]+') <> ''
                GROUP BY host, method, path_pattern
                ORDER BY id_variations DESC, path_pattern
                LIMIT 30
                """),
            new Query("API Endpoints", """
                SELECT DISTINCT id, host, method, path, status_code
                FROM traffic
                WHERE lower(path) LIKE '%/api/%'
                   OR lower(path) LIKE '%/v1/%'
                   OR lower(path) LIKE '%/v2/%'
                   OR lower(path) LIKE '%/v3/%'
                   OR lower(path) LIKE '%/rest/%'
                   OR lower(path) LIKE '%/graphql%'
                ORDER BY path
                """),
            new Query("Redirect Chains (3xx)", """
                SELECT id, host, method, path, status_code
                FROM traffic
                WHERE status_code >= 300 AND status_code < 400
                ORDER BY id DESC
                """),
            new Query("Potential File Uploads", """
                SELECT DISTINCT id, host, method, path, status_code
                FROM traffic
                WHERE lower(req_headers::VARCHAR) LIKE '%multipart%'
                   OR regexp_extract(lower(path), 'upload|file|attach|import') <> ''
                ORDER BY host, path
                """)
        ))
    );
}
