
import re
import sqlite3
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]

DATALAKE_PATH = PROJECT_ROOT / "datalake"
DATAMARTS_PATH = PROJECT_ROOT / "datamarts"
DB_PATH = DATAMARTS_PATH / "metadata.db"


def create_metadata_db(db_path=DB_PATH):
    db_path = Path(db_path)
    db_path.parent.mkdir(parents=True, exist_ok=True)

    with sqlite3.connect(db_path) as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS books (
                book_id TEXT PRIMARY KEY,
                title TEXT,
                author TEXT,
                language TEXT,
                body_path TEXT
            )
        """)


def parse_header_file(header_path):
    content = Path(header_path).read_text(
        encoding="utf-8",
        errors="ignore"
    )

    title_match = re.search(
        r"^Title:\s*(.+)$",
        content,
        re.IGNORECASE | re.MULTILINE
    )

    author_match = re.search(
        r"^Author:\s*(.+)$",
        content,
        re.IGNORECASE | re.MULTILINE
    )

    language_match = re.search(
        r"^Language:\s*(.+)$",
        content,
        re.IGNORECASE | re.MULTILINE
    )

    title = title_match.group(1).strip() if title_match else "Unknown"
    author = author_match.group(1).strip() if author_match else "Unknown"
    language = language_match.group(1).strip() if language_match else "Unknown"

    return title, author, language


def insert_book_metadata(book_id, header_path, body_path, db_path=DB_PATH):
    header_path = Path(header_path)
    body_path = Path(body_path)

    if not header_path.is_file():
        raise FileNotFoundError(f"Header no encontrado: {header_path}")

    if not body_path.is_file():
        raise FileNotFoundError(f"Body no encontrado: {body_path}")

    create_metadata_db(db_path)

    title, author, language = parse_header_file(header_path)

    relative_body_path = body_path.resolve().relative_to(PROJECT_ROOT)

    with sqlite3.connect(db_path) as conn:
        conn.execute("""
            INSERT OR REPLACE INTO books (
                book_id, title, author, language, body_path
            )
            VALUES (?, ?, ?, ?, ?)
        """, (
            str(book_id),
            title,
            author,
            language,
            relative_body_path.as_posix()
        ))

    print(
        f"[DATAMART METADATA] Procesado libro {book_id}: "
        f"'{title}' por {author}"
    )


def main():
    create_metadata_db()

    header_files = sorted(DATALAKE_PATH.rglob("*.header.txt"))

    if not header_files:
        print("No se encontraron archivos de cabecera en el Datalake.")
        return

    for header_file in header_files:
        book_id = header_file.name.split(".")[0]
        body_file = header_file.parent / f"{book_id}.body.txt"

        if not body_file.is_file():
            print(f"Body no encontrado para el libro {book_id}")
            continue

        insert_book_metadata(book_id, header_file, body_file)


if __name__ == "__main__":
    main()
