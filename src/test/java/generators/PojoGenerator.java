package generators;

import com.google.gson.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;


import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Generates POJO classes from Postman collection JSON payload examples.
 * This generator handles nested objects, array types, and properly manages
 * the generation of complex object hierarchies.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PojoGenerator {
    // Default configuration - can be customized via builder
    private static String outputDir = "src/main/java";
    private static String packageName = "models";
    private static boolean useLombok = true;
    private static boolean useJacksonAnnotations = true;

    // Tracking generated classes to avoid duplicates
    private static final Set<String> generatedClasses = new HashSet<>();
    private static final Map<String, String> jsonHashToClassName = new HashMap<>();

    // Pattern for valid Java identifiers
    private static final Pattern VALID_JAVA_IDENTIFIER = Pattern.compile("^[a-zA-Z_$][a-zA-Z0-9_$]*$");

    /**
     * Configures the generator with custom settings
     */
    public static class Config {
        private String outputDir = PojoGenerator.outputDir;
        private String packageName = PojoGenerator.packageName;
        private boolean useLombok = PojoGenerator.useLombok;
        private boolean useJacksonAnnotations = PojoGenerator.useJacksonAnnotations;

        public Config setOutputDir(String dir) {
            this.outputDir = dir;
            return this;
        }

        public Config setPackageName(String pkg) {
            this.packageName = pkg;
            return this;
        }

        public Config setUseLombok(boolean use) {
            this.useLombok = use;
            return this;
        }

        public Config setUseJacksonAnnotations(boolean use) {
            this.useJacksonAnnotations = use;
            return this;
        }

        public void apply() {
            PojoGenerator.outputDir = this.outputDir;
            PojoGenerator.packageName = this.packageName;
            PojoGenerator.useLombok = this.useLombok;
            PojoGenerator.useJacksonAnnotations = this.useJacksonAnnotations;
        }
    }

    /**
     * Main entry point to generate POJOs from a Postman collection file
     *
     * @param postmanCollectionPath Path to the Postman collection JSON file
     * @throws IOException If file operations fail
     */
    public static void generatePojos(String postmanCollectionPath) throws IOException {
        JsonObject collection = JsonParser.parseReader(new FileReader(postmanCollectionPath)).getAsJsonObject();

        createOutputDirectory();
        generatedClasses.clear();
        jsonHashToClassName.clear();

        // Process the collection recursively to handle nested folders
        if (collection.has("item")) {
            processItemsRecursively(collection.getAsJsonArray("item"), "");
        } else {
            System.err.println("Invalid Postman collection format: 'item' field not found");
        }
    }

    /**
     * Recursively processes items in the Postman collection, handling nested folders
     */
    private static void processItemsRecursively(JsonArray items, String folderPrefix) throws IOException {
        for (JsonElement item : items) {
            JsonObject itemObj = item.getAsJsonObject();

            // Check if this is a folder (contains subitems)
            if (itemObj.has("item")) {
                String folderName = itemObj.has("name") ? itemObj.get("name").getAsString() : "";
                String newPrefix = StringUtils.isNotEmpty(folderPrefix)
                        ? folderPrefix + capitalize(folderName)
                        : capitalize(folderName);

                processItemsRecursively(itemObj.getAsJsonArray("item"), newPrefix);
            } else {
                // This is an endpoint, not a folder
                String itemName = itemObj.has("name") ? itemObj.get("name").getAsString() : "Unknown";

                if (itemObj.has("request")) {
                    processRequest(itemObj.getAsJsonObject("request"), folderPrefix + capitalize(itemName));
                }

                if (itemObj.has("response")) {
                    processResponses(itemObj.getAsJsonArray("response"), folderPrefix + capitalize(itemName));
                }
            }
        }
    }

    /**
     * Processes a request object to extract and generate POJOs for request bodies
     */
    private static void processRequest(JsonObject request, String baseName) throws IOException {
        if (!request.has("body") || !request.getAsJsonObject("body").has("raw")) {
            return;
        }

        String rawBody = request.getAsJsonObject("body").get("raw").getAsString();
        if (!isValidJson(rawBody)) {
            return;
        }

        try {
            JsonElement bodyJson = JsonParser.parseString(rawBody);
            String className = baseName + "Request";

            if (bodyJson.isJsonObject()) {
                generatePojoClassesRecursively(className, bodyJson.getAsJsonObject(), new HashSet<>());
            } else {
                System.err.println("Request body for " + baseName + " is not a JSON object");
            }
        } catch (Exception e) {
            System.err.println("Error processing request body for " + baseName + ": " + e.getMessage());
        }
    }

    /**
     * Processes response objects to extract and generate POJOs for response bodies
     */
    private static void processResponses(JsonArray responses, String baseName) throws IOException {
        for (int i = 0; i < responses.size(); i++) {
            JsonObject response = responses.get(i).getAsJsonObject();
            if (!response.has("body")) {
                continue;
            }

            String rawBody = response.get("body").getAsString();
            if (!isValidJson(rawBody)) {
                continue;
            }

            try {
                JsonElement bodyJson = JsonParser.parseString(rawBody);
                String statusCode = response.has("code") ? response.get("code").getAsString() : String.valueOf(i);
                String className = baseName + "Response" + sanitizeForClassName(statusCode);

                if (bodyJson.isJsonObject()) {
                    generatePojoClassesRecursively(className, bodyJson.getAsJsonObject(), new HashSet<>());
                } else {
                    System.err.println("Response body for " + className + " is not a JSON object");
                }
            } catch (Exception e) {
                System.err.println("Error processing response body for " + baseName + ": " + e.getMessage());
            }
        }
    }

    /**
     * Recursively generates POJO classes for a JSON object and all its nested objects
     */
    private static void generatePojoClassesRecursively(String className, JsonObject jsonObject,
                                                       Set<String> generatedInCurrentBranch) throws IOException {
        // Check for class name collisions based on content hash
        String jsonHash = jsonObject.toString().hashCode() + "";

        if (jsonHashToClassName.containsKey(jsonHash)) {
            // We've already generated a class for this exact structure, just use that name
            String existingClassName = jsonHashToClassName.get(jsonHash);
            if (!existingClassName.equals(className)) {
                System.out.println("Using existing class " + existingClassName + " for " + className
                        + " (identical structure)");
                return;
            }
        }

        jsonHashToClassName.put(jsonHash, className);

        if (generatedClasses.contains(className)) {
            return; // Already generated this class
        }

        if (generatedInCurrentBranch.contains(className)) {
            System.err.println("Circular reference detected for class " + className + ", skipping");
            return;
        }

        generatedInCurrentBranch.add(className);

        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, JsonObject> nestedObjects = new LinkedHashMap<>();
        Map<String, JsonObject> nestedArrayObjects = new LinkedHashMap<>();

        // First pass - analyze all fields and identify nested structures
        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
            String fieldName = sanitizeFieldName(entry.getKey());
            JsonElement element = entry.getValue();

            if (element.isJsonObject()) {
                // This is a nested object - we'll need to generate a class for it
                String nestedClassName = className + capitalize(fieldName);
                fields.put(fieldName, nestedClassName);
                nestedObjects.put(nestedClassName, element.getAsJsonObject());
            } else if (element.isJsonArray() && !element.getAsJsonArray().isEmpty()) {
                // For arrays, we need to determine the component type
                JsonArray array = element.getAsJsonArray();
                String componentType = determineArrayComponentType(array, className, fieldName,
                        nestedArrayObjects);
                fields.put(fieldName, "List<" + componentType + ">");
            } else {
                // Simple type
                String fieldType = determineFieldType(element);
                fields.put(fieldName, fieldType);
            }
        }

        // Generate the class file
        generatePojoClass(className, fields);
        generatedClasses.add(className);
        generatedInCurrentBranch.remove(className);

        // Second pass - generate classes for all nested objects
        for (Map.Entry<String, JsonObject> entry : nestedObjects.entrySet()) {
            generatePojoClassesRecursively(entry.getKey(), entry.getValue(), generatedInCurrentBranch);
        }

        // Third pass - generate classes for array components that are objects
        for (Map.Entry<String, JsonObject> entry : nestedArrayObjects.entrySet()) {
            generatePojoClassesRecursively(entry.getKey(), entry.getValue(), generatedInCurrentBranch);
        }
    }

    /**
     * Determines the component type of an array
     */
    private static String determineArrayComponentType(JsonArray array, String parentClassName,
                                                      String fieldName, Map<String, JsonObject> nestedArrayObjects) {
        // Look at the first few elements to determine the common type
        Set<String> types = new HashSet<>();
        JsonObject sampleObject = null;

        // Check up to 5 elements to determine type consistency
        for (int i = 0; i < Math.min(array.size(), 5); i++) {
            JsonElement element = array.get(i);
            if (element.isJsonNull()) continue;

            if (element.isJsonPrimitive()) {
                JsonPrimitive primitive = element.getAsJsonPrimitive();
                if (primitive.isString()) types.add("String");
                else if (primitive.isNumber()) types.add("Double");
                else if (primitive.isBoolean()) types.add("Boolean");
            } else if (element.isJsonObject()) {
                types.add("Object");
                if (sampleObject == null) {
                    sampleObject = element.getAsJsonObject();
                }
            } else if (element.isJsonArray()) {
                types.add("List");
            }
        }

        // If we have a consistent object type, create a class for it
        if (types.size() == 1 && types.contains("Object") && sampleObject != null) {
            String componentClassName = parentClassName + capitalize(fieldName) + "Item";
            nestedArrayObjects.put(componentClassName, sampleObject);
            return componentClassName;
        }

        // Default or mixed types
        if (types.size() != 1) {
            return "Object";
        }

        // Single consistent primitive type
        return types.iterator().next();
    }

    /**
     * Determines the Java type for a JSON element
     */
    private static String determineFieldType(JsonElement element) {
        if (element == null || element.isJsonNull()) return "Object";

        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) return "String";
            if (primitive.isBoolean()) return "Boolean";

            if (primitive.isNumber()) {
                // Try to infer if it's an integer or floating point
                String numStr = primitive.getAsString();
                if (numStr.contains(".")) {
                    return "Double";
                }

                // Check if it fits in an Integer or needs along
                try {
                    long value = primitive.getAsLong();
                    if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
                        return "Long";
                    }
                    return "Integer";
                } catch (NumberFormatException e) {
                    return "Double";
                }
            }
        }

        if (element.isJsonArray()) return "List<Object>";

        return "Object";
    }

    /**
     * Generates a Java POJO class file
     */
    private static void generatePojoClass(String className, Map<String, String> fields) throws IOException {
        StringBuilder classBuilder = new StringBuilder();

        // Package declaration
        classBuilder.append("package ").append(packageName).append(";\n\n");

        // Imports
        Set<String> imports = new HashSet<>();
        imports.add("java.util.List");
        imports.add("java.util.Map");

        if (useLombok) {
            imports.add("lombok.Data");
            imports.add("lombok.NoArgsConstructor");
            imports.add("lombok.AllArgsConstructor");
        }

        if (useJacksonAnnotations) {
            imports.add("com.fasterxml.jackson.annotation.JsonIgnoreProperties");
            imports.add("com.fasterxml.jackson.annotation.JsonProperty");
        }

        for (String importClass : imports) {
            classBuilder.append("import ").append(importClass).append(";\n");
        }
        classBuilder.append("\n");

        // Class annotations
        if (useLombok) {
            classBuilder.append("@Data\n");
            classBuilder.append("@NoArgsConstructor\n");
            classBuilder.append("@AllArgsConstructor\n");
        }

        if (useJacksonAnnotations) {
            classBuilder.append("@JsonIgnoreProperties(ignoreUnknown = true)\n");
        }

        // Class declaration
        classBuilder.append("public class ").append(className).append(" {\n");

        // Fields
        for (Map.Entry<String, String> field : fields.entrySet()) {
            String fieldName = field.getKey();
            String fieldType = field.getValue();

            if (useJacksonAnnotations && !isValidJavaIdentifier(fieldName)) {
                classBuilder.append("    @JsonProperty(\"").append(fieldName).append("\")\n");
            }

            classBuilder.append("    private ").append(fieldType).append(" ");

            // Ensure field name is a valid Java identifier
            if (!isValidJavaIdentifier(fieldName)) {
                fieldName = makeValidJavaIdentifier(fieldName);
            }

            classBuilder.append(fieldName).append(";\n\n");
        }

        // Add getters and setters if not using Lombok
        if (!useLombok) {
            for (Map.Entry<String, String> field : fields.entrySet()) {
                String fieldName = field.getKey();
                String fieldType = field.getValue();

                // Ensure field name is a valid Java identifier for method names
                if (!isValidJavaIdentifier(fieldName)) {
                    fieldName = makeValidJavaIdentifier(fieldName);
                }

                String capitalizedFieldName = capitalize(fieldName);

                // Getter
                classBuilder.append("    public ").append(fieldType).append(" get")
                        .append(capitalizedFieldName).append("() {\n");
                classBuilder.append("        return this.").append(fieldName).append(";\n");
                classBuilder.append("    }\n\n");

                // Setter
                classBuilder.append("    public void set").append(capitalizedFieldName)
                        .append("(").append(fieldType).append(" ").append(fieldName).append(") {\n");
                classBuilder.append("        this.").append(fieldName).append(" = ").append(fieldName).append(";\n");
                classBuilder.append("    }\n\n");
            }
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

        System.out.println("Generated class: " + className);
    }

    /**
     * Checks if a string is a valid JSON
     */
    private static boolean isValidJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return false;
        }

        try {
            JsonParser.parseString(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Creates the output directory structure
     */
    static void createOutputDirectory() throws IOException {
        String fullPackagePath = outputDir + "/" + packageName.replace('.', '/');
        Path path = Paths.get(fullPackagePath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

    /**
     * Capitalizes the first letter of a string
     */
    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        if (str.length() == 1) return str.toUpperCase();
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /**
     * Sanitizes a field name to make it a valid Java identifier
     */
    private static String sanitizeFieldName(String fieldName) {
        // Replace special characters that are invalid in Java identifiers
        String sanitized = fieldName;

        // Handle reserved keywords
        Set<String> javaKeywords = Set.of(
                "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
                "continue", "default", "do", "double", "else", "enum", "extends", "false", "final", "finally",
                "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
                "native", "new", "null", "package", "private", "protected", "public", "return", "short",
                "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient",
                "true", "try", "void", "volatile", "while"
        );

        if (javaKeywords.contains(sanitized)) {
            return sanitized + "Field";
        }

        return sanitized;
    }

    /**
     * Checks if a string is a valid Java identifier
     */
    private static boolean isValidJavaIdentifier(String identifier) {
        return VALID_JAVA_IDENTIFIER.matcher(identifier).matches();
    }

    /**
     * Makes a string into a valid Java identifier
     */
    private static String makeValidJavaIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return "field";
        }

        StringBuilder sb = new StringBuilder();

        // First character must be a letter, underscore, or dollar sign
        char firstChar = identifier.charAt(0);
        if (!Character.isJavaIdentifierStart(firstChar)) {
            sb.append("field_");
        }

        // Replace invalid characters
        for (char c : identifier.toCharArray()) {
            if (Character.isJavaIdentifierPart(c)) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }

        return sb.toString();
    }

    /**
     * Sanitizes a string to be used as part of a class name
     */
    private static String sanitizeForClassName(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        // Keep only alphanumeric characters and capitalize each word
        String[] parts = input.split("[^a-zA-Z0-9]");
        StringBuilder result = new StringBuilder();

        for (String part : parts) {
            if (!part.isEmpty()) {
                result.append(capitalize(part));
            }
        }

        return result.toString();
    }
}