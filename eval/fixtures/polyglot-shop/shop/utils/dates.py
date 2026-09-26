from datetime import date, timedelta


def business_days_between(start: date, end: date) -> int:
    days = 0
    current = start
    while current < end:
        if current.weekday() < 5:
            days += 1
        current += timedelta(days=1)
    return days
