package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmailParser implements MessageParser {

    private static final Pattern TRANSACTION = Pattern.compile(
            "Your account ending (?<acct>\\d{4}) has been "
                    + "(?<dir>debited|credited) with "
                    + "(?:INR|Rs\\.?)\\s*(?<amount>[0-9,]+(?:\\.[0-9]{2})?)\\."
                    + "\\s*\\RMerchant / Remarks:\\s*(?<merchant>[^\\r\\n]+)"
    );

    private static final Pattern DATE = Pattern.compile(
            "(?m)^Date:\\s*(?<date>\\w{3},\\s*\\d{2} \\w{3} "
                    + "\\d{4} \\d{2}:\\d{2}:\\d{2} [+-]\\d{4})$"
    );

    private static final DateTimeFormatter EMAIL_DATE =
            DateTimeFormatter.ofPattern(
                    "EEE, dd MMM yyyy HH:mm:ss Z",
                    Locale.ENGLISH
            );

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher transaction = TRANSACTION.matcher(m.body());
        Matcher date = DATE.matcher(m.body());

        if (!transaction.find() || !date.find()) {
            return Optional.empty();
        }

        Direction direction = "debited".equals(transaction.group("dir"))
                ? Direction.DEBIT
                : Direction.CREDIT;

        try {
           BigDecimal amount = new BigDecimal(
        transaction.group("amount").replace(",", "")
).setScale(2);

          OffsetDateTime occurredAt = OffsetDateTime.parse(
        date.group("date"),
        EMAIL_DATE
).withOffsetSameInstant(Dates.IST);

            return Optional.of(new ParsedTxn(
                    transaction.group("acct"),
                    occurredAt,
                    direction,
                    amount,
                    transaction.group("merchant").trim(),
                    null,
                    m.messageId()
            ));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}