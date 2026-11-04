
import requests
from datetime import datetime
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]

DATALAKE_PATH = PROJECT_ROOT / "datalake"
CONTROL_PATH = PROJECT_ROOT / "control"
DOWNLOADED_PATH = CONTROL_PATH / "downloaded_books.txt"

START_MARKER = "*** START OF THE PROJECT GUTENBERG EBOOK"
END_MARKER = "*** END OF THE PROJECT GUTENBERG EBOOK"


def download_book(book_id):
    url = f"https://www.gutenberg.org/cache/epub/{book_id}/pg{book_id}.txt"

    try:
        response = requests.get(url, timeout=30)
        response.raise_for_status()
    except requests.RequestException as error:
        print("Error al descargar el libro:", error)
        return False

    text = response.text

    if START_MARKER not in text or END_MARKER not in text:
        print(f"No se encontraron los marcadores del libro {book_id}")
        return False

    header, body_and_footer = text.split(START_MARKER, 1)
    body, footer = body_and_footer.split(END_MARKER, 1)

    now = datetime.now()

    date = now.strftime("%Y%m%d")
    hour = now.strftime("%H")

    folder = DATALAKE_PATH / date / hour
    folder.mkdir(parents=True, exist_ok=True)

    header_path = folder / f"{book_id}.header.txt"
    body_path = folder / f"{book_id}.body.txt"

    try:
        header_path.write_text(header, encoding="utf-8")
        body_path.write_text(body, encoding="utf-8")
    except OSError as error:
        print(f"Error al guardar el libro {book_id}: {error}")
        return False

    mark_as_downloaded(book_id)

    print("Header guardado en:", header_path)
    print("Body guardado en:", body_path)

    return True


def mark_as_downloaded(book_id):
    CONTROL_PATH.mkdir(parents=True, exist_ok=True)
    DOWNLOADED_PATH.touch(exist_ok=True)

    downloaded_books = set(
        DOWNLOADED_PATH.read_text(encoding="utf-8").splitlines()
    )

    if str(book_id) not in downloaded_books:
        with DOWNLOADED_PATH.open("a", encoding="utf-8") as file:
            file.write(f"{book_id}\n")


if __name__ == "__main__":
    download_book(345)
    download_book(1342)
    download_book(84)
    download_book(99999999)
