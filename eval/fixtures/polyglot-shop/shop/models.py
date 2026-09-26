from dataclasses import dataclass, field
from decimal import Decimal


@dataclass
class LineItem:
    sku: str
    qty: int
    unit_price: Decimal

    @property
    def total(self) -> Decimal:
        return self.unit_price * self.qty


@dataclass
class Order:
    id: int
    customer_id: int
    items: list = field(default_factory=list)

    def subtotal(self) -> Decimal:
        return sum((i.total for i in self.items), Decimal("0"))
