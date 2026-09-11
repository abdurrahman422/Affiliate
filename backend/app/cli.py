"""OwnerLink maintenance CLI.

Change the owner password after first boot:

    python -m app.cli set-password
"""

from __future__ import annotations

import argparse
import getpass
import sys

from .bootstrap import bootstrap_owner, validate_runtime_settings
from .config import get_settings
from .db import init_db, make_engine, session_factory
from .hashing import hash_password
from .models import Owner


def _session():
    settings = get_settings()
    engine = make_engine(settings.DATABASE_URL)
    init_db(engine)
    return session_factory(engine)()


def cmd_set_password(_args: argparse.Namespace) -> int:
    db = _session()
    try:
        owner = db.query(Owner).first()
        if owner is None:
            print("No owner account exists. Start the server once to bootstrap.", file=sys.stderr)
            return 1
        password = getpass.getpass("New password: ")
        confirm = getpass.getpass("Confirm password: ")
        if password != confirm:
            print("Passwords do not match.", file=sys.stderr)
            return 1
        if len(password) < 12:
            print("Password must be at least 12 characters.", file=sys.stderr)
            return 1
        owner.password_hash = hash_password(password)
        db.commit()
        print("Password updated.")
        return 0
    finally:
        db.close()


def cmd_bootstrap(_args: argparse.Namespace) -> int:
    settings = get_settings()
    validate_runtime_settings(settings)
    db = _session()
    try:
        bootstrap_owner(db, settings)
        return 0
    finally:
        db.close()


def main() -> int:
    parser = argparse.ArgumentParser(prog="ownerlink")
    sub = parser.add_subparsers(dest="command", required=True)
    p_set = sub.add_parser("set-password", help="Change the owner password")
    p_set.set_defaults(func=cmd_set_password)
    p_boot = sub.add_parser("bootstrap", help="Create owner from environment if missing")
    p_boot.set_defaults(func=cmd_bootstrap)
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
