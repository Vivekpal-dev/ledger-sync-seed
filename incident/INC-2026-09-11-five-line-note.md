INC-2026-09-11 — Five-line incident note



Impact: Account 4821 displayed ₹92,213.10 for a ₹5 water-can transaction.

Root cause: The amount parser required two decimal places and skipped `Rs.5`, then incorrectly captured the available balance as the transaction amount.

Blast radius: 9 messages were affected by the same parsing pattern before the fix.

Fix: Updated amount parsing to accept whole-rupee amounts and added a regression test for `Rs.5`.

Validation: The incident reproduction now returns ₹5.00 and the affected-message scan returns 0.

