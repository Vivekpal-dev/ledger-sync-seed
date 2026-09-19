package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(
            SqlLedgerStore sql,
            DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {

        List<Divergence> divergences =
                new ArrayList<>();

        /*
         * SQL is the source of truth for the comparison.
         * First remove historical duplicate rows using the
         * same transaction identity used by Backfill.
         */
        Map<String, NormalizedTxn> expected =
                new LinkedHashMap<>();

        for (NormalizedTxn txn : sql.all()) {
            expected.put(identity(txn), txn);
        }

        /*
         * Query every account/month represented in SQL.
         * This lets us detect:
         *
         * - missing document transactions
         * - extra document transactions
         * - changed transaction fields
         */
        Map<String, List<NormalizedTxn>> actualByPartition =
                new LinkedHashMap<>();

        for (NormalizedTxn txn : expected.values()) {

            String partition =
                    txn.accountLast4()
                            + "|"
                            + YearMonth.from(txn.occurredAt());

            if (!actualByPartition.containsKey(partition)) {

                List<NormalizedTxn> actual =
                        documents.forAccountMonth(
                                txn.accountLast4(),
                                YearMonth.from(txn.occurredAt()));

                actualByPartition.put(
                        partition,
                        actual);
            }
        }

        Map<String, NormalizedTxn> actual =
                new LinkedHashMap<>();

        for (List<NormalizedTxn> rows :
                actualByPartition.values()) {

            for (NormalizedTxn txn : rows) {
                actual.put(identity(txn), txn);
            }
        }

        /*
         * Compare expected SQL transactions against
         * document transactions.
         */
        for (Map.Entry<String, NormalizedTxn> entry :
                expected.entrySet()) {

            String key = entry.getKey();
            NormalizedTxn sqlTxn = entry.getValue();

            NormalizedTxn documentTxn =
                    actual.get(key);

            if (documentTxn == null) {

                divergences.add(
                        new Divergence(
                                "missing transaction "
                                        + key,
                                describe(sqlTxn),
                                "<missing>"));

                continue;
            }

            compareFields(
                    divergences,
                    sqlTxn,
                    documentTxn);
        }

        /*
         * Detect transactions existing in the document store
         * but not in SQL.
         */
        for (Map.Entry<String, NormalizedTxn> entry :
                actual.entrySet()) {

            if (!expected.containsKey(entry.getKey())) {

                divergences.add(
                        new Divergence(
                                "extra transaction "
                                        + entry.getKey(),
                                "<missing>",
                                describe(entry.getValue())));
            }
        }

        /*
         * Make output deterministic.
         */
        divergences.sort(
                Comparator.comparing(
                        Divergence::what));

        return divergences;
    }

    private static void compareFields(
            List<Divergence> divergences,
            NormalizedTxn sqlTxn,
            NormalizedTxn documentTxn) {

        String identity =
                identity(sqlTxn);

        if (!sqlTxn.direction()
                .equals(documentTxn.direction())) {

            divergences.add(
                    new Divergence(
                            "direction " + identity,
                            sqlTxn.direction().name(),
                            documentTxn.direction().name()));
        }

        if (!sqlTxn.amount()
                .equals(documentTxn.amount())) {

            divergences.add(
                    new Divergence(
                            "amount " + identity,
                            sqlTxn.amount().toPlainString(),
                            documentTxn.amount().toPlainString()));
        }

        if (!sqlTxn.category()
                .equals(documentTxn.category())) {

            divergences.add(
                    new Divergence(
                            "category " + identity,
                            sqlTxn.category().name(),
                            documentTxn.category().name()));
        }

        if (!sqlTxn.merchant()
                .equals(documentTxn.merchant())) {

            divergences.add(
                    new Divergence(
                            "merchant " + identity,
                            sqlTxn.merchant(),
                            documentTxn.merchant()));
        }

        List<String> sqlIds =
                sqlTxn.sourceMessageIds()
                        .stream()
                        .sorted()
                        .toList();

        List<String> documentIds =
                documentTxn.sourceMessageIds()
                        .stream()
                        .sorted()
                        .toList();

        if (!sqlIds.equals(documentIds)) {

            divergences.add(
                    new Divergence(
                            "source_message_ids " + identity,
                            String.join(",", sqlIds),
                            String.join(",", documentIds)));
        }
    }

    private static String describe(
            NormalizedTxn txn) {

        return "account="
                + txn.accountLast4()
                + ", occurred_at="
                + txn.occurredAt()
                + ", direction="
                + txn.direction()
                + ", amount="
                + txn.amount()
                + ", category="
                + txn.category()
                + ", merchant="
                + txn.merchant()
                + ", source_message_ids="
                + String.join(
                        ",",
                        txn.sourceMessageIds());
    }

    private static String identity(
            NormalizedTxn txn) {

        return txn.accountLast4()
                + "|"
                + txn.occurredAt()
                + "|"
                + txn.direction()
                + "|"
                + txn.amount().toPlainString();
    }

    public record Divergence(
            String what,
            String inSql,
            String inDocuments) {
    }
}