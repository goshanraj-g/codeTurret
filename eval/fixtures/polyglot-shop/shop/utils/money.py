from decimal import Decimal, ROUND_HALF_EVEN


def round_cents(amount: Decimal) -> Decimal:
    return amount.quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)


def apply_discount(amount: Decimal, percent: int) -> Decimal:
    if not 0 <= percent <= 100:
        raise ValueError("percent out of range")
    return round_cents(amount * (Decimal(100 - percent) / 100))
