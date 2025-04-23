package generators;

import com.google.gson.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.text.CaseUtils;


import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates TestNG Rest Assured test classes from Postman collection items.
 * Each endpoint in the Postman collection will be converted to a TestNG test method.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TestClassGenerator {
    // Default configuration - can be customized via builder
    private static String outputDir = "src/test/java";
    private static String packageName = "tests";
    private static String baseUrl = "{{base_url}}";
    private static String basePackage = "com.api.automation";
    private static boolean generateBaseClass = true;
    private static String pojoPackage = "models";

    // Regex pattern to find Postman variables
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    /**
     * Configures the generator with custom settings
     */
    public static class Config {
        private String outputDir = TestClassGenerator.outputDir;
        private String packageName = TestClassGenerator.packageName;
        private String baseUrl = TestClassGenerator.baseUrl;
        private String basePackage = TestClassGenerator.basePackage;
        private boolean generateBaseClass = TestClassGenerator.generateBaseClass;
        private String pojoPackage = TestClassGenerator.pojoPackage;

        public Config setOutputDir(String dir) {
            this.outputDir = dir;
            return this;
        }

        public Config setPackageName(String pkg) {
            this.packageName = pkg;
            return this;
        }

        public Config setBaseUrl(String url) {
            this.baseUrl = url;
            return this;
        }

        public Config setBasePackage(String pkg) {
            this.basePackage = pkg;
            return this;
        }

        public Config setGenerateBaseClass(boolean generate) {
            this.generateBaseClass = generate;
            return this;
        }

        public Config setPojoPackage(String pkg) {
            this.pojoPackage = pkg;
            return this;
        }

        public void apply() {
            TestClassGenerator.outputDir = this.outputDir;
            TestClassGenerator.packageName = this.packageName;
            TestClassGenerator.baseUrl = this.baseUrl;
            TestClassGenerator.basePackage = this.basePackage;
            TestClassGenerator.generateBaseClass = this.generateBaseClass;
            TestClassGenerator.pojoPackage = this.pojoPackage;
        }
    }

    /**
     * Main entry point to generate test classes from a Postman collection file
     *
     * @param postmanCollectionPath Path to the Postman collection JSON file
     * @throws IOException If file operations fail
     */
    public static void generateTestClasses(String postmanCollectionPath) throws IOException {
        JsonObject collection = JsonParser.parseReader(new FileReader(postmanCollectionPath)).getAsJsonObject();

        createOutputDirectory();

        // Generate base test class if requested
        if (generateBaseClass) {
            generateBaseTestClass();
        }

        // Create a map to group endpoints by resource for better organization
        Map<String, List<JsonObject>> resourceEndpoints = new HashMap<>();

        // Process the collection recursively to handle nested folders
        if (collection.has("item")) {
            populateResourceEndpointsMap(collection.getAsJsonArray("item"), "", resourceEndpoints);
        } else {
            System.err.println("Invalid Postman collection format: 'item' field not found");
            return;
        }

        // Generate test classes for each resource group
        for (Map.Entry<String, List<JsonObject>> entry : resourceEndpoints.entrySet()) {
            String resourceName = entry.getKey();
            List<JsonObject> endpoints = entry.getValue();

            generateResourceTestClass(resourceName, endpoints);
        }
    }

    /**
     * Recursively processes items in the Postman collection, grouping them by resource
     */
    private static void populateResourceEndpointsMap(JsonArray items, String folderPath,
                                                     Map<String, List<JsonObject>> resourceEndpoints) {
        for (JsonElement item : items) {
            JsonObject itemObj = item.getAsJsonObject();
            String itemName = itemObj.has("name") ? itemObj.get("name").getAsString() : "Unknown";

            // Check if this is a folder (contains sub-items)
            if (itemObj.has("item")) {
                String newPath = folderPath.isEmpty() ? itemName : folderPath + "/" + itemName;
                populateResourceEndpointsMap(itemObj.getAsJsonArray("item"), newPath, resourceEndpoints);
            } else {
                // This is an endpoint, not a folder
                if (itemObj.has("request")) {
                    // Determine resource group from path or name
                    String resourceName = determineResourceName(itemObj, folderPath);
                    resourceEndpoints
                            .computeIfAbsent(resourceName, k -> new ArrayList<>())
                            .add(itemObj);
                }
            }
        }
    }

    /**
     * Determines the resource name for an endpoint
     */
    private static String determineResourceName(JsonObject endpoint, String folderPath) {
        // Try to use folder path first
        if (!folderPath.isEmpty()) {
            String[] parts = folderPath.split("/");
            return sanitizeResourceName(parts[0]);
        }

        // Try to extract from URL path
        if (endpoint.has("request")) {
            JsonObject request = endpoint.getAsJsonObject("request");
            if (request.has("url")) {
                JsonElement urlElement = request.get("url");
                String url = "";

                if (urlElement.isJsonObject() && urlElement.getAsJsonObject().has("path")) {
                    JsonArray pathArray = urlElement.getAsJsonObject().getAsJsonArray("path");
                    if (pathArray != null && pathArray.size() > 0) {
                        return sanitizeResourceName(pathArray.get(0).getAsString());
                    }
                } else if (urlElement.isJsonPrimitive()) {
                    url = urlElement.getAsString();
                    String[] pathParts = url.split("/");
                    for (String part : pathParts) {
                        if (!part.isEmpty() && !part.startsWith("{{") && !part.contains(":")) {
                            return sanitizeResourceName(part);
                        }
                    }
                }
            }
        }

        // Fall back to endpoint name
        String name = endpoint.has("name") ? endpoint.get("name").getAsString() : "Unknown";
        return sanitizeResourceName(name);
    }

    /**
     * Sanitizes a resource name for use in a class name
     */
    private static String sanitizeResourceName(String name) {
        // Remove any non-alphanumeric characters and capitalize the first letter
        String sanitized = name.replaceAll("[^a-zA-Z0-9]", " ");
        sanitized = sanitized.trim();

        // Convert to CamelCase
        return CaseUtils.toCamelCase(sanitized, true, ' ');
    }

    /**
     * Generates a test class for a group of related endpoints
     */
    private static void generateResourceTestClass(String resourceName, List<JsonObject> endpoints) throws IOException {
        String className = resourceName + "ApiTests";

        StringBuilder classBuilder = new StringBuilder();

        // Package declaration
        classBuilder.append("package ").append(packageName).append(";\n\n");

        // Imports
        Set<String> imports = new HashSet<>();
        imports.add("org.testng.annotations.Test");
        imports.add("org.testng.annotations.BeforeClass");
        imports.add("org.testng.annotations.BeforeMethod");
        imports.add("io.restassured.response.Response");
        imports.add("io.restassured.specification.RequestSpecification");
        imports.add("static io.restassured.RestAssured.given");
        imports.add("static org.hamcrest.Matchers.*");
        imports.add("org.testng.Assert");
        imports.add(basePackage + ".base.BaseApiTest");

        // Check if any endpoints have request body
        boolean hasRequestBody = endpoints.stream()
                .filter(e -> e.has("request"))
                .map(e -> e.getAsJsonObject("request"))
                .anyMatch(r -> r.has("body") && r.getAsJsonObject("body").has("raw"));

        // Add imports for potential POJOs
        if (hasRequestBody) {
            // Import POJO models package
            imports.add(basePackage + "." + pojoPackage + ".*");
            imports.add("org.json.JSONObject");
        }

        // Check if any endpoints have path variables
        boolean hasPathVariables = false;
        for (JsonObject endpoint : endpoints) {
            if (endpoint.has("request")) {
                JsonObject request = endpoint.getAsJsonObject("request");
                if (request.has("url")) {
                    JsonElement urlElement = request.get("url");
                    if (urlElement.isJsonObject() && urlElement.getAsJsonObject().has("variable")) {
                        hasPathVariables = true;
                        break;
                    } else if (urlElement.isJsonPrimitive() && urlElement.getAsString().contains("{")) {
                        hasPathVariables = true;
                        break;
                    }
                }
            }
        }

        if (hasPathVariables) {
            imports.add("java.util.HashMap");
            imports.add("java.util.Map");
        }

        // Sort imports
        List<String> sortedImports = new ArrayList<>(imports);
        Collections.sort(sortedImports);

        for (String importClass : sortedImports) {
            classBuilder.append("import ").append(importClass).append(";\n");
        }
        classBuilder.append("\n");

        // Class declaration with JavaDoc
        classBuilder.append("/**\n");
        classBuilder.append(" * Tests for ").append(resourceName).append(" API endpoints\n");
        classBuilder.append(" */\n");
        classBuilder.append("public class ").append(className)
                .append(" extends BaseApiTest {\n\n");

        // Setup methods
        classBuilder.append("    @BeforeClass\n");
        classBuilder.append("    public void setUpClass() {\n");
        classBuilder.append("        // Set up any class-level configuration specific to ")
                .append(resourceName).append(" tests\n");
        classBuilder.append("    }\n\n");

        classBuilder.append("    @BeforeMethod\n");
        classBuilder.append("    public void setUpMethod() {\n");
        classBuilder.append("        // Set up method-level configuration\n");
        classBuilder.append("    }\n\n");

        // Generate a test method for each endpoint
        for (JsonObject endpoint : endpoints) {
            generateTestMethod(endpoint, classBuilder);
        }

        classBuilder.append("}\n");

        // Write to file
        String fullPackagePath = outputDir + "/" + packageName.replace('.', '/');
        Path path = Paths.get(fullPackagePath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }

        try (FileWriter writer = new FileWriter(fullPackagePath + "/" + className + ".java")) {
            writer.write(classBuilder.toString());
        }

        System.out.println("Generated test class: " + className);
    }

    /**
     * Generates a test method for an individual endpoint
     */
    private static void generateTestMethod(JsonObject endpoint, StringBuilder classBuilder) {
        String endpointName = endpoint.has("name") ? endpoint.get("name").getAsString() : "Unknown";

        // Skip if no request
        if (!endpoint.has("request")) {
            return;
        }

        JsonObject request = endpoint.getAsJsonObject("request");

        // Extract HTTP method
        String httpMethod = request.has("method") ? request.get("method").getAsString().toLowerCase() : "get";

        // Extract URL
        String url = "";
        List<String> pathParams = new ArrayList<>();

        if (request.has("url")) {
            JsonElement urlElement = request.get("url");
            if (urlElement.isJsonObject()) {
                JsonObject urlObj = urlElement.getAsJsonObject();
                if (urlObj.has("raw")) {
                    url = urlObj.get("raw").getAsString();
                }

                // Extract path variables
                if (urlObj.has("variable")) {
                    JsonArray variables = urlObj.getAsJsonArray("variable");
                    for (JsonElement var : variables) {
                        if (var.isJsonObject() && var.getAsJsonObject().has("key")) {
                            pathParams.add(var.getAsJsonObject().get("key").getAsString());
                        }
                    }
                } else if (urlObj.has("path")) {
                    // Extract path parameters from path array if available
                    JsonArray pathArray = urlObj.getAsJsonArray("path");
                    for (JsonElement pathElement : pathArray) {
                        if (pathElement.isJsonPrimitive()) {
                            String pathPart = pathElement.getAsString();
                            if (pathPart.startsWith(":") || pathPart.matches("\\{.+\\}")) {
                                // This is a path parameter
                                String paramName = pathPart.startsWith(":") ?
                                        pathPart.substring(1) :
                                        pathPart.substring(1, pathPart.length() - 1);
                                pathParams.add(paramName);
                            }
                        }
                    }
                }
            } else if (urlElement.isJsonPrimitive()) {
                url = urlElement.getAsString();

                // Find path variables in the URL - look for patterns like {id}
                Pattern pattern = Pattern.compile("\\{([^}]+)\\}|:([^/]+)");
                Matcher matcher = pattern.matcher(url);
                while (matcher.find()) {
                    String param = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                    pathParams.add(param);
                }
            }
        }

        // Clean up URL for path parameters
        String cleanUrl = url;
        for (String param : pathParams) {
            cleanUrl = cleanUrl.replace("{" + param + "}", "{" + param + "}");
            cleanUrl = cleanUrl.replace(":" + param, "{" + param + "}");
        }

        // Make the URL relative if it contains the base URL
        if (cleanUrl.contains("{{")) {
            // Replace Postman variables with empty string to get relative path
            Matcher matcher = VARIABLE_PATTERN.matcher(cleanUrl);
            cleanUrl = matcher.replaceAll("");

            // Remove protocol and domain if present
            cleanUrl = cleanUrl.replaceAll("^https?://[^/]+", "");
        }

        // Ensure URL starts with /
        if (!cleanUrl.startsWith("/") && !cleanUrl.isEmpty()) {
            cleanUrl = "/" + cleanUrl;
        }

        // Extract headers
        List<String> headerKeys = new ArrayList<>();
        if (request.has("header")) {
            JsonArray headers = request.getAsJsonArray("header");
            for (JsonElement header : headers) {
                if (header.isJsonObject() && header.getAsJsonObject().has("key")) {
                    headerKeys.add(header.getAsJsonObject().get("key").getAsString());
                }
            }
        }

        // Extract query params
        List<String> queryParams = new ArrayList<>();
        if (request.has("url") && request.get("url").isJsonObject() &&
                request.getAsJsonObject("url").has("query")) {

            JsonArray queries = request.getAsJsonObject("url").getAsJsonArray("query");
            for (JsonElement query : queries) {
                if (query.isJsonObject() && query.getAsJsonObject().has("key")) {
                    queryParams.add(query.getAsJsonObject().get("key").getAsString());
                }
            }
        }

        // Extract request body and determine if we need to use POJOs
        boolean hasBody = false;
        String bodyType = "";
        String bodyContent = "";
        boolean isJsonBody = false;
        String pojoClassName = null;

        if (request.has("body") && request.getAsJsonObject("body").has("mode")) {
            hasBody = true;
            bodyType = request.getAsJsonObject("body").get("mode").getAsString();

            if ("raw".equals(bodyType) && request.getAsJsonObject("body").has("raw")) {
                bodyContent = request.getAsJsonObject("body").get("raw").getAsString();
                isJsonBody = isJsonBody(bodyContent);

                if (isJsonBody) {
                    // Determine potential POJO class name for request
                    pojoClassName = determineRequestPojoName(endpointName);
                }
            }
        }

        // Create a sanitized method name
        String methodName = "test" + sanitizeMethodName(endpointName);

        // Comment with endpoint details
        classBuilder.append("    /**\n");
        classBuilder.append("     * Test for ").append(endpointName).append("\n");
        classBuilder.append("     * ").append(httpMethod.toUpperCase()).append(" ").append(url).append("\n");
        if (hasBody && isJsonBody && bodyContent.length() < 100) {
            classBuilder.append("     * Request body: ").append(bodyContent.replaceAll("\n", " ")).append("\n");
        } else if (hasBody && isJsonBody) {
            classBuilder.append("     * Request body: JSON payload\n");
        }
        classBuilder.append("     */\n");

        // Test method
        classBuilder.append("    @Test\n");
        classBuilder.append("    public void ").append(methodName).append("() {\n");

        // Path parameters
        if (!pathParams.isEmpty()) {
            classBuilder.append("        // Path parameters\n");
            classBuilder.append("        Map<String, Object> pathParams = new HashMap<>();\n");
            for (String param : pathParams) {
                classBuilder.append("        pathParams.put(\"").append(param)
                        .append("\", \"sample_").append(param).append("\"); // Replace with actual test data\n");
            }
            classBuilder.append("\n");
        }

        // Request body using POJO if applicable
        if (hasBody && isJsonBody && pojoClassName != null) {
            classBuilder.append("        // Request body using POJO\n");
            classBuilder.append("        ").append(pojoClassName).append(" requestBody = new ")
                    .append(pojoClassName).append("();\n");
            classBuilder.append("        // TODO: Set properties on the requestBody object\n");
            classBuilder.append("        // Example: requestBody.setName(\"Test Name\");\n\n");
        } else if (hasBody && isJsonBody) {
            classBuilder.append("        // Request body\n");
            classBuilder.append("        JSONObject requestBody = new JSONObject();\n");
            classBuilder.append("        // TODO: Add properties to the request body\n");
            classBuilder.append("        // Example: requestBody.put(\"name\", \"Test Name\");\n\n");
        } else if (hasBody && "formdata".equals(bodyType)) {
            classBuilder.append("        // Form data parameters will be added to the request below\n\n");
        }

        // Query parameters
        if (!queryParams.isEmpty()) {
            classBuilder.append("        // Create request with query parameters\n");
            classBuilder.append("        RequestSpecification request = given()\n");
            classBuilder.append("            .spec(getRequestSpec())");

            for (String param : queryParams) {
                classBuilder.append("\n            .queryParam(\"").append(param)
                        .append("\", \"sample_").append(param).append("\") // Replace with actual test data");
            }

            classBuilder.append(";\n\n");
        } else {
            classBuilder.append("        // Create base request\n");
            classBuilder.append("        RequestSpecification request = given()\n");
            classBuilder.append("            .spec(getRequestSpec());\n\n");
        }

        // Headers
        if (!headerKeys.isEmpty()) {
            classBuilder.append("        // Add headers\n");
            for (String header : headerKeys) {
                classBuilder.append("        request = request.header(\"").append(header)
                        .append("\", \"sample_header_value\"); // Replace with actual header value\n");
            }
            classBuilder.append("\n");
        }

        // Add request body to the request
        if (hasBody) {
            if (isJsonBody && pojoClassName != null) {
                classBuilder.append("        // Add POJO request body\n");
                classBuilder.append("        request = request.body(requestBody);\n\n");
            } else if (isJsonBody) {
                classBuilder.append("        // Add JSON request body\n");
                classBuilder.append("        request = request.body(requestBody.toString());\n\n");
            } else if ("formdata".equals(bodyType) && request.getAsJsonObject("body").has("formdata")) {
                classBuilder.append("        // Add form data parameters\n");
                JsonArray formParams = request.getAsJsonObject("body").getAsJsonArray("formdata");
                for (JsonElement param : formParams) {
                    if (param.isJsonObject() && param.getAsJsonObject().has("key")) {
                        String key = param.getAsJsonObject().get("key").getAsString();
                        classBuilder.append("        request = request.formParam(\"").append(key)
                                .append("\", \"sample_").append(key).append("\"); // Replace with actual data\n");
                    }
                }
                classBuilder.append("\n");
            } else if ("raw".equals(bodyType)) {
                classBuilder.append("        // Add raw request body\n");
                classBuilder.append("        String rawBody = \"").append(bodyContent.replace("\"", "\\\"").replace("\n", "\\n"))
                        .append("\";\n");
                classBuilder.append("        request = request.body(rawBody);\n\n");
            }
        }

        // Execute request
        classBuilder.append("        // Execute request\n");
        classBuilder.append("        Response response = request\n");
        classBuilder.append("            .when()\n");

        // URL with path parameters if needed
        if (!pathParams.isEmpty()) {
            classBuilder.append("            .").append(httpMethod).append("(\"")
                    .append(cleanUrl).append("\", pathParams)\n");
        } else {
            classBuilder.append("            .").append(httpMethod).append("(\"")
                    .append(cleanUrl).append("\")\n");
        }

        // Response validation
        classBuilder.append("            .then()\n");
        classBuilder.append("            .spec(getResponseSpec())\n");
        classBuilder.append("            .statusCode(200) // Update with expected status code\n");

        // Add some common validations based on request type
        if ("get".equalsIgnoreCase(httpMethod)) {
            classBuilder.append("            .body(\"$\", not(empty())) // Verify response is not empty\n");
        } else if ("post".equalsIgnoreCase(httpMethod)) {
            classBuilder.append("            .body(\"id\", notNullValue()) // Verify ID is returned\n");
        } else if ("put".equalsIgnoreCase(httpMethod) || "patch".equalsIgnoreCase(httpMethod)) {
            classBuilder.append("            .body(\"$\", notNullValue()) // Verify response exists\n");
        }

        classBuilder.append("            .extract().response();\n\n");

        // Determine potential response POJO
        String responsePojoName = determineResponsePojoName(endpointName);

        // Additional validations with response POJO if appropriate
        classBuilder.append("        // Additional validations\n");
        if ("get".equalsIgnoreCase(httpMethod) || "post".equalsIgnoreCase(httpMethod)) {
            classBuilder.append("        // Option 1: Validate with JSONPath\n");
            classBuilder.append("        // String actualValue = response.jsonPath().getString(\"name\");\n");
            classBuilder.append("        // Assert.assertEquals(actualValue, \"expected value\");\n\n");

            classBuilder.append("        // Option 2: Parse response to POJO\n");
            classBuilder.append("        // ").append(responsePojoName).append(" responseObj = response.as(")
                    .append(responsePojoName).append(".class);\n");
            classBuilder.append("        // Assert.assertNotNull(responseObj);\n");
            classBuilder.append("        // Assert.assertEquals(responseObj.getName(), \"expected name\");\n");
        } else if ("delete".equalsIgnoreCase(httpMethod)) {
            classBuilder.append("        // For delete, often just the status code check is sufficient\n");
            classBuilder.append("        // If the API returns a body, add appropriate assertions\n");
        }

        classBuilder.append("    }\n\n");
    }

    /**
     * Determines a potential POJO class name for a request
     */
    private static String determineRequestPojoName(String endpointName) {
        return sanitizeClassName(endpointName) + "Request";
    }

    /**
     * Determines a potential POJO class name for a response
     */
    private static String determineResponsePojoName(String endpointName) {
        return sanitizeClassName(endpointName) + "Response";
    }

    /**
     * Sanitizes a name to be used as a class name
     */
    private static String sanitizeClassName(String name) {
        // Remove special characters and spaces
        String sanitized = name.replaceAll("[^a-zA-Z0-9]", " ");
        sanitized = sanitized.trim();

        // Convert to CamelCase
        return CaseUtils.toCamelCase(sanitized, true, ' ');
    }

    /**
     * Generate the base test class that all resource classes will extend
     */
    private static void generateBaseTestClass() throws IOException {
        StringBuilder classBuilder = new StringBuilder();
        String baseClassName = "BaseApiTest";

        // Create the package directory structure
        String basePackagePath = basePackage.replace('.', '/');
        String fullPackagePath = outputDir + "/" + basePackagePath + "/base";
        Path path = Paths.get(fullPackagePath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }

        // Package declaration
        classBuilder.append("package ").append(basePackage).append(".base;\n\n");

        // Imports
        classBuilder.append("import io.restassured.RestAssured;\n");
        classBuilder.append("import io.restassured.builder.RequestSpecBuilder;\n");
        classBuilder.append("import io.restassured.builder.ResponseSpecBuilder;\n");
        classBuilder.append("import io.restassured.filter.log.LogDetail;\n");
        classBuilder.append("import io.restassured.http.ContentType;\n");
        classBuilder.append("import io.restassured.specification.RequestSpecification;\n");
        classBuilder.append("import io.restassured.specification.ResponseSpecification;\n");
        classBuilder.append("import org.testng.annotations.BeforeSuite;\n");
        classBuilder.append("import ").append(basePackage).append(".config.ConfigManager;\n\n");

        // Class declaration
        classBuilder.append("/**\n");
        classBuilder.append(" * Base class for all API test classes\n");
        classBuilder.append(" */\n");
        classBuilder.append("public abstract class ").append(baseClassName).append(" {\n\n");

        // Properties
        classBuilder.append("    private static RequestSpecification requestSpec;\n");
        classBuilder.append("    private static ResponseSpecification responseSpec;\n");
        classBuilder.append("    protected static final ConfigManager CONFIG = ConfigManager.getInstance();\n\n");

        // Setup method
        classBuilder.append("    @BeforeSuite\n");
        classBuilder.append("    public void setupSuite() {\n");
        classBuilder.append("        // Get base URL from configuration\n");
        classBuilder.append("        String baseUrl = CONFIG.getProperty(\"base.url\", \"").append(baseUrl).append("\");\n");
        classBuilder.append("        RestAssured.baseURI = baseUrl;\n\n");

        classBuilder.append("        // Set up default request specification\n");
        classBuilder.append("        requestSpec = new RequestSpecBuilder()\n");
        classBuilder.append("            .setContentType(ContentType.JSON)\n");
        classBuilder.append("            .setAccept(ContentType.JSON)\n");
        classBuilder.append("            .log(LogDetail.ALL)\n");
        classBuilder.append("            .build();\n\n");

        classBuilder.append("        // Set up default response specification\n");
        classBuilder.append("        responseSpec = new ResponseSpecBuilder()\n");
        classBuilder.append("            .log(LogDetail.ALL)\n");
        classBuilder.append("            .build();\n\n");

        classBuilder.append("        // Set timeouts\n");
        classBuilder.append("        RestAssured.config = RestAssured.config()\n");
        classBuilder.append("            .httpClient(RestAssured.config().getHttpClientConfig()\n");
        classBuilder.append("                .setParam(\"http.connection.timeout\", \n");
        classBuilder.append("                    CONFIG.getIntProperty(\"http.connection.timeout\", 30000))\n");
        classBuilder.append("                .setParam(\"http.socket.timeout\", \n");
        classBuilder.append("                    CONFIG.getIntProperty(\"http.socket.timeout\", 60000))\n");
        classBuilder.append("            );\n");
        classBuilder.append("    }\n\n");

        // Authentication method
        classBuilder.append("    /**\n");
        classBuilder.append("     * Set up authentication for requests that require it\n");
        classBuilder.append("     * Override in subclasses if needed\n");
        classBuilder.append("     */\n");
        classBuilder.append("    protected RequestSpecification setAuthentication(RequestSpecification reqSpec) {\n");
        classBuilder.append("        // Example: OAuth2, Basic Auth, API Key, etc.\n");
        classBuilder.append("        String apiKey = CONFIG.getProperty(\"api.key\", \"\");\n");
        classBuilder.append("        if (!apiKey.isEmpty()) {\n");
        classBuilder.append("            return reqSpec.header(\"X-API-Key\", apiKey);\n");
        classBuilder.append("        }\n\n");
        classBuilder.append("        // If no authentication is required, return the request spec as is\n");
        classBuilder.append("        return reqSpec;\n");
        classBuilder.append("    }\n\n");

        // Getter methods
        classBuilder.append("    /**\n");
        classBuilder.append("     * Returns the base request specification to use for all requests\n");
        classBuilder.append("     */\n");
        classBuilder.append("    protected RequestSpecification getRequestSpec() {\n");
        classBuilder.append("        // Apply authentication if needed\n");
        classBuilder.append("        return setAuthentication(requestSpec);\n");
        classBuilder.append("    }\n\n");

        classBuilder.append("    /**\n");
        classBuilder.append("     * Returns the base response specification to use for all responses\n");
        classBuilder.append("     */\n");
        classBuilder.append("    protected ResponseSpecification getResponseSpec() {\n");
        classBuilder.append("        return responseSpec;\n");
        classBuilder.append("    }\n");

        classBuilder.append("}\n");

        try (FileWriter writer = new FileWriter(fullPackagePath + "/" + baseClassName + ".java")) {
            writer.write(classBuilder.toString());
        }

        System.out.println("Generated base test class: " + baseClassName);
    }

    /**
     * Creates the output directory structure
     */
    private static void createOutputDirectory() throws IOException {
        String fullPackagePath = outputDir + "/" + packageName.replace('.', '/');
        Path path = Paths.get(fullPackagePath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

    /**
     * Sanitizes a method name to make it a valid Java identifier
     */
    private static String sanitizeMethodName(String name) {
        // Replace special characters that are invalid in Java identifiers
        String sanitized = name.replaceAll("[^a-zA-Z0-9]", " ");
        sanitized = sanitized.trim();

        // Convert to camelCase (first letter lowercase)
        String camelCase = CaseUtils.toCamelCase(sanitized, false, ' ');

        // Ensure method name doesn't start with a number
        if (!camelCase.isEmpty() && Character.isDigit(camelCase.charAt(0))) {
            camelCase = "test" + camelCase;
        }

        return camelCase;
    }

    /**
     * Checks if a string is a valid JSON
     */
    private static boolean isJsonBody(String body) {
        if (body == null || body.trim().isEmpty()) {
            return false;
        }

        String trimmed = body.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try {
                JsonParser.parseString(trimmed);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
}