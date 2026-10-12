# Customer Payments API

The payment ledger is customer-level and append-only. It does not allocate money
to invoices. All routes require the existing raw `token` request header.

## Balance

For active assigned SELL invoices:

```text
grossAssignedAmount = sum(active assigned SELL invoice totals)
netPaidAmount       = sum(PAYMENT amounts) - sum(REVERSAL amounts)
totalOwed           = max(grossAssignedAmount - netPaidAmount, 0)
creditBalance       = max(netPaidAmount - grossAssignedAmount, 0)
currency            = "som"
```

Invoice voiding, deassignment, or reassignment can create credit. Credit is
retained and automatically offsets later invoice assignments. A new payment is
rejected when it exceeds the current `totalOwed`.

## Record a payment

`POST /api/customers/{customerId}/payments`

Headers:

```text
token: <raw session token>
Idempotency-Key: <unique client-generated value>
```

Body:

```json
{
  "amount": 5000.00,
  "method": "CASH",
  "paidAt": "2026-07-28T10:30:00",
  "note": "Optional note"
}
```

`method` is `CASH`, `CARD`, `BANK_TRANSFER`, or `OTHER`. `paidAt` is optional
and defaults to server time. Amount must be positive, have at most two decimal
places, and not exceed current debt. Notes are trimmed and limited to 500
characters.

Returns `201`:

```json
{
  "payment": {
    "id": 41,
    "type": "PAYMENT",
    "amount": 5000.00,
    "currency": "som",
    "method": "CASH",
    "occurredAt": "2026-07-28T10:30:00",
    "createdAt": "2026-07-28T10:31:02",
    "note": "Optional note",
    "reversalOfPaymentId": null
  },
  "customer": {
    "id": 12,
    "name": "Aizada",
    "phone": null,
    "totalOwed": 5000.0,
    "grossAssignedAmount": 10000.0,
    "netPaidAmount": 5000.0,
    "creditBalance": 0.0,
    "currency": "som",
    "assignedInvoiceCount": 1,
    "createdAt": "2026-07-20T09:00:00",
    "updatedAt": "2026-07-20T09:00:00"
  }
}
```

## Reverse a payment

`POST /api/customers/{customerId}/payments/{paymentId}/reversal`

A new `Idempotency-Key` is required.

```json
{ "reason": "Wrong customer selected" }
```

The trimmed reason is required and limited to 500 characters. Reversal is
always for the full original amount. Returns `200` with the same mutation
response shape; the returned entry has type `REVERSAL`, a null method, and
`reversalOfPaymentId` set to the original payment.

## Payment history

`GET /api/customers/{customerId}/payments?page=0&size=20`

Returns entries ordered by `occurredAt DESC, id DESC`:

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalItems": 0,
  "totalPages": 0,
  "hasNext": false
}
```

Page numbers are zero-based. Non-positive size uses 20 and size is capped at
100.

## Idempotency and errors

Idempotency keys are unique per authenticated user. Repeating the same
normalized request returns its original entry without changing the balance.
Reusing a key for different normalized data returns `409`.

- `400`: missing/invalid key or body, future date, invalid precision/amount, or
  text longer than 500 characters.
- `404`: customer or payment is missing or belongs to another user.
- `409`: overpayment, different idempotent replay, non-payment reversal target,
  or an already reversed payment.

Payment/reversal and its `PAYMENT`/`PAYMENT_REVERSED` Logbook entry commit in
one transaction. Logbook payment amounts are positive and reversal amounts are
negative.
