"""Event loop factory supported by psycopg's async connection on Windows."""
import asyncio
import selectors


def selector_loop():
    return asyncio.SelectorEventLoop(selectors.SelectSelector())
