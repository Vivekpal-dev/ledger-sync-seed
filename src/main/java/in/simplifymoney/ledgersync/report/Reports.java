package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO =
            BigDecimal.ZERO.setScale(2);

    // =========================================================
    // SUMMARY
    // =========================================================

    public static Map<String, Object> summary(
            List<NormalizedTxn> ledger) {

        Map<String, Object> accounts = new LinkedHashMap<>();

        for (String acct : new TreeSet<>(
                ledger.stream()
                        .map(NormalizedTxn::accountLast4)
                        .toList())) {

            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            BigDecimal microTotal = ZERO;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn = ZERO;

            int microCount = 0;

            for (NormalizedTxn t : ledger) {

                if (!t.accountLast4().equals(acct)) {
                    continue;
                }

                if (t.category() == Category.SPEND) {

                    spend = spend.add(t.amount());

                } else if (t.category() == Category.INCOME) {

                    income = income.add(t.amount());

                } else if (t.category() == Category.MICRO) {

                    microCount++;
                    microTotal = microTotal.add(t.amount());

                } else if (t.category() == Category.TRANSFER) {

                    if (t.direction() == Direction.DEBIT) {
                        transferredOut =
                                transferredOut.add(t.amount());
                    } else {
                        transferredIn =
                                transferredIn.add(t.amount());
                    }
                }
            }

            Map<String, Object> account =
                    new LinkedHashMap<>();

            account.put(
                    "spend",
                    spend.toPlainString()
            );

            account.put(
                    "income",
                    income.toPlainString()
            );

            account.put(
                    "micro_count",
                    microCount
            );

            account.put(
                    "micro_total",
                    microTotal.toPlainString()
            );

            account.put(
                    "transferred_out",
                    transferredOut.toPlainString()
            );

            account.put(
                    "transferred_in",
                    transferredIn.toPlainString()
            );

            accounts.put(acct, account);
        }

        Map<String, Object> doc =
                new LinkedHashMap<>();

        doc.put("accounts", accounts);

        return doc;
    }

    // =========================================================
    // LEDGER DOCUMENT
    // =========================================================

   public static Map<String, Object> ledgerDocument(
        List<NormalizedTxn> ledger) {

    List<Map<String, Object>> transactions = new ArrayList<>();

    for (NormalizedTxn txn : ledger) {
        Map<String, Object> item = new LinkedHashMap<>();

        item.put("account_last4", txn.accountLast4());
        item.put("occurred_at", txn.occurredAt().toString());
        item.put("direction", txn.direction().name());
        item.put("amount", txn.amount().toString());
        item.put("category", txn.category().name());
        item.put("merchant", txn.merchant());
        item.put("source_message_ids", txn.sourceMessageIds());

        transactions.add(item);
    }

    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("transactions", transactions);

    return doc;
}

    // =========================================================
    // RECONCILIATION
    // =========================================================

    public static Map<String, Object> reconciliation(
            List<NormalizedTxn> ledger,
            List<BalanceCheckpoint> checkpoints) {

        Map<String, Object> result =
                new LinkedHashMap<>();

        List<Map<String, Object>> discrepancies =
                new ArrayList<>();

        // Process each account separately.
        for (String account :
                new TreeSet<>(
                        checkpoints.stream()
                                .map(BalanceCheckpoint::accountLast4)
                                .toList())) {

            List<BalanceCheckpoint> accountCheckpoints =
                    checkpoints.stream()
                            .filter(c ->
                                    c.accountLast4()
                                            .equals(account))
                            .sorted(
                                    Comparator.comparing(
                                            BalanceCheckpoint::occurredAt))
                            .toList();

            if (accountCheckpoints.size() < 2) {
                continue;
            }

            // Compare every pair of consecutive bank checkpoints.
            for (int i = 1;
                 i < accountCheckpoints.size();
                 i++) {

                BalanceCheckpoint previous =
                        accountCheckpoints.get(i - 1);

                BalanceCheckpoint current =
                        accountCheckpoints.get(i);

                /*
                 * Start from the previous bank-reported balance.
                 */
                BigDecimal expectedBalance =
                       previous.statedBalance();

                /*
                 * Apply all ledger transactions in:
                 *
                 * previous < transaction <= current
                 *
                 * The current checkpoint transaction must be included.
                 */
                for (NormalizedTxn t : ledger) {

                    if (!t.accountLast4()
                            .equals(account)) {
                        continue;
                    }

                    boolean afterPrevious =
                            t.occurredAt()
                                    .isAfter(
                                            previous.occurredAt());

                    boolean atOrBeforeCurrent =
                            t.occurredAt()
                                    .isBefore(
                                            current.occurredAt())
                            || t.occurredAt()
                                    .isEqual(
                                            current.occurredAt());

                    if (afterPrevious
                            && atOrBeforeCurrent) {

                        if (t.direction()
                                == Direction.DEBIT) {

                            expectedBalance =
                                    expectedBalance
                                            .subtract(
                                                    t.amount());

                        } else {

                            expectedBalance =
                                    expectedBalance
                                            .add(
                                                    t.amount());
                        }
                    }
                }

                /*
                 * Compare our calculated balance with
                 * the bank's checkpoint.
                 */
                BigDecimal difference =
                        current.statedBalance()
                                .subtract(expectedBalance)
                                .setScale(2);

                /*
                 * If there is no difference, reconciliation
                 * is successful for this interval.
                 */
                if (difference.compareTo(ZERO) == 0) {
                    continue;
                }

                Map<String, Object> discrepancy =
                        new LinkedHashMap<>();

                discrepancy.put(
                        "account_last4",
                        account
                );

                discrepancy.put(
                        "occurred_at",
                        current.occurredAt().toString()
                );

                discrepancy.put(
                        "amount",
                        difference.abs().toPlainString()
                );

                discrepancy.put(
                        "note",
                        "Bank balance differs from the balance "
                                + "implied by ledger transactions between "
                                + previous.occurredAt()
                                + " and "
                                + current.occurredAt()
                                + ". Checkpoint source message: "
                                + current.sourceMessageId()
                );

                discrepancies.add(discrepancy);
            }
        }

        result.put(
                "discrepancies",
                discrepancies
        );

        return result;
    }

    // =========================================================
    // CATEGORY TOTALS
    // =========================================================

    public static Map<Category, BigDecimal> byCategory(
            List<NormalizedTxn> ledger) {

        Map<Category, BigDecimal> totals =
                new LinkedHashMap<>();

        for (Category category : Category.values()) {
            totals.put(category, ZERO);
        }

        for (NormalizedTxn t : ledger) {

            BigDecimal current =
                    totals.get(t.category());

            totals.put(
                    t.category(),
                    current.add(t.amount())
            );
        }

        return totals;
    }
}