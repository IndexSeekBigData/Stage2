
from pathlib import Path

from inverted_index import index_book
from downloader import download_book

PROJECT_ROOT = Path(__file__).resolve().parents[2]

CONTROL_PATH = PROJECT_ROOT / "control"
DATALAKE_PATH = PROJECT_ROOT / "datalake"

DOWNLOADED_PATH = CONTROL_PATH / "downloaded_books.txt"
INDEXED_PATH = CONTROL_PATH / "indexed_books.txt"

BOOK_IDS = [1342, 345, 84, 11, 1661, 2701, 23, 456]


def initialize_control():
    CONTROL_PATH.mkdir(parents=True, exist_ok=True)
    DATALAKE_PATH.mkdir(parents=True, exist_ok=True)

    DOWNLOADED_PATH.touch(exist_ok=True)
    INDEXED_PATH.touch(exist_ok=True)


def read_book_ids(path):
    return {
        int(book_id.strip())
        for book_id in path.read_text(encoding="utf-8").splitlines()
        if book_id.strip()
    }


def get_pending_books():
    initialize_control()

    downloaded = read_book_ids(DOWNLOADED_PATH)
    indexed = read_book_ids(INDEXED_PATH)

    return sorted(downloaded - indexed)


def process_pending_book(book_id):
    body_files = sorted(
        DATALAKE_PATH.rglob(f"{book_id}.body.txt")
    )

    if not body_files:
        print(f"No se encontró el body del libro {book_id}")
        return False

    index_book(book_id, body_files[0])
    return True


def is_book_downloaded(book_id):
    initialize_control()

    downloaded = read_book_ids(DOWNLOADED_PATH)

    return book_id in downloaded


def download_new_book():
    for book_id in BOOK_IDS:
        if not is_book_downloaded(book_id):
            download_book(book_id)
            return True

    print("No quedan libros nuevos para descargar.")
    return False


def main():
    initialize_control()

    pending_books = get_pending_books()

    if pending_books:
        process_pending_book(pending_books[0])
    else:
        download_new_book()


if __name__ == "__main__":
    main()
