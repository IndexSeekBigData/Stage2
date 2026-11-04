
import json
import os
import re
import tempfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]

DATALAKE_PATH = PROJECT_ROOT / "datalake"
DATAMARTS_PATH = PROJECT_ROOT / "datamarts"
CONTROL_PATH = PROJECT_ROOT / "control"

INDEX_PATH = DATAMARTS_PATH / "inverted_index.json"
INDEXED_PATH = CONTROL_PATH / "indexed_books.txt"


def tokenize(text):
    words = re.findall(r"\b[a-zA-Z]{3,}\b", text.lower())
    return set(words)


def load_existing_index(index_path=INDEX_PATH):
    index_path = Path(index_path)

    if not index_path.exists():
        return {}

    with index_path.open("r", encoding="utf-8") as file:
        return json.load(file)


def save_index(index_data, index_path=INDEX_PATH):
    index_path = Path(index_path)
    index_path.parent.mkdir(parents=True, exist_ok=True)

    temporary_path = None

    try:
        with tempfile.NamedTemporaryFile(
                mode="w",
                encoding="utf-8",
                dir=index_path.parent,
                suffix=".tmp",
                delete=False
        ) as file:
            temporary_path = Path(file.name)
            json.dump(index_data, file, ensure_ascii=False, indent=2)

        os.replace(temporary_path, index_path)

    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def mark_as_indexed(book_id):
    CONTROL_PATH.mkdir(parents=True, exist_ok=True)
    INDEXED_PATH.touch(exist_ok=True)

    indexed_books = set(
        INDEXED_PATH.read_text(encoding="utf-8").splitlines()
    )

    if str(book_id) not in indexed_books:
        with INDEXED_PATH.open("a", encoding="utf-8") as file:
            file.write(f"{book_id}\n")


def index_book(book_id, body_path, index_path=INDEX_PATH):
    body_path = Path(body_path)

    if not body_path.is_file():
        raise FileNotFoundError(f"Body no encontrado: {body_path}")

    text = body_path.read_text(encoding="utf-8", errors="ignore")

    terms = tokenize(text)
    inverted_index = load_existing_index(index_path)

    book_id_str = str(book_id)

    for term in sorted(terms):
        postings = inverted_index.setdefault(term, [])

        if book_id_str not in postings:
            postings.append(book_id_str)
            postings.sort(key=int)

    save_index(inverted_index, index_path)
    mark_as_indexed(book_id)

    print(
        f"[INDEXER] Libro {book_id} indexado con éxito "
        f"({len(terms)} términos únicos)."
    )


def search_term(term, index_path=INDEX_PATH):
    normalized_terms = tokenize(term)

    if len(normalized_terms) != 1:
        raise ValueError("Introduce un único término válido.")

    normalized_term = next(iter(normalized_terms))
    inverted_index = load_existing_index(index_path)

    return inverted_index.get(normalized_term, [])


def main():
    body_files = sorted(DATALAKE_PATH.rglob("*.body.txt"))

    if not body_files:
        print("No se encontraron archivos .body.txt en el Datalake.")
        return

    for body_file in body_files:
        book_id = body_file.name.split(".")[0]
        index_book(book_id, body_file)


if __name__ == "__main__":
    main()
