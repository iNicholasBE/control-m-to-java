#!/usr/bin/env python3
"""Write a synthetic POSITIONS-<date>.csv for the nightly close chain.

40 columns, 20 portfolios of uneven size, one wide free-text column. Deterministic: the same
--seed, --date and --rows always produce a byte-identical file.

    python3 gen_positions.py --date 2026-10-23 --rows 200000 --out-dir ./inbox
"""
import argparse
import datetime as dt
import os
import random

HEADER = [
    "position_id", "business_date", "portfolio", "book", "desk", "trader_id", "instrument_id", "instrument_type",
    "currency", "counterparty_id", "counterparty_rating", "country", "sector", "quantity", "price", "notional",
    "market_value", "accrued_interest", "fx_rate", "notional_eur", "duration", "convexity", "delta", "gamma",
    "vega", "theta", "volatility", "pd", "lgd", "maturity_date", "trade_date", "settlement_date", "coupon_rate",
    "spread_bps", "haircut", "collateral_eur", "limit_eur", "status", "source_system", "comments",
]
DESKS = ["EQUITY", "RATES", "CREDIT", "FX", "COMMODITIES"]
TYPES = ["BOND", "EQUITY", "FUTURE", "SWAP", "OPTION", "REPO"]
FX = {"EUR": 1.0, "USD": 0.9213, "GBP": 1.1702, "CHF": 1.0461, "JPY": 0.0061, "SEK": 0.0874}
RATINGS = [("AAA", 0.0001), ("AA", 0.0003), ("A", 0.0008), ("BBB", 0.0025), ("BB", 0.011), ("B", 0.045)]
COUNTRIES = ["BE", "NL", "DE", "FR", "LU", "IT", "ES", "GB", "US", "CH", "SE", "JP"]
SECTORS = ["FINANCIALS", "UTILITIES", "ENERGY", "INDUSTRIALS", "HEALTHCARE", "TECHNOLOGY", "SOVEREIGN", "REAL_ESTATE"]
STATUSES = ["ACTIVE"] * 18 + ["PENDING_SETTLEMENT", "AMENDED"]
SOURCES = ["MUREX", "CALYPSO", "SUMMIT", "MANUAL"]
WORDS = ("hedge rebalanced after close collateral call received margin agreed with counterparty desk approval "
         "limit breach reviewed restructured booking corrected price challenge accepted month end netting set "
         "pending confirmation legal entity mapping reconciled against custodian statement override approved by "
         "risk manager stale price replaced vendor curve sensitivity recalculated").split()


def portfolio_weights(rng, count):
    weights = [rng.uniform(0.5, 1.5) for _ in range(count)]
    total = sum(weights)
    return [w / total for w in weights]


def generate(path, business_date, rows, portfolios, seed):
    rng = random.Random(f"{seed}|{business_date}|{rows}")
    codes = [f"PF{i:02d}" for i in range(1, portfolios + 1)]
    weights = portfolio_weights(rng, portfolios)
    currencies = list(FX)
    bd = business_date.isoformat()
    tmp = path + ".part"
    with open(tmp, "w", encoding="utf-8", newline="\n") as out:
        out.write(",".join(HEADER) + "\n")
        for n in range(1, rows + 1):
            portfolio = rng.choices(codes, weights)[0]
            desk = rng.choice(DESKS)
            itype = rng.choice(TYPES)
            ccy = rng.choice(currencies)
            fx = FX[ccy]
            rating, base_pd = rng.choice(RATINGS)
            quantity = rng.randint(1, 50_000)
            price = rng.uniform(0.5, 250.0)
            notional = quantity * price * rng.uniform(0.8, 1.2)
            market_value = notional * rng.uniform(0.9, 1.1)
            notional_eur = notional * fx
            maturity = business_date + dt.timedelta(days=rng.randint(30, 30 * 365))
            trade = business_date - dt.timedelta(days=rng.randint(0, 3 * 365))
            settle = trade + dt.timedelta(days=2)
            collateral = notional_eur * rng.uniform(0.0, 0.6)
            comment = " ".join(rng.choice(WORDS) for _ in range(rng.randint(20, 40)))
            out.write(
                f"P{n:09d},{bd},{portfolio},BK-{desk[:2]}-{rng.randint(1, 400):04d},{desk},T{rng.randint(1, 250):04d},"
                f"XS{rng.randint(10**9, 10**10 - 1)},{itype},{ccy},CP{rng.randint(1, 5000):05d},{rating},"
                f"{rng.choice(COUNTRIES)},{rng.choice(SECTORS)},{quantity},{price:.4f},{notional:.2f},"
                f"{market_value:.2f},{notional * rng.uniform(0, 0.02):.2f},{fx:.6f},{notional_eur:.2f},"
                f"{rng.uniform(0.1, 25):.4f},{rng.uniform(0, 4):.4f},{rng.uniform(-1, 1):.4f},{rng.uniform(0, 0.05):.6f},"
                f"{rng.uniform(-5000, 5000):.2f},{rng.uniform(-800, 0):.2f},{rng.uniform(0.05, 0.65):.6f},"
                f"{base_pd * rng.uniform(0.5, 1.5):.6f},{rng.uniform(0.2, 0.75):.4f},{maturity.isoformat()},"
                f"{trade.isoformat()},{settle.isoformat()},{rng.uniform(0, 0.08):.4f},{rng.uniform(5, 900):.2f},"
                f"{rng.uniform(0, 0.25):.4f},{collateral:.2f},{notional_eur * rng.uniform(1.0, 3.0):.2f},"
                f"{rng.choice(STATUSES)},{rng.choice(SOURCES)},\"{comment}\"\n"
            )
    os.replace(tmp, path)  # atomic: a file watcher never sees half a file


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--date", required=True, type=dt.date.fromisoformat, help="business date, YYYY-MM-DD")
    parser.add_argument("--rows", type=int, default=200_000, help="200000 for 1x, 2000000 for 10x")
    parser.add_argument("--portfolios", type=int, default=20)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--out-dir", default=".")
    args = parser.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)
    path = os.path.join(args.out_dir, f"POSITIONS-{args.date.isoformat()}.csv")
    generate(path, args.date, args.rows, args.portfolios, args.seed)
    print(path)


if __name__ == "__main__":
    main()
