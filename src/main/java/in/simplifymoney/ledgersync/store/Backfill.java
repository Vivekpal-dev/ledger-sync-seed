package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(
            SqlLedgerStore source,
            DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {

        List<NormalizedTxn> rows = source.all();

        /*
         * Historical SQL data may contain duplicates.
         *
         * The transaction identity is:
         * account + occurredAt + direction + amount
         *
         * If duplicate rows represent the same transaction,
         * merge their source message IDs rather than copying
         * duplicate transactions into the document store.
         */
        Map<String, NormalizedTxn> unique =
                new LinkedHashMap<>();

        long skipped = 0;

        for (NormalizedTxn txn : rows) {

            String key = identity(txn);

            NormalizedTxn existing = unique.get(key);

            if (existing == null) {
                unique.put(key, txn);
            } else {
                List<String> mergedIds =
                        new ArrayList<>(
                                existing.sourceMessageIds());

                mergedIds.addAll(
                        txn.sourceMessageIds());

                mergedIds = mergedIds.stream()
                        .distinct()
                        .sorted()
                        .toList();

                NormalizedTxn merged =
                        new NormalizedTxn(
                                existing.accountLast4(),
                                existing.occurredAt(),
                                existing.direction(),
                                existing.amount(),
                                existing.category(),
                                existing.merchant(),
                                mergedIds);

                unique.put(key, merged);
                skipped++;
            }
        }

        long written = 0;

        for (NormalizedTxn txn : unique.values()) {
            target.save(txn);
            written++;
        }

        return new Result(
                rows.size(),
                written,
                skipped);
    }

    private static String identity(NormalizedTxn txn) {
        return txn.accountLast4()
                + "|"
                + txn.occurredAt()
                + "|"
                + txn.direction()
                + "|"
                + txn.amount().toPlainString();
    }

    public record Result(
            long read,
            long written,
            long skipped) {
    }
}