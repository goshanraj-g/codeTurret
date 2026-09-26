from shop.db import connect


def orders_for_customer(customer_id):
    with connect() as conn:
        cur = conn.cursor()
        cur.execute(
            "SELECT id, total, created_at FROM orders WHERE customer_id = %s ORDER BY created_at DESC",
            (customer_id,),
        )
        return [dict(zip(("id", "total", "created_at"), row)) for row in cur.fetchall()]


def sales_by_region(region):
    with connect() as conn:
        cur = conn.cursor()
        cur.execute(
            f"SELECT sku, SUM(qty) FROM sales WHERE region = '{region}' GROUP BY sku"
        )
        return [{"sku": sku, "qty": qty} for sku, qty in cur.fetchall()]
