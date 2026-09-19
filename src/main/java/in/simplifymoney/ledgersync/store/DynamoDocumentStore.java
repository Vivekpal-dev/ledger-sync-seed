package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DynamoDocumentStore implements DocumentStore {

    private static final String TABLE = "ledger";
    private static final String GSI_ACCOUNT = "AccountMonthIndex";
    private static final String GSI_CATEGORY = "AccountCategoryIndex";

    private final HttpClient client;
    private final URI endpoint;

    public DynamoDocumentStore() {
        this("http://localhost:8000");
    }

    public DynamoDocumentStore(String endpoint) {
        this.client = HttpClient.newHttpClient();
        this.endpoint = URI.create(endpoint);
        ensureTable();
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(
            String accountLast4, YearMonth month) {

        String from = month.atDay(1)
                .atStartOfDay(ZoneOffset.ofHoursMinutes(5, 30))
                .toOffsetDateTime()
                .toString();

        String to = month.plusMonths(1).atDay(1)
                .atStartOfDay(ZoneOffset.ofHoursMinutes(5, 30))
                .toOffsetDateTime()
                .toString();

        Map<String, Object> values = new LinkedHashMap<>();
        values.put(":account", stringValue("ACCOUNT#" + accountLast4));
        values.put(":from", stringValue(from));
        values.put(":to", stringValue(to));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("TableName", TABLE);
        request.put("IndexName", GSI_ACCOUNT);
        request.put(
                "KeyConditionExpression",
                "gsi1pk = :account AND gsi1sk BETWEEN :from AND :to");
        request.put("ExpressionAttributeValues", values);
        request.put("ScanIndexForward", false);

        Map<String, Object> response = call("DynamoDB_20120810.Query", request);

        List<NormalizedTxn> result = new ArrayList<>();

        for (Map<String, Object> item : items(response)) {
            result.add(fromItem(item));
        }

        return result;
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {

        Map<String, Object> values = new LinkedHashMap<>();
        values.put(":account", stringValue("ACCOUNT#" + accountLast4));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("TableName", TABLE);
        request.put("IndexName", GSI_CATEGORY);
        request.put(
                "KeyConditionExpression",
                "gsi2pk = :account");
        request.put("ExpressionAttributeValues", values);

        Map<String, Object> response = call("DynamoDB_20120810.Query", request);

        Map<Category, BigDecimal> result = new LinkedHashMap<>();

        for (Map<String, Object> item : items(response)) {
            String category = string(item, "category");
            BigDecimal total = number(item, "total");

            result.put(Category.valueOf(category), total);
        }

        return result;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {

        Map<String, Object> values = new LinkedHashMap<>();
        values.put(":message", stringValue("MSG#" + messageId));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("TableName", TABLE);
        request.put(
                "KeyConditionExpression",
                "pk = :message");
        request.put("ExpressionAttributeValues", values);

        Map<String, Object> response =
                call("DynamoDB_20120810.Query", request);

        List<Map<String, Object>> rows = items(response);

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(fromItem(rows.get(0)));
    }

    @Override
    public void save(NormalizedTxn txn) {

        String identity = identity(txn);

        Map<String, Object> key = new LinkedHashMap<>();
        key.put("pk", stringValue("TXN#" + identity));
        key.put("sk", stringValue("T"));

        Map<String, Object> existing = get(key);

        if (existing == null) {
            put(transactionItem(txn, identity));
            updateCategoryTotal(txn);
        } else {
            mergeSourceMessageIds(existing, txn, identity);
        }

        /*
         * Message index items make byMessageId() a direct Query.
         * Rewriting the same item is idempotent.
         */
        for (String messageId : txn.sourceMessageIds()) {
            put(messageItem(txn, identity, messageId));
        }
    }

    private void ensureTable() {

        Map<String, Object> request = new LinkedHashMap<>();

        request.put("TableName", TABLE);

        List<Map<String, Object>> attributes = new ArrayList<>();

        attributes.add(attribute("pk", "S"));
        attributes.add(attribute("sk", "S"));
        attributes.add(attribute("gsi1pk", "S"));
        attributes.add(attribute("gsi1sk", "S"));
        attributes.add(attribute("gsi2pk", "S"));
        attributes.add(attribute("gsi2sk", "S"));

        request.put("AttributeDefinitions", attributes);

        List<Map<String, Object>> keySchema = new ArrayList<>();
        keySchema.add(key("pk", "HASH"));
        keySchema.add(key("sk", "RANGE"));
        request.put("KeySchema", keySchema);

        request.put("BillingMode", "PAY_PER_REQUEST");

        List<Map<String, Object>> indexes = new ArrayList<>();

        indexes.add(gsi(
                GSI_ACCOUNT,
                "gsi1pk",
                "gsi1sk"
        ));

        indexes.add(gsi(
                GSI_CATEGORY,
                "gsi2pk",
                "gsi2sk"
        ));

        request.put("GlobalSecondaryIndexes", indexes);

        try {
            call("DynamoDB_20120810.CreateTable", request);
        } catch (RuntimeException e) {
            /*
             * Table already existing is fine.
             * DynamoDB Local reports ResourceInUseException.
             */
            if (!e.getMessage().contains("ResourceInUseException")) {
                throw e;
            }
        }
    }

    private Map<String, Object> transactionItem(
            NormalizedTxn txn,
            String identity) {

        Map<String, Object> item = new LinkedHashMap<>();

        item.put("pk", stringValue("TXN#" + identity));
        item.put("sk", stringValue("T"));
        item.put("kind", stringValue("T"));

        item.put("account_last4", stringValue(txn.accountLast4()));
        item.put("occurred_at", stringValue(txn.occurredAt().toString()));
        item.put("direction", stringValue(txn.direction().name()));
        item.put("amount", numberValue(txn.amount()));
        item.put("category", stringValue(txn.category().name()));
        item.put("merchant", stringValue(txn.merchant()));

        item.put(
                "source_message_ids",
                stringSetValue(txn.sourceMessageIds()));

        /*
         * Q1:
         * ACCOUNT#4821 + timestamp + identity
         */
        item.put(
                "gsi1pk",
                stringValue("ACCOUNT#" + txn.accountLast4()));

        item.put(
                "gsi1sk",
                stringValue(
                        txn.occurredAt().toString()
                                + "#"
                                + identity));

        return item;
    }

   private Map<String, Object> messageItem(
        NormalizedTxn txn,
        String identity,
        String messageId) {

    Map<String, Object> item = new LinkedHashMap<>();

    item.put("pk", stringValue("MSG#" + messageId));
    item.put("sk", stringValue("T#" + identity));
    item.put("kind", stringValue("M"));

    item.put("account_last4", stringValue(txn.accountLast4()));
    item.put("occurred_at", stringValue(txn.occurredAt().toString()));
    item.put("direction", stringValue(txn.direction().name()));
    item.put("amount", numberValue(txn.amount()));
    item.put("category", stringValue(txn.category().name()));
    item.put("merchant", stringValue(txn.merchant()));
    item.put(
            "source_message_ids",
            stringSetValue(txn.sourceMessageIds()));

    return item;
}

    private void updateCategoryTotal(NormalizedTxn txn) {

    String category = txn.category().name();

    Map<String, Object> key = new LinkedHashMap<>();
    key.put(
            "pk",
            stringValue("SUM#" + txn.accountLast4()));
    key.put(
            "sk",
            stringValue("CAT#" + category));

    Map<String, Object> values = new LinkedHashMap<>();
    values.put(":zero", numberValue(BigDecimal.ZERO));
    values.put(":amount", numberValue(txn.amount()));
    values.put(":category", stringValue(category));
    values.put(
            ":account",
            stringValue("ACCOUNT#" + txn.accountLast4()));
    values.put(
            ":catKey",
            stringValue("CAT#" + category));

    Map<String, Object> names = new LinkedHashMap<>();
    names.put("#category", "category");
    names.put("#total", "total");
    names.put("#gsiPk", "gsi2pk");
    names.put("#gsiSk", "gsi2sk");

    Map<String, Object> request = new LinkedHashMap<>();

    request.put("TableName", TABLE);
    request.put("Key", key);

    request.put(
            "UpdateExpression",
            "SET #category = :category, "
                    + "#total = if_not_exists(#total, :zero) + :amount, "
                    + "#gsiPk = :account, "
                    + "#gsiSk = :catKey");

    request.put("ExpressionAttributeNames", names);
    request.put("ExpressionAttributeValues", values);

    call("DynamoDB_20120810.UpdateItem", request);
}

    private void mergeSourceMessageIds(
            Map<String, Object> existing,
            NormalizedTxn txn,
            String identity) {

        List<String> ids = new ArrayList<>();

        Object raw = existing.get("source_message_ids");

        if (raw instanceof Map<?, ?> map) {
            Object values = map.get("SS");

            if (values instanceof List<?> list) {
                for (Object value : list) {
                    ids.add(String.valueOf(value));
                }
            }
        }

        ids.addAll(txn.sourceMessageIds());

        ids = ids.stream()
                .distinct()
                .sorted()
                .toList();

        Map<String, Object> key = new LinkedHashMap<>();
        key.put("pk", stringValue("TXN#" + identity));
        key.put("sk", stringValue("T"));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put(":ids", stringSetValue(ids));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("TableName", TABLE);
        request.put("Key", key);
        request.put(
                "UpdateExpression",
                "SET source_message_ids = :ids");
        request.put("ExpressionAttributeValues", values);

        call("DynamoDB_20120810.UpdateItem", request);
    }

    private Map<String, Object> get(Map<String, Object> key) {

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("TableName", TABLE);
        request.put("Key", key);

        Map<String, Object> response =
                call("DynamoDB_20120810.GetItem", request);

        Object raw = response.get("Item");

        if (!(raw instanceof Map<?, ?>)) {
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> item =
                (Map<String, Object>) raw;

        return item;
    }

    private void put(Map<String, Object> item) {

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("TableName", TABLE);
        request.put("Item", item);

        call("DynamoDB_20120810.PutItem", request);
    }

    private Map<String, Object> call(
            String target,
            Map<String, Object> payload) {

        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header(
                            "Content-Type",
                            "application/x-amz-json-1.0")
                    .header("X-Amz-Target", target)
                    .POST(
                            HttpRequest.BodyPublishers.ofString(
                                    Json.write(payload),
                                    StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    client.send(
                            request,
                            HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200
                    || response.statusCode() >= 300) {

                throw new IllegalStateException(
                        "DynamoDB request failed: HTTP "
                                + response.statusCode()
                                + " "
                                + response.body());
            }

            return Json.parseObject(response.body());

        } catch (Exception e) {
            throw new IllegalStateException(
                    "DynamoDB request failed",
                    e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(
            Map<String, Object> response) {

        Object raw = response.get("Items");

        if (!(raw instanceof List<?> list)) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();

        for (Object item : list) {
            result.add((Map<String, Object>) item);
        }

        return result;
    }

    private static NormalizedTxn fromItem(
            Map<String, Object> item) {

        List<String> sourceIds = stringSet(item, "source_message_ids");

        return new NormalizedTxn(
                string(item, "account_last4"),
                java.time.OffsetDateTime.parse(
                        string(item, "occurred_at")),
                Direction.valueOf(
                        string(item, "direction")),
                number(item, "amount"),
                Category.valueOf(
                        string(item, "category")),
                string(item, "merchant"),
                sourceIds.stream()
                        .sorted()
                        .toList());
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringSet(
            Map<String, Object> item,
            String field) {

        Object raw = item.get(field);

        if (!(raw instanceof Map<?, ?> map)) {
            return List.of();
        }

        Object values = map.get("SS");

        if (!(values instanceof List<?> list)) {
            return List.of();
        }

        List<String> result = new ArrayList<>();

        for (Object value : list) {
            result.add(String.valueOf(value));
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private static String string(
            Map<String, Object> item,
            String field) {

        Object raw = item.get(field);

        if (!(raw instanceof Map<?, ?> map)) {
            return "";
        }

        Object value = map.get("S");

        return value == null
                ? ""
                : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static BigDecimal number(
            Map<String, Object> item,
            String field) {

        Object raw = item.get(field);

        if (!(raw instanceof Map<?, ?> map)) {
            return BigDecimal.ZERO;
        }

        Object value = map.get("N");

        return new BigDecimal(String.valueOf(value));
    }

    private static Map<String, Object> stringValue(
            String value) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("S", value);
        return result;
    }

    private static Map<String, Object> numberValue(
            BigDecimal value) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("N", value.toPlainString());
        return result;
    }

    private static Map<String, Object> stringSetValue(
            List<String> values) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("SS", values);
        return result;
    }

    private static Map<String, Object> attribute(
            String name,
            String type) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("AttributeName", name);
        result.put("AttributeType", type);
        return result;
    }

    private static Map<String, Object> key(
            String name,
            String type) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("AttributeName", name);
        result.put("KeyType", type);
        return result;
    }

    private static Map<String, Object> gsi(
            String name,
            String hash,
            String range) {

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("IndexName", name);

        List<Map<String, Object>> schema = new ArrayList<>();
        schema.add(key(hash, "HASH"));
        schema.add(key(range, "RANGE"));

        result.put("KeySchema", schema);

        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("ProjectionType", "ALL");

        result.put("Projection", projection);

        return result;
    }

    private static String identity(NormalizedTxn txn) {

        String value =
                txn.accountLast4()
                        + "|"
                        + txn.occurredAt()
                        + "|"
                        + txn.direction()
                        + "|"
                        + txn.amount().toPlainString();

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] bytes =
                    digest.digest(
                            value.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder();

            for (byte b : bytes) {
                hex.append(
                        String.format("%02x", b));
            }

            return hex.toString();

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not create transaction identity",
                    e);
        }
    }
}