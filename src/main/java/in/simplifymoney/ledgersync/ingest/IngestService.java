package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.BalanceCheckpoint;
import in.simplifymoney.ledgersync.store.LedgerStore;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    private final List<BalanceCheckpoint> balanceCheckpoints =
            new ArrayList<>();

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {

        List<RawMessage> messages = readCorpus(corpus);

        Map<TxnKey, NormalizedTxn> transactions =
                new LinkedHashMap<>();

        int parsed = 0;
        int skipped = 0;

        for (RawMessage m : messages) {

            Optional<ParsedTxn> p = parsers.parse(m);

            /*
             * Preserve bank balance evidence for reconciliation.
             *
             * statedBalance is intentionally not part of NormalizedTxn,
             * so we keep it separately as a BalanceCheckpoint.
             */
            if (p.isPresent() && p.get().statedBalance() != null) {

                balanceCheckpoints.add(
                        new BalanceCheckpoint(
                                p.get().accountLast4(),
                                p.get().occurredAt(),
                                p.get().statedBalance(),
                                p.get().sourceMessageId()
                        )
                );
            }

            if (p.isEmpty()) {
                skipped++;
                continue;
            }

            parsed++;

            NormalizedTxn txn = toTransaction(p.get());

            TxnKey key = TxnKey.from(txn);

            NormalizedTxn existing = transactions.get(key);

            if (existing == null) {
                transactions.put(key, txn);
            } else {
                transactions.put(key, merge(existing, txn));
            }
        }

        for (NormalizedTxn txn : transactions.values()) {
            store.save(txn);
        }

        return new Stats(
                messages.size(),
                transactions.size(),
                skipped
        );
    }

    public List<BalanceCheckpoint> balanceCheckpoints() {
        return List.copyOf(balanceCheckpoints);
    }

    public static List<RawMessage> readCorpus(Path corpus)
            throws IOException {

        try (Stream<String> lines = Files.lines(corpus)) {

            return lines
                    .filter(line -> !line.isBlank())
                    .map(line -> Json.parseObject(line))
                    .map(IngestService::toRawMessage)
                    .toList();
        }
    }

  private static RawMessage toRawMessage(
        Map<String, Object> json) {

    return new RawMessage(
            (String) json.get("message_id"),
            (String) json.get("channel"),
            (String) json.get("sender"),
            OffsetDateTime.parse((String) json.get("received_at")),
            (String) json.get("subject"),
            (String) json.get("body")
    );
}

    private NormalizedTxn toTransaction(ParsedTxn p) {

        Category category;

        String merchant =
                p.merchant() == null
                        ? ""
                        : p.merchant().toUpperCase();

        boolean ownAccountTransfer =
                merchant.contains("PARAG KAPOOR");

        boolean micro =
                p.direction() == Direction.DEBIT
                        && merchant.contains("UPI")
                        && p.amount().compareTo(
                                BigDecimal.valueOf(100)
                        ) <= 0;

        if (ownAccountTransfer) {

            category = Category.TRANSFER;

        } else if (micro) {

            category = Category.MICRO;

        } else if (p.direction() == Direction.DEBIT) {

            category = Category.SPEND;

        } else {

            category = Category.INCOME;
        }

        return new NormalizedTxn(
                p.accountLast4(),
                p.occurredAt(),
                p.direction(),
                p.amount(),
                category,
                p.merchant(),
                List.of(p.sourceMessageId())
        );
    }

    private NormalizedTxn merge(
            NormalizedTxn first,
            NormalizedTxn second) {

        Set<String> sourceIds =
                new LinkedHashSet<>(first.sourceMessageIds());

        sourceIds.addAll(second.sourceMessageIds());

        List<String> mergedSourceIds =
                sourceIds.stream()
                        .sorted()
                        .toList();

        return new NormalizedTxn(
                first.accountLast4(),
                first.occurredAt(),
                first.direction(),
                first.amount(),
                first.category(),
                first.merchant(),
                mergedSourceIds
        );
    }

    private record TxnKey(
            String accountLast4,
            OffsetDateTime occurredAt,
            Direction direction,
            BigDecimal amount) {

        static TxnKey from(NormalizedTxn txn) {

            return new TxnKey(
                    txn.accountLast4(),
                    txn.occurredAt(),
                    txn.direction(),
                    txn.amount()
            );
        }
    }

    public record Stats(
            int messagesRead,
            int transactionsWritten,
            int messagesSkipped) {
    }
}