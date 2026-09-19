package in.simplifymoney.ledgersync.report;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record BalanceCheckpoint(
        String accountLast4,
        OffsetDateTime occurredAt,
        BigDecimal statedBalance,
        String sourceMessageId) {
}
